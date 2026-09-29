package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Registers BOSS as the `boss://` handler on Linux, at startup, the way [WindowsProtocolHandler]
 * does on Windows.
 *
 * Before this, `boss://` was registered on Linux only as a side effect of making BOSS the
 * default browser or a default app (`LinuxDefaultBrowserHandler`), so on a fresh install a
 * Google or Apple sign-in, and the magic link, had no way back into the app.
 *
 * It writes its own hidden entry, [DESKTOP_FILE_NAME], rather than the `boss.desktop` the
 * default-browser flow owns: that one lists `http`, `https` and every file type under
 * `Categories=WebBrowser`, and writing it at every launch would put BOSS forward as a browser
 * candidate nobody asked for. The association is claimed only when nothing else holds it, so a
 * `boss.desktop` the user set up, or a packaged entry, is left alone.
 *
 * Skipped under a bare `java` launcher (a Gradle run), where the command line would not start
 * BOSS again.
 */
internal object LinuxProtocolHandler {
    private val logger = BossLogger.forComponent("LinuxProtocolHandler")

    const val DESKTOP_FILE_NAME = "boss-url-handler.desktop"
    private const val SCHEME_MIME = "x-scheme-handler/boss"
    private const val COMMAND_TIMEOUT_SECONDS = 5L

    /** Reserved in an `Exec` argument; any one of them forces quoting. */
    private const val RESERVED = " \t\n'><~|&;*?#()"

    /** Would need escaping inside the quotes on top of the string-level escaping; refused instead. */
    private val UNQUOTABLE = setOf('"', '`', '$', '\\')

    private val applicationsDir = File(System.getProperty("user.home"), ".local/share/applications")

    /** Idempotent; safe to call on every launch. Never throws. */
    // Each guard is a reason to leave the scheme alone, and startup must not throw.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun ensureRegistered() {
        try {
            val launcher = currentLauncher()
            if (launcher == null) {
                logger.debug(LogCategory.SYSTEM, "Not registering boss:// - not running from a packaged launcher")
                return
            }
            val content = desktopEntryFor(launcher)
            if (content == null) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Not registering boss:// - the launcher path cannot go in a desktop entry",
                )
                return
            }
            val holder = run("xdg-mime", "query", "default", SCHEME_MIME)?.trim().orEmpty()
            if (holder.isNotEmpty() && holder != DESKTOP_FILE_NAME) {
                logger.debug(LogCategory.SYSTEM, "boss:// already has a handler", mapOf("handler" to holder))
                return
            }
            val file = File(applicationsDir, DESKTOP_FILE_NAME)
            if (holder == DESKTOP_FILE_NAME && file.isFile && file.readText() == content) return
            applicationsDir.mkdirs()
            file.writeText(content)
            run("update-desktop-database", applicationsDir.absolutePath)
            val claimed = run("xdg-mime", "default", DESKTOP_FILE_NAME, SCHEME_MIME) != null
            logger.info(LogCategory.SYSTEM, "Registered boss:// handler", mapOf("claimed" to claimed))
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not register boss:// handler", error = e)
        }
    }

    /** The packaged launcher running this process, or null for a plain `java` launch. */
    private fun currentLauncher(): String? {
        val command =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null) ?: return null
        val name = File(command).name
        return command.takeUnless { name == "java" || name == "javaw" }
    }

    /**
     * The hidden desktop entry that hands `boss://` links to [launcher], or null when the path
     * carries a character the entry would have to escape twice over.
     */
    internal fun desktopEntryFor(launcher: String): String? {
        if (launcher.any { it in UNQUOTABLE }) return null
        return listOf(
            "[Desktop Entry]",
            "Version=1.0",
            "Type=Application",
            "Name=BOSS Console",
            "Comment=Opens boss:// links in BOSS",
            "Exec=${execQuoted(launcher)} %u",
            "Terminal=false",
            "NoDisplay=true",
            "MimeType=$SCHEME_MIME;",
        ).joinToString("\n", postfix = "\n")
    }

    /** [path] as one `Exec` argument: double-quoted when it holds a reserved character. */
    internal fun execQuoted(path: String): String = if (path.any { it in RESERVED }) "\"$path\"" else path

    /** [command]'s output when it exits 0 in time, else null. */
    private fun run(vararg command: String): String? =
        try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            // Waited on before reading: these commands print a line at most, well under a pipe
            // buffer, and reading first would block past the timeout on a wedged command.
            if (process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
                process.inputStream.bufferedReader().use { it.readText() }
            } else {
                process.destroyForcibly()
                null
            }
        } catch (e: IOException) {
            logger.debug(
                LogCategory.SYSTEM,
                "Command unavailable",
                mapOf("command" to command.first(), "reason" to (e.message ?: "")),
            )
            null
        }
}
