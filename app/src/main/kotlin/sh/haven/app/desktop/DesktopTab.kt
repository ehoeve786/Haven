package sh.haven.app.desktop

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single tab on the Desktop screen, representing an active native
 * Wayland session. Mirrors TerminalTab for terminal sessions.
 */
sealed class DesktopTab {
    abstract val id: String
    abstract val label: String
    abstract val colorTag: Int
    abstract val connected: StateFlow<Boolean>
    abstract val frame: StateFlow<Bitmap?>
    abstract val error: StateFlow<String?>

    /** Protocol indicator for the tab bar icon/label. */
    val protocol: String get() = when (this) {
        is Wayland -> "Wayland"
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
    }
}
