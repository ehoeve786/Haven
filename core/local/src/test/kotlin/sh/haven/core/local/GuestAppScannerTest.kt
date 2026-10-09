package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit coverage for the pure half of [GuestAppScanner]: Exec field-code
 * stripping, `[Desktop Entry]` skip rules, and icon-path resolution order.
 * No Android / no proot — plain JUnit + temp dirs.
 */
class GuestAppScannerTest {

    // --- stripExecCodes -----------------------------------------------------

    @Test
    fun `strips trailing file and url field codes`() {
        assertEquals("gimp", DesktopEntryParser.stripExecCodes("gimp %U"))
        assertEquals("mpv", DesktopEntryParser.stripExecCodes("mpv %f"))
        assertEquals("imv", DesktopEntryParser.stripExecCodes("imv %F"))
    }

    @Test
    fun `keeps real args while dropping field codes`() {
        assertEquals("app --flag", DesktopEntryParser.stripExecCodes("app %F --flag"))
        assertEquals("foo bar", DesktopEntryParser.stripExecCodes("foo %i %c %k bar"))
    }

    @Test
    fun `double percent becomes a literal percent and spaces survive`() {
        assertEquals("echo 100%done", DesktopEntryParser.stripExecCodes("echo 100%%done"))
        assertEquals("a b c", DesktopEntryParser.stripExecCodes("a b c"))
    }

    @Test
    fun `mid-string field code is removed and whitespace collapses`() {
        assertEquals("env GDK_BACKEND=wayland firefox", DesktopEntryParser.stripExecCodes("env GDK_BACKEND=wayland firefox %u"))
    }

    // --- parse --------------------------------------------------------------

    private fun entry(vararg lines: String) = "[Desktop Entry]\n" + lines.joinToString("\n")

    @Test
    fun `parses a normal application entry`() {
        val e = DesktopEntryParser.parse(
            entry("Type=Application", "Name=GIMP", "Exec=gimp %U", "Icon=gimp", "Categories=Graphics;2DGraphics;"),
        )!!
        assertEquals("GIMP", e.name)
        assertEquals("gimp", e.exec)
        assertEquals("gimp", e.icon)
        assertEquals(listOf("Graphics", "2DGraphics"), e.categories)
    }

    @Test
    fun `skips NoDisplay, Hidden, Terminal, non-application, and blanks`() {
        assertNull(DesktopEntryParser.parse(entry("Type=Application", "Name=X", "Exec=x", "NoDisplay=true")))
        assertNull(DesktopEntryParser.parse(entry("Type=Application", "Name=X", "Exec=x", "Hidden=true")))
        assertNull(DesktopEntryParser.parse(entry("Type=Application", "Name=X", "Exec=x", "Terminal=true")))
        assertNull(DesktopEntryParser.parse(entry("Type=Link", "Name=X", "Exec=x", "URL=http://e")))
        assertNull(DesktopEntryParser.parse(entry("Type=Application", "Exec=x"))) // no Name
        assertNull(DesktopEntryParser.parse(entry("Type=Application", "Name=X")))  // no Exec
    }

    @Test
    fun `ignores locale-suffixed keys and a second group`() {
        val e = DesktopEntryParser.parse(
            entry(
                "Name=Files", "Name[de]=Dateien", "Exec=nautilus",
                "[Desktop Action new]", "Name=New Window", "Exec=nautilus --new-window",
            ),
        )!!
        assertEquals("Files", e.name)
        assertEquals("nautilus", e.exec) // not the action group's exec
    }

    // --- resolveIconPath ----------------------------------------------------

    private fun fakeRootfs(): File = Files.createTempDirectory("appscan").toFile()

    private fun touch(root: File, rel: String): File =
        File(root, rel).apply { parentFile?.mkdirs(); writeText("x") }

    @Test
    fun `absolute icon path resolves when present`() {
        val root = fakeRootfs()
        val icon = touch(root, "opt/app/logo.png")
        assertEquals(icon, DesktopEntryParser.resolveIconPath(root, "/opt/app/logo.png"))
        assertNull(DesktopEntryParser.resolveIconPath(root, "/opt/app/missing.png"))
    }

    @Test
    fun `theme png preferred and larger size wins`() {
        val root = fakeRootfs()
        touch(root, "usr/share/icons/hicolor/48x48/apps/gimp.png")
        val big = touch(root, "usr/share/icons/hicolor/128x128/apps/gimp.png")
        assertEquals(big, DesktopEntryParser.resolveIconPath(root, "gimp"))
    }

    @Test
    fun `falls back to pixmaps then null`() {
        val root = fakeRootfs()
        val pm = touch(root, "usr/share/pixmaps/thing.png")
        assertEquals(pm, DesktopEntryParser.resolveIconPath(root, "thing"))
        assertNull(DesktopEntryParser.resolveIconPath(root, "nope"))
    }

    @Test
    fun `svg is resolved when no png exists`() {
        val root = fakeRootfs()
        val svg = touch(root, "usr/share/icons/hicolor/scalable/apps/firefox.svg")
        val out = DesktopEntryParser.resolveIconPath(root, "firefox")
        assertEquals(svg, out)
        assertTrue(out!!.extension == "svg")
    }
}
