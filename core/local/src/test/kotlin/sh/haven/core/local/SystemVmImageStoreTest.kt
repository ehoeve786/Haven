package sh.haven.core.local

import android.content.Context
import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.haven.core.local.proot.DistroCatalog
import java.io.File

/**
 * The guest architecture makes a round trip the compiler cannot check: chosen at
 * import, written to a sidecar, read back by [SystemVmManager.startImage], and
 * turned into a qemu target. `arch` is a DEFAULTED parameter at both hand-offs,
 * so dropping either one still compiles and silently boots every image on the
 * x86_64 target — where an arm64 image doesn't error, it just sits on a machine
 * with no bootable device. These tests pin the whole chain rather than the ends.
 */
class SystemVmImageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cache: File
    private lateinit var source: File

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        cache = tmp.newFolder("cache")
        source = tmp.newFile("src.img").apply { writeText("not a real disk, but real bytes") }
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    private fun context(): Context = mockk<Context>(relaxed = true).also { every { it.cacheDir } returns cache }

    /**
     * A ProotManager whose shell answers the three questions the manager asks:
     * are the tools there, convert this image, and where is the firmware. The
     * convert branch has to materialise the HOST file, since that is what
     * importImage checks for — the path in the command is the in-proot one.
     */
    private fun proot(): ProotManager = mockk<ProotManager>(relaxed = true).also { p ->
        every { p.activeDistro } returns DistroCatalog.lookup(DistroCatalog.DEFAULT_ID)!!
        every { p.activeDistroId } returns DistroCatalog.DEFAULT_ID
        coEvery { p.runCommandInProot(any(), any()) } answers {
            val cmd = firstArg<String>()
            when {
                cmd.contains("command -v") -> "QEMU_OK" to 0
                cmd.contains("qemu-img convert") -> {
                    val out = Regex("/tmp/system-vm/([^']+\\.qcow2)").find(cmd)!!.groupValues[1]
                    File(cache, "system-vm").apply { mkdirs() }
                    File(cache, "system-vm/$out").writeText("converted")
                    "" to 0
                }
                cmd.startsWith("for f in") -> "HVNFW:$PROBED_FIRMWARE" to 0
                else -> "" to 0
            }
        }
    }

    private fun archSidecar(id: String) = File(cache, "system-vm/$id.arch")

    @Test
    fun `import records the chosen arch and lists it back`() {
        val mgr = SystemVmManager(context(), proot())
        val arm = runBlocking { mgr.importImage("armimg", "Arm image", source.absolutePath, arch = VmArch.AARCH64) }
        assertEquals(VmArch.AARCH64, arm.arch)
        assertEquals("aarch64", archSidecar("armimg").readText().trim())

        // The default is x86_64 — the pre-existing behaviour for every caller
        // that doesn't care (and for the UI before the selector existed).
        val x86 = runBlocking { mgr.importImage("x86img", "x86 image", source.absolutePath) }
        assertEquals(VmArch.X86_64, x86.arch)

        val listed = mgr.listImages().associate { it.id to it.arch }
        assertEquals(VmArch.AARCH64, listed["armimg"])
        assertEquals(VmArch.X86_64, listed["x86img"])
    }

    @Test
    fun `an image with no sidecar lists as x86_64, not as unknown`() {
        val mgr = SystemVmManager(context(), proot())
        runBlocking { mgr.importImage("legacy", "Legacy image", source.absolutePath, arch = VmArch.AARCH64) }
        // Simulate an image imported before the sidecar existed: the qcow2 is
        // there, the arch record is not.
        assertTrue(archSidecar("legacy").delete())
        assertEquals(VmArch.X86_64, mgr.listImages().single { it.id == "legacy" }.arch)
    }

    @Test
    fun `deleting an image takes its arch sidecar with it`() {
        val mgr = SystemVmManager(context(), proot())
        runBlocking { mgr.importImage("gone", "Doomed", source.absolutePath, arch = VmArch.AARCH64) }
        assertTrue(archSidecar("gone").exists())
        runBlocking { mgr.deleteImage("gone") }
        assertFalse("a stale .arch would outlive its image and be reused by the next same-id import", archSidecar("gone").exists())
    }

    @Test
    fun `startImage builds the target the sidecar recorded`() {
        val p = proot()
        val launched = slot<String>()
        // A process that is already dead: start() sees it, skips the 20s
        // port-bind wait, and throws — after the command has been built, which
        // is the artifact under test.
        every { p.startCommandInProot(capture(launched)) } returns mockk<Process>(relaxed = true) {
            every { isAlive } returns false
        }
        val mgr = SystemVmManager(context(), p)

        runBlocking { mgr.importImage("armimg", "Arm image", source.absolutePath, arch = VmArch.AARCH64) }
        val armFailure = runCatching { runBlocking { mgr.startImage("armimg") } }.exceptionOrNull()
        assertTrue("expected the dead-process failure, got $armFailure", armFailure is SystemVmException)
        assertTrue("must boot the aarch64 target: ${launched.captured}", launched.captured.startsWith("exec qemu-system-aarch64"))
        assertTrue("…on the virt machine", launched.captured.contains("-M virt"))
        assertTrue("…with the firmware the probe found", launched.captured.contains("-bios $PROBED_FIRMWARE"))

        runBlocking { mgr.importImage("x86img", "x86 image", source.absolutePath) }
        runCatching { runBlocking { mgr.startImage("x86img") } }
        assertTrue("must boot the x86_64 target: ${launched.captured}", launched.captured.startsWith("exec qemu-system-x86_64"))
        assertTrue("…on the pc machine", launched.captured.contains("-M pc"))
        assertFalse("…and never carry aarch64 firmware", launched.captured.contains("-bios"))
    }

    @Test
    fun `starting an image that isn't there says so instead of launching qemu`() {
        val p = proot()
        val mgr = SystemVmManager(context(), p)
        val error = runCatching { runBlocking { mgr.startImage("nope") } }.exceptionOrNull()
        assertTrue("got $error", error is SystemVmException && error.message.orEmpty().contains("no such system-VM image"))
    }

    private companion object {
        /** Not a real path — it only has to come back out of the probe and into `-bios`. */
        const val PROBED_FIRMWARE = "/usr/share/AAVMF/AAVMF_CODE.fd"
    }
}
