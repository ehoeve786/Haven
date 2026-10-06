package sh.haven.app.desktop

import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Test
import sh.haven.core.rdp.RdpSession
import sh.haven.rdp.MouseButton

/**
 * Unit tests for the [RemoteDesktopSession] adapters introduced for
 * #128. These pin the verb-routing contract between the abstraction
 * and the underlying drivers — anything regressing the mapping
 * (button numbers, scroll-as-click-4/5, pause/resume direction)
 * surfaces here long before it reaches a connected VNC/RDP server.
 */
class RemoteDesktopSessionTest {

    // --- RdpDesktopSession -------------------------------------------------

    @Test
    fun `rdp adapter forwards mouse move`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendMouseMove(10, 20)
        verify(exactly = 1) { session.sendMouseMove(10, 20) }
    }

    @Test
    fun `rdp adapter maps x11 button 1 to LEFT`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendMouseButton(button = 1, pressed = true)
        verify(exactly = 1) { session.sendMouseButton(MouseButton.LEFT, true) }
    }

    @Test
    fun `rdp adapter sendMouseClick translates the button number`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendMouseClick(5, 6, button = 1)
        verify(exactly = 1) { session.sendMouseClick(5, 6, MouseButton.LEFT) }
    }

    @Test
    fun `rdp adapter scroll up sends positive vertical wheel`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendMouseWheel(deltaY = 1)
        verify(exactly = 1) { session.sendMouseWheel(true, 120) }
    }

    @Test
    fun `rdp adapter scroll down sends negative vertical wheel`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendMouseWheel(deltaY = -1)
        verify(exactly = 1) { session.sendMouseWheel(true, -120) }
    }

    @Test
    fun `rdp adapter zero scroll is a no-op`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendMouseWheel(deltaY = 0)
        verify(exactly = 0) { session.sendMouseWheel(any(), any()) }
    }

    @Test
    fun `rdp adapter forwards clipboard text`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendClipboardText("hi")
        verify(exactly = 1) { session.sendClipboardText("hi") }
    }

    @Test
    fun `rdp adapter pause and resume are no-ops`() {
        // RDP has no client-side throttle — the abstraction's pause /
        // resume must not blow up, just no-op.
        val session = mockk<RdpSession>(relaxed = true)
        val s = RdpDesktopSession(session)
        s.pause()
        s.resume()
        // Verify nothing on the underlying session was called.
        verify(exactly = 0) { session.sendMouseMove(any(), any()) }
        verify(exactly = 0) { session.close() }
    }

    @Test
    fun `rdp adapter close calls session close`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).close()
        verify(exactly = 1) { session.close() }
    }
}
