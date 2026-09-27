package ai.rever.boss.plugin.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.vector.ImageVector

/** UI-thread-only, window-scoped terminal controls hosted by an optional native titlebar. */
class TerminalTitleBarAction(
    val id: String,
    val label: String,
    val symbol: String,
    val icon: ImageVector,
    val active: Boolean,
    val onClick: () -> Unit,
)

/** Unsupported hosts leave the window unclaimed, retaining the terminal's floating controls. */
object TerminalTitleBarBridge {
    private data class Entry(
        val windowId: String,
        val active: Boolean,
        val actions: List<TerminalTitleBarAction>,
    )

    private val windows = mutableStateMapOf<String, Boolean>()
    private val entries = mutableStateMapOf<Any, Entry>()

    fun hostWindow(
        windowId: String,
        enabled: Boolean,
    ) {
        if (enabled) windows[windowId] = true else windows.remove(windowId)
    }

    fun isHosted(windowId: String): Boolean = windows[windowId] == true

    fun publish(
        windowId: String,
        owner: Any,
        active: Boolean,
        actions: List<TerminalTitleBarAction>,
    ) {
        entries[owner] = Entry(windowId, active, actions)
    }

    fun remove(owner: Any) {
        entries.remove(owner)
    }

    fun actions(windowId: String): List<TerminalTitleBarAction> =
        entries.values
            .lastOrNull { it.windowId == windowId && it.active }
            ?.actions
            .orEmpty()
}
