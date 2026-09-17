package ai.rever.boss.search

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes file replacement operations to prevent race conditions when replacing text
 * in closed files. This is instantiated globally and shared across all [ContentSearchService]
 * instances to protect the process-shared file system from concurrent writes.
 */
class ClosedFileReplacementCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Executes the given [block] holding a lock specific to the [canonicalPath].
     */
    suspend fun <T> withFileLock(canonicalPath: String, block: suspend () -> T): T {
        val mutex = locks.getOrPut(canonicalPath) { Mutex() }
        return mutex.withLock {
            block()
        }
    }
}

/**
 * The process-wide shared coordinator used by [ContentSearchService] by default, since
 * [ContentSearchService] instances are window-scoped while the filesystem is process-shared.
 */
val GlobalClosedFileReplacementCoordinator = ClosedFileReplacementCoordinator()
