package ai.rever.boss.components.sidebar

import ai.rever.boss.plugin.ui.TerminalTitleBarAction
import ai.rever.boss.plugin.ui.TerminalTitleBarBridge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalTitleBarBridgeTest {
    @Test
    fun `actions are isolated by window and removed with their owner`() {
        val first = Any()
        val second = Any()
        var clicks = 0
        val action = TerminalTitleBarAction("call", "Call", "phone", Icons.Default.Call, true) { clicks++ }
        try {
            assertFalse(TerminalTitleBarBridge.isHosted("test-first"))
            TerminalTitleBarBridge.hostWindow("test-first", true)
            assertTrue(TerminalTitleBarBridge.isHosted("test-first"))
            assertFalse(TerminalTitleBarBridge.isHosted("test-second"))
            TerminalTitleBarBridge.publish("test-first", first, true, listOf(action))
            TerminalTitleBarBridge.publish("test-second", second, true, listOf(action))
            TerminalTitleBarBridge.actions("test-first").single().onClick()
            assertEquals(1, clicks)
            TerminalTitleBarBridge.publish("test-first", first, false, listOf(action))
            assertTrue(TerminalTitleBarBridge.actions("test-first").isEmpty())
            assertEquals(1, TerminalTitleBarBridge.actions("test-second").size)
            TerminalTitleBarBridge.remove(second)
            assertTrue(TerminalTitleBarBridge.actions("test-second").isEmpty())
        } finally {
            TerminalTitleBarBridge.remove(first)
            TerminalTitleBarBridge.remove(second)
            TerminalTitleBarBridge.hostWindow("test-first", false)
        }
    }
}
