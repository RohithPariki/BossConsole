package ai.rever.boss.window

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals

/** Opt-in native smoke test: owns a small unfocusable window, never a user's app window. */
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_GLASS", matches = "1")
class MacWindowGlassSmokeTest {
    @Test
    fun `native material installs changes style and detaches`() {
        val events = LinkedBlockingQueue<Boolean>()
        lateinit var window: ComposeWindow
        lateinit var controller: MacWindowGlass
        val request = GlassRequest(true, IntSize(320, 180), dark = true, clear = false, fullscreen = false)
        SwingUtilities.invokeAndWait {
            window =
                ComposeWindow().apply {
                    title = "BOSS glass test"
                    isUndecorated = true
                    isTransparent = true
                    focusableWindowState = false
                    setSize(320, 180)
                    setContent { }
                    isVisible = true
                }
            controller = MacWindowGlass(window.windowHandle) { events.offer(it) }
        }
        try {
            controller.update(request)
            assertEquals(true, events.poll(10, TimeUnit.SECONDS), "native backdrop should install")
            controller.update(request.copy(dark = false, clear = true))
            assertEquals(true, events.poll(10, TimeUnit.SECONDS), "light clear glass should stay installed")
            controller.update(request.copy(enabled = false))
            assertEquals(false, events.poll(10, TimeUnit.SECONDS), "disabled glass must detach")
        } finally {
            controller.close()
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }
}
