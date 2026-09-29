package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxProtocolHandlerTest {
    @Test
    fun `the entry hands boss links to the launcher and stays out of menus`() {
        val entry = checkNotNull(LinuxProtocolHandler.desktopEntryFor("/opt/boss/bin/BOSS"))
        assertTrue("Exec=/opt/boss/bin/BOSS %u" in entry.lines())
        assertTrue("MimeType=x-scheme-handler/boss;" in entry.lines())
        assertTrue("NoDisplay=true" in entry.lines())
        // Never a browser candidate: that is boss.desktop's job, and only on request.
        assertTrue(entry.lines().none { it.startsWith("Categories=") })
    }

    @Test
    fun `a launcher path with a space is quoted`() {
        assertEquals("\"/home/me/My Apps/BOSS\"", LinuxProtocolHandler.execQuoted("/home/me/My Apps/BOSS"))
    }

    @Test
    fun `a launcher path needing escapes is refused rather than half escaped`() {
        assertNull(LinuxProtocolHandler.desktopEntryFor("/opt/\$HOME/BOSS"))
        assertNull(LinuxProtocolHandler.desktopEntryFor("/opt/a\"b/BOSS"))
    }
}
