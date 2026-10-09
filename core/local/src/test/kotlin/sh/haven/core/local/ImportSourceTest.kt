package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportSourceTest {

    @Test
    fun `https and http are downloads`() {
        assertEquals(
            ImportSource.Remote("https://example.com/rootfs.tar.gz"),
            ImportSource.of("https://example.com/rootfs.tar.gz"),
        )
        assertEquals(
            ImportSource.Remote("http://nas.lan/rootfs.tar.gz"),
            ImportSource.of("http://nas.lan/rootfs.tar.gz"),
        )
    }

    @Test
    fun `a bare absolute path is a local file`() {
        assertEquals(
            ImportSource.LocalFile("/sdcard/Download/rootfs.tar.gz"),
            ImportSource.of("/sdcard/Download/rootfs.tar.gz"),
        )
    }

    @Test
    fun `a file URL is a local file with the scheme stripped`() {
        // The reported case: this used to reach File("file:///...") and fail
        // with "Local rootfs not found: file:///sdcard/..." (#560).
        assertEquals(
            ImportSource.LocalFile("/sdcard/Download/rootfs.tar.gz"),
            ImportSource.of("file:///sdcard/Download/rootfs.tar.gz"),
        )
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(
            ImportSource.LocalFile("/sdcard/rootfs.tar.gz"),
            ImportSource.of("  file:///sdcard/rootfs.tar.gz\n"),
        )
    }

    @Test
    fun `a SAF content URI says so instead of failing as a missing file`() {
        val result = ImportSource.of("content://com.android.providers.downloads/document/42")
        assertTrue(result.toString(), result is ImportSource.Unsupported)
        val reason = (result as ImportSource.Unsupported).reason
        assertTrue(reason, reason.contains("content://"))
        assertTrue(reason, reason.contains("/sdcard/Download"))
    }

    @Test
    fun `a file URL with a relative path is rejected with the fix in the message`() {
        val result = ImportSource.of("file://sdcard/rootfs.tar.gz")
        assertTrue(result.toString(), result is ImportSource.Unsupported)
        assertTrue((result as ImportSource.Unsupported).reason.contains("file:///sdcard"))
    }

    @Test
    fun `empty input is rejected`() {
        assertTrue(ImportSource.of("   ") is ImportSource.Unsupported)
    }
}
