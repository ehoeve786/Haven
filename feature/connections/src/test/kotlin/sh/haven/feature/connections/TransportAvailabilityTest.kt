package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #510 — the terminal build drops some native payloads, so offering their
 * transports would create a profile that can only fail at connect.
 */
class TransportAvailabilityTest {

    private val all = listOf(
        "SSH" to "SSH",
        "LOCAL" to "Local Shell (PRoot)",
        "GUEST" to "Linux Guest (UML)",
        "SMB" to "SMB (File Share)",
        "RCLONE" to "Cloud Storage (rclone)",
    )

    private fun values(
        rclone: Boolean = true,
        uml: Boolean = true,
    ) = TransportAvailability.offered(all, rclone, uml).map { it.first }

    @Test
    fun `a full build offers everything`() {
        assertEquals(all.map { it.first }, values())
    }

    /** Order is the caller's; filtering must not reshuffle the menu. */
    @Test
    fun `order is preserved`() {
        assertEquals(
            listOf("SSH", "LOCAL", "GUEST", "SMB"),
            values(rclone = false),
        )
    }

    /**
     * The guest is gated on a missing file (libvmlinux.so) — the terminal
     * flavour drops the kernel and F-Droid builds skip the fetch.
     */
    @Test
    fun `a build without the UML payload drops the guest transport`() {
        val offered = values(uml = false)

        assertFalse("GUEST", "GUEST" in offered)
        assertTrue("LOCAL should survive", "LOCAL" in offered)
        assertTrue("SSH should survive", "SSH" in offered)
    }

    /**
     * rclone is gated on a probe rather than a missing file: the terminal
     * build ships libgojni.so, just built without the rclone package, so
     * looking for the library would wrongly report it present.
     */
    @Test
    fun `a build whose Go library omits rclone drops the rclone transport`() {
        val offered = values(rclone = false)

        assertFalse("RCLONE", "RCLONE" in offered)
        assertTrue("SMB should survive", "SMB" in offered)
    }
}
