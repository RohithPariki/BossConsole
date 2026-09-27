package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Pointer

/** AppKit draws one shared glass background per group, preserving each subitem's action. */
internal object MacToolbarGroups {
    val members =
        mapOf(
            "terminal_controls" to listOf("terminal_sharing", "terminal_call", "terminal_mcp"),
            "utility_controls" to listOf("search", "tools", "toolbox"),
        )

    fun identifiers(actions: List<String>): List<String> =
        actions.map { id -> members.entries.firstOrNull { id in it.value }?.key ?: id }.distinct()

    fun create(id: String): Pointer {
        val group =
            checkNotNull(
                pointer(pointer(clazz("NSToolbarItemGroup"), "alloc"), "initWithItemIdentifier:", string(id)),
            )
        send(group, "setBordered:", 1.toByte())
        send(group, "setControlRepresentation:", 1L) // Expanded: keep every action visible.
        send(group, "setSelectionMode:", 2L) // Momentary: these are actions, not selectable tabs.
        val label = if (id == "terminal_controls") "Sharing, Call and MCP" else "Search and Tools"
        send(group, "setLabel:", string(label))
        return group
    }

    fun update(
        id: String,
        group: Pointer,
        available: Set<String>,
        previous: MutableMap<String, List<String>>,
        makeItem: (Pointer?) -> Pointer?,
    ) {
        val present = members.getValue(id).filter { it in available }
        if (previous[id] == present) return
        val array = pointer(clazz("NSMutableArray"), "array")
        present.mapNotNull { makeItem(string(it)) }.forEach { send(array, "addObject:", it) }
        send(group, "setSubitems:", array)
        previous[id] = present
    }
}
