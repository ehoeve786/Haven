package sh.haven.app.desktop

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.spice.SpiceSession
import sh.haven.core.ui.CursorOverlay

/**
 * A single tab on the Desktop screen, representing an active SPICE
 * or native Wayland session. Mirrors TerminalTab for terminal sessions.
 */
sealed class DesktopTab {
    abstract val id: String
    abstract val label: String
    abstract val colorTag: Int
    abstract val connected: StateFlow<Boolean>
    abstract val frame: StateFlow<Bitmap?>
    abstract val error: StateFlow<String?>

    /**
     * The protocol-neutral session driver, populated for tabs that
     * carry a remote desktop connection. Null for tabs without one
     * (e.g. native Wayland, which renders into a TextureView and
     * has no agent-driveable input plane). #128.
     */
    abstract val remoteDesktop: RemoteDesktopSession?

    /** Protocol indicator for the tab bar icon/label. */
    val protocol: String get() = when (this) {
        is Spice -> "SPICE"
        is Wayland -> "Wayland"
    }

    data class Spice(
        override val id: String,
        override val label: String,
        override val colorTag: Int = 0,
        val session: SpiceSession,
        val _connected: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val _frame: MutableStateFlow<Bitmap?> = MutableStateFlow(null),
        val _error: MutableStateFlow<String?> = MutableStateFlow(null),
        /** Latest cursor shape from the SPICE cursor channel. */
        val _cursor: MutableStateFlow<CursorOverlay?> = MutableStateFlow(null),
        /** Local pointer position we last sent — seeds the touchpad-mode virtual cursor. */
        val _pointerPos: MutableStateFlow<Pair<Int, Int>> = MutableStateFlow(0 to 0),
        /** Lease tying this tab to its SSH tunnel; closing it releases the tunnel. */
        val tunnelLease: SshSessionManager.TunnelLease? = null,
        val profileId: String? = null,
    ) : DesktopTab() {
        override val connected: StateFlow<Boolean> get() = _connected
        override val frame: StateFlow<Bitmap?> get() = _frame
        override val error: StateFlow<String?> get() = _error
        override val remoteDesktop: RemoteDesktopSession = SpiceDesktopSession(session)
        val cursor: StateFlow<CursorOverlay?> get() = _cursor
        val pointerPos: StateFlow<Pair<Int, Int>> get() = _pointerPos
    }

    data class Wayland(
        override val id: String = "wayland-native",
        override val label: String = "Wayland",
        override val colorTag: Int = 0,
        val _connected: MutableStateFlow<Boolean> = MutableStateFlow(true),
        val _error: MutableStateFlow<String?> = MutableStateFlow(null),
    ) : DesktopTab() {
        override val connected: StateFlow<Boolean> get() = _connected
        override val frame: StateFlow<Bitmap?> = MutableStateFlow(null) // N/A — uses TextureView
        override val error: StateFlow<String?> get() = _error
        override val remoteDesktop: RemoteDesktopSession? = null
    }
}
