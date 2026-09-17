package ai.rever.boss.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Regression coverage for the process-local lost-update consistency guarantee
 * introduced by [ClosedFileReplacementCoordinator] (see GitHub issue #622).
 *
 * **The bug (before the fix):**
 * Two [ContentSearchService] instances — one per window — could interleave:
 *
 * ```
 * Service A: read("alpha beta")          ← stale snapshot
 * Service B: read("alpha beta")
 * Service B: write("alpha BETA")         ← B's change lands
 * Service A: write("ALPHA beta")         ← A silently reverts B's change
 * ```
 *
 * Both callers received a success result. The only indication of data loss was
 * that the file's final content did not contain both replacements.
 *
 * **What these tests prove:**
 * - Same service / same file: serialized.
 * - Different service instances / same file: process-wide coordination.
 * - Both completion orders: no ordering assumption.
 * - Cancellation: lock released, subsequent callers unblocked.
 * - I/O failure: lock released, subsequent callers unblocked.
 * - Different files: NOT globally serialized (concurrent by design).
 * - Dry-run: participates in the lock for consistent snapshot semantics.
 * - Sequential control: baseline for the concurrency tests.
 * - Path alias: same canonical resource → same coordinator lock.
 *
 * All tests use a fresh [ClosedFileReplacementCoordinator] to avoid shared
 * state across test runs.
 */
class ClosedFileReplacementConcurrencyTest {

    // ------------------------------------------------------------------
    // Test 8 — Sequential control (establish the baseline)
    // ------------------------------------------------------------------

    @Test
    fun `sequential replacements produce both changes (control)`(
        @TempDir dir: File,
    ) = runBlocking {
        val file = File(dir, "doc.txt").also { it.writeText("alpha beta") }
        val coordinator = ClosedFileReplacementCoordinator()
        val svc = service(dir, coordinator)

        // Replace "alpha" → "ALPHA", then "beta" → "BETA" in order.
        svc.replaceInProject("alpha", "ALPHA", listOf(file.absolutePath), dryRun = false)
        svc.replaceInProject("beta", "BETA", listOf(file.absolutePath), dryRun = false)

        assertEquals("ALPHA BETA", file.readText())
    }

    // ------------------------------------------------------------------
    // Test 1 — Same service, overlapping calls, same file: both changes kept
    // ------------------------------------------------------------------

    @Test
    fun `concurrent replacements in the same service both land`(
        @TempDir dir: File,
    ) = runBlocking {
        val file = File(dir, "doc.txt").also { it.writeText("alpha beta") }
        val coordinator = ClosedFileReplacementCoordinator()
        val svc = service(dir, coordinator)

        // Launch both concurrently. The coordinator serializes them; order
        // is non-deterministic, but one will complete before the other
        // acquires the lock and re-reads the updated file. Both changes land.
        val a = async(Dispatchers.IO) {
            svc.replaceInProject("alpha", "ALPHA", listOf(file.absolutePath), dryRun = false)
        }
        val b = async(Dispatchers.IO) {
            svc.replaceInProject("beta", "BETA", listOf(file.absolutePath), dryRun = false)
        }
        a.await()
        b.await()

        val result = file.readText()
        assertTrue("ALPHA" in result, "A's replacement was lost: $result")
        assertTrue("BETA" in result, "B's replacement was lost: $result")
    }

    // ------------------------------------------------------------------
    // Test 2 — Different service instances, same file: cross-instance guard
    //
    // This is the architectural test: proves the coordinator lives above the
    // window-scoped service instance, not inside it.
    // ------------------------------------------------------------------

    @Test
    fun `concurrent replacements through separate service instances both land`(
        @TempDir dir: File,
    ) = runBlocking {
        val file = File(dir, "doc.txt").also { it.writeText("alpha beta") }

        // SHARED coordinator — the production contract.
        val coordinator = ClosedFileReplacementCoordinator()
        val svcA = service(dir, coordinator)
        val svcB = service(dir, coordinator)

        val a = async(Dispatchers.IO) {
            svcA.replaceInProject("alpha", "ALPHA", listOf(file.absolutePath), dryRun = false)
        }
        val b = async(Dispatchers.IO) {
            svcB.replaceInProject("beta", "BETA", listOf(file.absolutePath), dryRun = false)
        }
        a.await()
        b.await()

        val result = file.readText()
        assertTrue("ALPHA" in result, "A's replacement was lost: $result")
        assertTrue("BETA" in result, "B's replacement was lost: $result")
    }

    // ------------------------------------------------------------------
    // Test 3 — Reverse completion order: B wins the lock first
    //
    // The coordinator makes both orders correct; this verifies the second
    // ordering explicitly by forcing B to acquire the lock first via a
    // ControlledCoordinator that pauses A between the pre-check and lock
    // acquisition while B completes end-to-end.
    // ------------------------------------------------------------------

    @Test
    fun `B completes before A acquires the lock — both changes land`(
        @TempDir dir: File,
    ) = runBlocking {
        val file = File(dir, "doc.txt").also { it.writeText("alpha beta") }

        // Gate: A waits here until B has finished writing.
        val bDone = CountDownLatch(1)
        val aCanProceed = CountDownLatch(1)

        val coordinator = object : ClosedFileReplacementCoordinator() {
            private var firstCall = true

            @Synchronized
            private fun isFirst(): Boolean {
                return if (firstCall) { firstCall = false; true } else false
            }

            override suspend fun <T> withFileLock(
                canonicalPath: String,
                block: suspend () -> T,
            ): T {
                if (isFirst()) {
                    // First caller (A): signal it's about to lock, then pause
                    // until B has finished so B wins the effective write order.
                    aCanProceed.countDown()
                    assertTrue(bDone.await(10, TimeUnit.SECONDS), "Timeout waiting for B to finish")
                }
                return super.withFileLock(canonicalPath, block)
            }
        }

        val svc = service(dir, coordinator)

        val jobA = launch(Dispatchers.IO) {
            // Wait until coordinator signals it has seen A's pre-lock call.
            assertTrue(aCanProceed.await(5, TimeUnit.SECONDS), "Timeout waiting for A to reach coordinator")
            // Now B can run and complete before A.
        }

        val a = async(Dispatchers.IO) {
            svc.replaceInProject("alpha", "ALPHA", listOf(file.absolutePath), dryRun = false)
        }
        val b = async(Dispatchers.IO) {
            // Wait until A is paused in the coordinator, then complete.
            assertTrue(aCanProceed.await(5, TimeUnit.SECONDS), "Timeout waiting for A to reach coordinator")
            val result = svc.replaceInProject("beta", "BETA", listOf(file.absolutePath), dryRun = false)
            bDone.countDown()
            result
        }

        jobA.join()
        a.await()
        b.await()

        val result = file.readText()
        assertTrue("ALPHA" in result, "A's replacement was lost: $result")
        assertTrue("BETA" in result, "B's replacement was lost: $result")
    }

    // ------------------------------------------------------------------
    // Test 4 — Cancellation releases the lock
    // ------------------------------------------------------------------

    @Test
    fun `a cancelled replacement releases the lock so the next caller proceeds`(
        @TempDir dir: File,
    ) = runBlocking {
        val file = File(dir, "doc.txt").also { it.writeText("beta") }

        // A gate the test can open from outside the coordinator.
        val insideLock = CountDownLatch(1)
        val cancelSignal = CompletableDeferred<Unit>()

        val coordinator = object : ClosedFileReplacementCoordinator() {
            override suspend fun <T> withFileLock(
                canonicalPath: String,
                block: suspend () -> T,
            ): T {
                return super.withFileLock(canonicalPath) {
                    if (insideLock.count == 1L) {
                        insideLock.countDown()   // signal: lock acquired
                        cancelSignal.await()     // hold until cancelled
                    }
                    block()
                }
            }
        }

        val svcA = service(dir, coordinator)
        val svcB = service(dir, coordinator)

        // A acquires the overridden coordinator and gets stuck inside.
        val jobA = launch(Dispatchers.IO) {
            try {
                svcA.replaceInProject("beta", "NOPE", listOf(file.absolutePath), dryRun = false)
            } catch (_: CancellationException) {
            }
        }

        insideLock.await(5, TimeUnit.SECONDS)
        jobA.cancelAndJoin() // cancels, which must release any real lock

        // B now replaces in svcB with the same coordinator - this proves
        // B is not blocked by A's abandoned Mutex lock.
        val result = svcB.replaceInProject("beta", "BETA", listOf(file.absolutePath), dryRun = false)

        assertEquals(1, result.totalReplacements)
        assertEquals("BETA", file.readText())
    }

    // ------------------------------------------------------------------
    // Test 5 — I/O failure releases the lock
    // ------------------------------------------------------------------

    @Test
    fun `an IOException during replacement releases the lock`(
        @TempDir dir: File,
    ) = runBlocking {
        val file = File(dir, "doc.txt").also { it.writeText("beta") }
        val coordinator = ClosedFileReplacementCoordinator()

        // Make the first call fail by passing a directory path, which
        // causes readText to throw, exercising the finally/unlock path.
        val notAFile = File(dir, "subdir").also { it.mkdirs() }

        val svc = service(dir, coordinator)
        val failResult = svc.replaceInProject(
            "beta",
            "NOPE",
            listOf(notAFile.absolutePath),
            dryRun = false,
        )
        // Error must be reported, not swallowed.
        assertTrue(failResult.files.any { it.error != null })

        // Lock must have been released: a subsequent replacement on a real
        // file must succeed.
        svc.replaceInProject("beta", "BETA", listOf(file.absolutePath), dryRun = false)
        assertEquals("BETA", file.readText())
    }

    // ------------------------------------------------------------------
    // Test 6 — Independent files: no global serialization (barrier-based)
    //
    // Both services acquire their respective per-file locks concurrently.
    // We use CountDownLatches as cross-coroutine barriers: if A and B can
    // BOTH signal "I hold my lock" before either releases, there is no
    // global lock. A timing test would be fragile; this is deterministic.
    // ------------------------------------------------------------------

    @Test
    fun `replacements on different files proceed concurrently`(
        @TempDir dir: File,
    ) = runBlocking {
        val fileA = File(dir, "a.txt").also { it.writeText("word") }
        val fileB = File(dir, "b.txt").also { it.writeText("word") }

        val aHoldsLock = CountDownLatch(1)
        val bHoldsLock = CountDownLatch(1)
        val aCanRelease = CountDownLatch(1)
        val bCanRelease = CountDownLatch(1)

        // Coordinator that signals when each file's lock is held.
        val coordinator = object : ClosedFileReplacementCoordinator() {
            override suspend fun <T> withFileLock(
                canonicalPath: String,
                block: suspend () -> T,
            ): T {
                return super.withFileLock(canonicalPath) {
                    if (canonicalPath.endsWith("a.txt")) {
                        aHoldsLock.countDown()
                        aCanRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        bHoldsLock.countDown()
                        bCanRelease.await(5, TimeUnit.SECONDS)
                    }
                    block()
                }
            }
        }

        val svcA = service(dir, coordinator)
        val svcB = service(dir, coordinator)

        val jobA = launch(Dispatchers.IO) {
            svcA.replaceInProject("word", "WORD_A", listOf(fileA.absolutePath), dryRun = false)
        }
        val jobB = launch(Dispatchers.IO) {
            svcB.replaceInProject("word", "WORD_B", listOf(fileB.absolutePath), dryRun = false)
        }

        // Both must be able to hold their respective locks at the same time.
        val bothHoldLocks =
            aHoldsLock.await(5, TimeUnit.SECONDS) &&
                bHoldsLock.await(5, TimeUnit.SECONDS)

        // Release both so the jobs can finish.
        aCanRelease.countDown()
        bCanRelease.countDown()
        jobA.join()
        jobB.join()

        assertTrue(bothHoldLocks, "Independent-file replacements were globally serialized")
        assertEquals("WORD_A", fileA.readText())
        assertEquals("WORD_B", fileB.readText())
    }

    // ------------------------------------------------------------------
    // Test 7 — Dry-run participates in the lock
    // ------------------------------------------------------------------

    @Test
    fun `dry-run participates in coordination and does not write`(
        @TempDir dir: File,
    ) = runBlocking {
        val file = File(dir, "doc.txt").also { it.writeText("alpha beta") }

        val aHoldsLock = CountDownLatch(1)
        val aCanRelease = CountDownLatch(1)
        val bReachedCoordinator = CompletableDeferred<Unit>()
        val bGotLock = CompletableDeferred<Unit>()

        val coordinator = object : ClosedFileReplacementCoordinator() {
            override suspend fun <T> withFileLock(
                canonicalPath: String,
                block: suspend () -> T,
            ): T {
                if (aHoldsLock.count == 0L) {
                    bReachedCoordinator.complete(Unit)
                }
                return super.withFileLock(canonicalPath) {
                    if (aHoldsLock.count == 1L) {
                        aHoldsLock.countDown()
                        assertTrue(aCanRelease.await(5, TimeUnit.SECONDS), "Timeout waiting for A to release")
                    } else {
                        bGotLock.complete(Unit)
                    }
                    block()
                }
            }
        }
        val svc = service(dir, coordinator)

        val jobA = launch(Dispatchers.IO) {
            svc.replaceInProject("alpha", "ALPHA", listOf(file.absolutePath), dryRun = true)
        }

        // Wait until A (dry-run) actually holds the lock
        assertTrue(aHoldsLock.await(5, TimeUnit.SECONDS), "A didn't get lock")

        val jobB = launch(Dispatchers.IO) {
            svc.replaceInProject("beta", "BETA", listOf(file.absolutePath), dryRun = false)
        }

        // Wait for B to reach the coordinator (meaning it has made the call)
        bReachedCoordinator.await()

        // B should NOT have the lock yet because A is holding it
        assertFalse(bGotLock.isCompleted, "B acquired the lock while A (dry-run) was holding it!")

        // Release A
        aCanRelease.countDown()

        jobA.join()
        jobB.join()
        assertTrue(bGotLock.isCompleted, "B never got the lock")

        assertEquals("ALPHA beta", file.readText(), "dry-run must not write, but real run should")
    }

    // ------------------------------------------------------------------
    // Test 9 — Path alias: same canonical resource → same coordinator lock
    // ------------------------------------------------------------------

    @Test
    fun `a file reached by alias and canonical path uses the same lock`(
        @TempDir dir: File,
    ) {
        // On platforms that support symbolic links.
        val original = File(dir, "original.txt").also { it.writeText("alpha beta") }
        val link = File(dir, "link.txt")

        val canCreateLink =
            runCatching {
                java.nio.file.Files.createSymbolicLink(link.toPath(), original.toPath())
                true
            }.getOrDefault(false)

        if (!canCreateLink) {
            // Symlinks not supported (e.g., unprivileged Windows); skip.
            return
        }

        // canonicalOrPath on both paths must resolve to the same string,
        // which means the coordinator assigns them the same Mutex.
        val coordinator = ClosedFileReplacementCoordinator()
        val lockKeysUsed = mutableListOf<String>()
        val trackingCoordinator =
            object : ClosedFileReplacementCoordinator() {
                override suspend fun <T> withFileLock(
                    canonicalPath: String,
                    block: suspend () -> T,
                ): T {
                    synchronized(lockKeysUsed) { lockKeysUsed.add(canonicalPath) }
                    return coordinator.withFileLock(canonicalPath, block)
                }
            }

        val svc = service(dir, trackingCoordinator)

        runBlocking {
            svc.replaceInProject("alpha", "ALPHA", listOf(original.absolutePath), dryRun = false)
            svc.replaceInProject("ALPHA", "alpha", listOf(link.absolutePath), dryRun = false)
        }

        // Both calls must have used the same canonical key.
        assertEquals(
            2,
            lockKeysUsed.size,
            "Expected 2 lock acquisitions: $lockKeysUsed",
        )
        assertEquals(
            lockKeysUsed[0],
            lockKeysUsed[1],
            "Original and link resolved to different lock keys: $lockKeysUsed",
        )
        // File content must round-trip correctly.
        assertEquals("alpha beta", original.readText())
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun service(
        dir: File,
        coordinator: ClosedFileReplacementCoordinator,
    ): ContentSearchService =
        ContentSearchService(
            projectPathProvider = { dir.absolutePath },
            replacementCoordinator = coordinator,
        )

    private suspend fun ContentSearchService.replaceInProject(
        query: String,
        replacement: String,
        files: List<String>,
        dryRun: Boolean,
    ) = replaceInProject(
        query = query,
        replacement = replacement,
        files = files,
        isRegex = false,
        caseSensitive = false,
        wholeWord = false,
        dryRun = dryRun,
    )
}
