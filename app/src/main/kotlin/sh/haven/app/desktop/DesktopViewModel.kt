package sh.haven.app.desktop

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.desktop.CursorSnapshot
import sh.haven.core.data.desktop.DesktopFrameHandle
import sh.haven.core.data.desktop.DesktopInputHandle
import sh.haven.core.data.desktop.DesktopStatus
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.knock.KnockSequence
import sh.haven.core.knock.PortKnocker
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.ProotManager
import sh.haven.core.local.proot.Distro
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.rdp.RdpSession
import sh.haven.core.spice.SpiceSession
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshConnection
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ui.CursorOverlay
import sh.haven.core.tunnel.TunnelResolver
import sh.haven.core.tunnel.TunneledConnection
import sh.haven.core.tunnel.TunneledSocket
import sh.haven.feature.rdp.RdpViewModel
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import sh.haven.core.redact.LogRedact

private const val TAG = "DesktopViewModel"

private sealed class DesktopStartOutcome {
    object Ready : DesktopStartOutcome()
    object Timeout : DesktopStartOutcome()
    data class Error(val message: String) : DesktopStartOutcome()
}

@HiltViewModel
class DesktopViewModel @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val connectionLogRepository: ConnectionLogRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val connectionRepository: ConnectionRepository,
    private val tunnelResolver: TunnelResolver,
    private val portKnocker: PortKnocker,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val localSessionManager: LocalSessionManager,
    private val desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
    private val usbDriveVmManager: sh.haven.app.usb.UsbDriveVmManager,
    private val umlRecoveryManager: sh.haven.app.usb.UmlRecoveryManager,
) : ViewModel() {

    // --- Distro / DE management (issue #162 Phase 3c) ---
    //
    // The Connections topbar used to host a dialog with the distro picker,
    // rootfs setup state, and DE install/start/stop rows. That UI moved
    // to a "Manage" view inside the Desktop tab in 3c (`DesktopManagerScreen`).
    // These StateFlows + methods are the data layer the new screen reads;
    // every accessor is a thin pass-through to ProotManager / DesktopManager
    // so the source of truth stays in one place.

    private val prootManager: ProotManager get() = localSessionManager.prootManager
    private val desktopManager: DesktopManager get() = localSessionManager.desktopManager

    val activeDistroId: StateFlow<String> get() = prootManager.activeDistroIdFlow

    val rootfsSetupState: StateFlow<ProotManager.SetupState> get() = prootManager.state

    val desktopSetupState: StateFlow<ProotManager.DesktopSetupState>
        get() = prootManager.desktopState

    val desktopStates: StateFlow<Map<ProotManager.DesktopEnvironment, DesktopManager.DesktopInstance>>
        get() = desktopManager.desktops

    val installedDistros: List<Distro> get() = prootManager.installedDistros
    val availableDistros: List<Distro> get() = prootManager.availableDistros
    val availableForeignDistros: List<Pair<Distro, sh.haven.core.local.proot.Arch>>
        get() = prootManager.availableForeignDistros
    val installedDesktops: Set<ProotManager.DesktopEnvironment>
        get() = prootManager.installedDesktops

    val isRootfsReady: Boolean get() = prootManager.isReady

    fun switchActiveDistro(distroId: String) {
        prootManager.setActiveDistroId(distroId)
    }

    /** Selected package-mirror region (#263). Pass-through to ProotManager. */
    val mirrorRegion: StateFlow<sh.haven.core.local.proot.MirrorRegion>
        get() = prootManager.mirrorRegionFlow

    fun setMirrorRegion(region: sh.haven.core.local.proot.MirrorRegion) {
        prootManager.setMirrorRegion(region)
    }

    /** #300: remap privileged (<1024) guest binds up by +2000. Pass-through. */
    val remapLowPorts: StateFlow<Boolean> get() = prootManager.remapLowPortsFlow

    fun setRemapLowPorts(enabled: Boolean) {
        prootManager.setRemapLowPorts(enabled)
    }

    /** #301: share the device's /storage with the local guest. Pass-through. */
    val shareStorageWithGuest: StateFlow<Boolean> get() = prootManager.shareStorageWithGuestFlow

    fun setShareStorageWithGuest(enabled: Boolean) {
        prootManager.setShareStorageWithGuest(enabled)
    }

    /** #304: bind Android's read-only system partitions into the guest. Pass-through. */
    val bindAndroidSystem: StateFlow<Boolean> get() = prootManager.bindAndroidSystemFlow

    fun setBindAndroidSystem(enabled: Boolean) {
        prootManager.setBindAndroidSystem(enabled)
    }

    /** #446: which resolvers the local guest's /etc/resolv.conf gets. Pass-through. */
    val dnsMode: StateFlow<sh.haven.core.local.ProotDnsMode> get() = prootManager.dnsModeFlow

    fun setDnsMode(mode: sh.haven.core.local.ProotDnsMode) {
        prootManager.setDnsMode(mode)
    }

    /** #446: custom nameservers, used when [dnsMode] is CUSTOM. Pass-through. */
    val dnsServers: StateFlow<String> get() = prootManager.dnsServersFlow

    fun setDnsServers(servers: String) {
        prootManager.setDnsServers(servers)
    }

    /**
     * Local-shell open requests keyed by the resolved profile id. Collected
     * by HavenNavHost (which is always composed, unlike TerminalScreen) so
     * the request is never dropped while the user is on the Desktop tab.
     * HavenNavHost sets a pending-profile state and animates to the Terminal
     * page; TerminalScreen consumes it once composed and calls
     * addLocalTabForProfile. SharedFlow with replay=0 — a screen rotation
     * shouldn't re-open a shell. See GlassHaven/Haven#168.
     */
    /** A request to open a local shell tab, optionally joining a running desktop (#285). */
    data class LocalShellRequest(val profileId: String, val desktopDeId: String? = null)

    private val _openLocalShellRequests = MutableSharedFlow<LocalShellRequest>(extraBufferCapacity = 4)
    val openLocalShellRequests: SharedFlow<LocalShellRequest> = _openLocalShellRequests.asSharedFlow()

    /**
     * Open a terminal tab into the given distro. Restores the entry-point
     * removed in v5.38.0 when the Connections topbar lost its "Alpine
     * console" icon — see GlassHaven/Haven#168. Reuses the single canonical
     * "Local Shell" profile (mirrors McpTools.openLocalShell so the agent
     * and the user reach the same place) and switches the active distro
     * to [distroId] first, so the proot session boots into the right rootfs.
     *
     * Emits the resolved profile id on [openLocalShellRequests] rather than
     * the AgentUiCommandBus: the bus is replay=0 and TerminalViewModel only
     * collects it while TerminalScreen is composed, so a request fired from
     * the Desktop tab (Terminal not in the pager's composition window) was
     * silently dropped — the tab never opened (#168 regression). HavenNavHost
     * is always composed, so routing through it is reliable.
     */
    fun openShellForDistro(distroId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (prootManager.activeDistroId != distroId) {
                prootManager.setActiveDistroId(distroId)
            }
            val all = connectionRepository.getAll()
            val profile = all.firstOrNull {
                it.connectionType == "LOCAL" && !it.useAndroidShell
            } ?: run {
                val seeded = ConnectionProfile(
                    label = "Local Shell",
                    host = "localhost",
                    username = "",
                    port = 0,
                    connectionType = "LOCAL",
                    useAndroidShell = false,
                )
                connectionRepository.save(seeded)
                connectionRepository.getAll().firstOrNull {
                    it.connectionType == "LOCAL" && it.label == "Local Shell" && !it.useAndroidShell
                } ?: seeded
            }
            _openLocalShellRequests.emit(LocalShellRequest(profile.id))
        }
    }

    /**
     * Open a terminal tab whose environment joins the RUNNING desktop [de]
     * (#285) — DISPLAY / WAYLAND_DISPLAY / XDG_RUNTIME_DIR — so the user can
     * drive the desktop's apps from a shell. Reuses the canonical "Local Shell"
     * profile; unlike [openShellForDistro] it does NOT switch the active distro
     * (desktops run on the active distro, and their sockets live in the shared
     * cacheDir). The deId rides the request so the terminal resolves and merges
     * the desktop env at session creation.
     */
    fun openTerminalInDesktop(de: ProotManager.DesktopEnvironment) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = connectionRepository.getAll()
            val profile = all.firstOrNull {
                it.connectionType == "LOCAL" && !it.useAndroidShell
            } ?: run {
                val seeded = ConnectionProfile(
                    label = "Local Shell",
                    host = "localhost",
                    username = "",
                    port = 0,
                    connectionType = "LOCAL",
                    useAndroidShell = false,
                )
                connectionRepository.save(seeded)
                connectionRepository.getAll().firstOrNull {
                    it.connectionType == "LOCAL" && it.label == "Local Shell" && !it.useAndroidShell
                } ?: seeded
            }
            _openLocalShellRequests.emit(LocalShellRequest(profile.id, de.spec.id))
        }
    }

    /**
     * Make [distro] the active distro and install its rootfs.
     * Mirrors the revert-on-failure semantics from ConnectionsViewModel.addDistro:1472.
     */
    fun addDistro(distro: Distro) {
        val previousActive = prootManager.activeDistroId
        prootManager.setActiveDistroId(distro.id)
        viewModelScope.launch {
            try {
                prootManager.installRootfs()
                if (prootManager.state.value is ProotManager.SetupState.Error) {
                    Log.w(TAG, "addDistro: ${distro.id} install failed, reverting to $previousActive")
                    prootManager.setActiveDistroId(previousActive)
                }
            } catch (e: Exception) {
                Log.e(TAG, "addDistro: ${distro.id} install threw", e)
                prootManager.setActiveDistroId(previousActive)
            }
        }
    }

    /**
     * Install a catalog distro for a NON-host arch — runs under qemu-user
     * emulation (#325). Routed through the import path, which downloads the
     * foreign tarball, auto-detects its arch, and writes the marker that
     * arms qemu at launch; registers under the derived "<id>-<arch>" id.
     */
    fun addForeignDistro(distro: Distro, arch: sh.haven.core.local.proot.Arch) {
        val source = distro.rootfsSources[arch] ?: return
        viewModelScope.launch {
            try {
                prootManager.importRootfs(
                    id = prootManager.foreignDistroId(distro, arch),
                    label = "${distro.label} (${arch.slug})",
                    family = distro.family,
                    source = source.url,
                    format = formatFor(source.url),
                    expectedSha256 = source.sha256,
                )
            } catch (e: Exception) {
                Log.e(TAG, "addForeignDistro: ${distro.id}/${arch.slug} threw", e)
            }
        }
    }

    /**
     * Import a custom rootfs tarball as a new distro (#284). [source] is an
     * http(s) URL or an on-device file path; progress shows via the same
     * rootfsSetupState the install path uses.
     */
    fun importRootfs(
        id: String,
        label: String,
        family: sh.haven.core.local.proot.PackageFamily,
        source: String,
    ) {
        viewModelScope.launch {
            try {
                prootManager.importRootfs(id, label, family, source, formatFor(source))
            } catch (e: Exception) {
                Log.e(TAG, "importRootfs: $id threw", e)
            }
        }
    }

    private fun formatFor(source: String): sh.haven.core.local.proot.RootfsFormat = when {
        source.endsWith(".tar.xz") || source.endsWith(".txz") -> sh.haven.core.local.proot.RootfsFormat.TAR_XZ
        source.endsWith(".tar.zst") || source.endsWith(".tar.zstd") -> sh.haven.core.local.proot.RootfsFormat.TAR_ZSTD
        else -> sh.haven.core.local.proot.RootfsFormat.TAR_GZ
    }

    // --- Per-distro custom bind mounts (#301) ---

    /** Bumped when binds change, so the Manage screen recomposes the count. */
    val customBindsRev: StateFlow<Int> get() = prootManager.customBindsRev

    fun customBinds(distroId: String): List<sh.haven.core.local.proot.CustomBind> =
        prootManager.customBinds(distroId)

    fun setCustomBinds(distroId: String, binds: List<sh.haven.core.local.proot.CustomBind>) {
        prootManager.setCustomBinds(distroId, binds)
    }

    /**
     * Delete a distro's rootfs. Stops any DEs running on it first so
     * the session-viewer tabs don't outlive their backing rootfs.
     */
    fun deleteDistro(distroId: String) {
        if (prootManager.activeDistroId == distroId) {
            desktopManager.stopAll()
        }
        prootManager.deleteDistro(distroId)
    }

    /**
     * Install a desktop environment on the active distro. Optional addons
     * land in a follow-up `installAddons` call when the primary install
     * succeeds — same shape ConnectionsViewModel.setupDesktop had before
     * the 3c move.
     */
    fun setupDesktop(
        de: ProotManager.DesktopEnvironment,
        addons: Set<ProotManager.DesktopAddon> = emptySet(),
    ) {
        viewModelScope.launch {
            // Native desktops have no VNC server; the password is unused.
            prootManager.setupDesktop("", de)
            if (addons.isNotEmpty() &&
                prootManager.desktopState.value is ProotManager.DesktopSetupState.Complete
            ) {
                prootManager.installAddons(addons)
            }
        }
    }

    fun uninstallDesktop(de: ProotManager.DesktopEnvironment) {
        viewModelScope.launch {
            desktopManager.stopDesktop(de)
            prootManager.uninstallDesktop(de)
            prootManager.resetDesktopState()
        }
    }

    /** Start a native DE and add it as a Wayland tab in the Desktop session viewer. */
    fun startDesktop(de: ProotManager.DesktopEnvironment) {
        viewModelScope.launch {
            val shellCmd = preferencesRepository.waylandShellCommand.first()
            Log.d(TAG, "startDesktop: ${LogRedact.of(de.label)} shell=$shellCmd")
            withContext(Dispatchers.IO) {
                desktopManager.startDesktop(de, shellCmd)
            }
            if (de.isNative) {
                kotlinx.coroutines.delay(2000)
                addWaylandTab()
            }
        }
    }

    fun stopDesktop(de: ProotManager.DesktopEnvironment) {
        viewModelScope.launch(Dispatchers.IO) {
            desktopManager.stopDesktop(de)
        }
    }

    fun resetDesktopSetupState() {
        prootManager.resetDesktopState()
    }

    /**
     * Retry the failed install phase (issue #162 Phase 3d). Dispatches
     * via `ProotManager.retry()` which inspects the current Error
     * state and re-runs just the failing layer (or wipes-and-retries
     * for the destructive Download/Extract phases).
     */
    fun retryRootfsInstall() {
        viewModelScope.launch { prootManager.retry() }
    }

    init {
        // Workspace launcher posts here when a DESKTOP / WAYLAND item
        // fires. The matching pager switch happens in HavenNavHost.
        // DesktopViewModel is hoisted to nav scope so emissions always
        // land regardless of which tab the user is currently viewing.
        viewModelScope.launch {
            agentUiCommandBus.commands.collect { command ->
                when (command) {
                    is sh.haven.core.data.agent.AgentUiCommand.OpenRemoteDesktop ->
                        openRemoteDesktopForProfile(command.profileId)
                    is sh.haven.core.data.agent.AgentUiCommand.OpenWaylandDesktop ->
                        addWaylandTab()
                    is sh.haven.core.data.agent.AgentUiCommand.OpenUsbDrive ->
                        openUsbDrive(command.deviceName)
                    else -> { /* handled by other collectors */ }
                }
            }
        }
    }

    /**
     * Resolve [profileId] to an RDP or SPICE profile and dispatch to the
     * matching `add*Session`. Used by the workspace launcher; for
     * tunneled profiles, picks the first connected SSH session for the
     * tunnel profile and lets `addRdpSession` / `addSpiceSession` throw
     * the existing "SSH session not found" error if none is up.
     */
    private fun openRemoteDesktopForProfile(profileId: String) {
        viewModelScope.launch {
            val profile = connectionRepository.getById(profileId)
            if (profile == null) {
                Log.w(TAG, "OpenRemoteDesktop: profile $profileId not found")
                return@launch
            }
            when {
                profile.isRdp -> {
                    val sshSessionId =
                        if (profile.rdpSshForward && profile.rdpSshProfileId != null) {
                            sshSessionManager.getSessionsForProfile(profile.rdpSshProfileId!!)
                                .firstOrNull { it.status.name == "CONNECTED" }
                                ?.sessionId
                        } else null
                    addRdpSession(
                        host = profile.host,
                        port = profile.rdpPort,
                        username = profile.rdpUsername.orEmpty(),
                        password = profile.rdpPassword.orEmpty(),
                        domain = profile.rdpDomain.orEmpty(),
                        sshForward = profile.rdpSshForward,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                        useNla = profile.rdpUseNla,
                        colorDepth = profile.rdpColorDepth,
                    )
                }
                profile.isSpice -> {
                    val sshSessionId =
                        if (profile.spiceSshForward && profile.spiceSshProfileId != null) {
                            sshSessionManager.getSessionsForProfile(profile.spiceSshProfileId!!)
                                .firstOrNull { it.status.name == "CONNECTED" }
                                ?.sessionId
                        } else null
                    addSpiceSession(
                        host = profile.host,
                        port = profile.spicePort ?: 5900,
                        password = profile.spicePassword,
                        sshForward = profile.spiceSshForward,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                    )
                }
                else -> Log.w(
                    TAG,
                    "OpenRemoteDesktop: ${profile.label} is ${profile.connectionType}, not RDP/SPICE",
                )
            }
        }
    }

    /**
     * Transient user-facing messages from background tasks (desktop start
     * failures, etc.) that need to surface as a Toast/Snackbar. Collected
     * by DesktopScreen. SharedFlow with replay=0 so a screen rotation
     * doesn't re-fire the same message.
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    // --- "Open USB drive" in a VM (#287) — keyed by busid, up to
    // QemuManager.MAX_CONCURRENT_DRIVES concurrently OPENING/READY.
    val usbDriveSessions: StateFlow<Map<String, sh.haven.app.usb.UsbDriveVmManager.Status>> = usbDriveVmManager.sessions

    // Whether the persistent USB-helper appliance is provisioned (drives the
    // "Delete USB helper Linux" menu item; the appliance is kept across opens).
    private val _applianceProvisioned = MutableStateFlow(usbDriveVmManager.applianceProvisioned)
    val applianceProvisioned: StateFlow<Boolean> = _applianceProvisioned.asStateFlow()

    init {
        // Announce each drive's slow VM boot outcome on the snackbar (it then
        // auto-opens in Files at its mount) — tracked per busid so opening a
        // second drive doesn't re-fire the first one's message.
        viewModelScope.launch {
            var last = usbDriveVmManager.sessions.value.mapValues { it.value.phase }
            usbDriveVmManager.sessions.collect { byBusid ->
                _applianceProvisioned.value = usbDriveVmManager.applianceProvisioned
                byBusid.forEach { (busid, s) ->
                    if (s.phase != last[busid]) {
                        when (s.phase) {
                            sh.haven.app.usb.UsbDriveVmManager.Phase.READY ->
                                _userMessages.emit("USB drive mounted — opening it in Files.")
                            sh.haven.app.usb.UsbDriveVmManager.Phase.ERROR ->
                                _userMessages.emit("Couldn't open USB drive: ${s.error ?: "VM failed to start"}")
                            else -> {}
                        }
                    }
                }
                last = byBusid.mapValues { it.value.phase }
            }
        }
    }

    /** Delete the persistent USB-helper appliance; the next open re-provisions it. */
    fun deleteUsbAppliance() {
        viewModelScope.launch {
            usbDriveVmManager.deleteAppliance()
            _applianceProvisioned.value = usbDriveVmManager.applianceProvisioned
            _userMessages.emit("USB helper Linux deleted — the next USB-drive open will rebuild it once.")
        }
    }

    /**
     * Non-null while the user must choose between several attached USB
     * drives — the manual menu item has no deviceName, and resolveDrive
     * refuses to guess. The Manage screen renders this as a picker dialog.
     */
    data class UsbDrivePicker(
        val drives: List<sh.haven.core.usb.UsbDeviceInfo>,
        val writable: Boolean,
        /** true → the picker's taps open the live (route:"guest") session instead of the VM. */
        val live: Boolean = false,
    )
    private val _usbDrivePicker = MutableStateFlow<UsbDrivePicker?>(null)
    val usbDrivePicker: StateFlow<UsbDrivePicker?> = _usbDrivePicker.asStateFlow()

    fun dismissUsbDrivePicker() {
        _usbDrivePicker.value = null
    }

    /**
     * Non-null while the user must choose a mount route for a drive Android
     * has mounted itself (#603): browse it directly through the Files tab
     * (no VM), or commit to the Linux-VM boot. Shown every time — correlation
     * is a heuristic, so the choice isn't remembered.
     */
    data class UsbRouteChoice(
        val deviceName: String,
        val productName: String?,
        val match: sh.haven.app.usb.UsbMountMatch,
        val writable: Boolean,
    )
    private val _usbRouteChoice = MutableStateFlow<UsbRouteChoice?>(null)
    val usbRouteChoice: StateFlow<UsbRouteChoice?> = _usbRouteChoice.asStateFlow()

    fun dismissUsbRouteChoice() {
        _usbRouteChoice.value = null
    }

    /** Commit to the Linux-VM boot (the pre-#603 open path, unchanged). */
    fun openUsbDriveVm(deviceName: String?, writable: Boolean = false) {
        viewModelScope.launch {
            try {
                usbDriveVmManager.open(deviceName, writable)
                _userMessages.emit("Opening the USB drive in a Linux VM — this can take a few minutes; progress is shown below.")
            } catch (e: sh.haven.app.usb.UsbDriveVmManager.UsbVmException) {
                _userMessages.emit(e.message ?: "Couldn't open USB drive")
            }
        }
    }

    /** Open the Android-mounted volume directly in the Files tab — no VM boot. */
    fun openUsbDriveDirectly(choice: UsbRouteChoice) {
        _usbRouteChoice.value = null
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.NavigateToSftpPath("local", choice.match.volume.path),
        )
        val where = choice.match.volume.description ?: choice.match.volume.path
        _userMessages.tryEmit("Browsing $where directly — no VM needed.")
    }

    /**
     * Boot a VM that mounts the attached USB drive; its files appear as a
     * connection. When Android has already mounted the drive (#603), a route
     * choice is offered first; without a mount the VM path runs as before,
     * with a one-line why (ext4/GPT/LUKS are only readable through the VM).
     */
    fun openUsbDrive(deviceName: String? = null, writable: Boolean = false) {
        if (deviceName == null) {
            val drives = usbDriveVmManager.massStorageDevices()
            if (drives.size > 1) {
                _usbDrivePicker.value = UsbDrivePicker(drives, writable)
                return
            }
        }
        _usbDrivePicker.value = null
        viewModelScope.launch {
            val match = try {
                usbDriveVmManager.androidMount(deviceName)
            } catch (e: sh.haven.app.usb.UsbDriveVmManager.UsbVmException) {
                _userMessages.emit(e.message ?: "Couldn't open USB drive")
                return@launch
            }
            if (match != null) {
                val info = usbDriveVmManager.massStorageDevices().firstOrNull { it.deviceName == match.deviceName }
                _usbRouteChoice.value = UsbRouteChoice(match.deviceName, info?.productName, match, writable)
            } else {
                _userMessages.emit(
                    "Android hasn't mounted this drive — opening it in the Linux VM (the only way to read ext4/GPT/LUKS filesystems).",
                )
                openUsbDriveVm(deviceName, writable)
            }
        }
    }

    fun closeUsbDrive(busid: String) {
        viewModelScope.launch {
            usbDriveVmManager.close(busid)
            _userMessages.emit("USB drive VM closed.")
        }
    }

    // --- Live USB card via the UML guest (fast route) -----------------------
    // Same drive set as the VM route, but the card is served raw over NBD to a
    // UML guest that boots in seconds — ddrescue work, no filesystem mounting.

    val usbLiveSessions: StateFlow<Map<String, sh.haven.app.usb.UmlRecoveryManager.Status>> = umlRecoveryManager.sessions

    fun openUsbDriveLive(deviceName: String? = null, writable: Boolean = false) {
        if (deviceName == null) {
            val drives = umlRecoveryManager.massStorageDevices()
            if (drives.size > 1) {
                _usbDrivePicker.value = UsbDrivePicker(drives, writable, live = true)
                return
            }
        }
        _usbDrivePicker.value = null
        viewModelScope.launch {
            try {
                umlRecoveryManager.open(deviceName, writable)
                _userMessages.emit("Attaching the card live — open the \"USB: … (live)\" connection for the rescue console.")
            } catch (e: sh.haven.app.usb.UmlRecoveryManager.UmlRecoveryException) {
                _userMessages.emit(e.message ?: "Couldn't open the card live")
            }
        }
    }

    fun closeUsbLive(busid: String) {
        viewModelScope.launch {
            umlRecoveryManager.close(busid)
            _userMessages.emit("Live card session closed.")
        }
    }

    /** Unlock a LUKS-encrypted partition on an open USB-drive VM. */
    fun unlockUsbDrivePartition(busid: String, devicePath: String, passphrase: String) {
        viewModelScope.launch {
            try {
                usbDriveVmManager.unlockPartition(busid, devicePath, passphrase)
                _userMessages.emit("Partition unlocked.")
            } catch (e: sh.haven.app.usb.UsbDriveVmManager.UsbVmException) {
                _userMessages.emit(e.message ?: "Couldn't unlock the partition")
            }
        }
    }

    // --- Desktop-manager dialog drafts -------------------------------------
    //
    // Flag AND fields together, never the flag alone. These screens sit in a
    // HorizontalPager under a nav host that can leave the composition, so a
    // rotation takes any open dialog with it.
    //

    /** The desktop-setup dialog: which DE it was opened for (null = closed) plus its draft. */
    data class DesktopSetupDraft(
        val shellCmd: String = "/bin/sh",
        val addons: Set<ProotManager.DesktopAddon> = emptySet(),
    )

    private val _setupDesktopDe = MutableStateFlow<ProotManager.DesktopEnvironment?>(null)
    val setupDesktopDe: StateFlow<ProotManager.DesktopEnvironment?> = _setupDesktopDe.asStateFlow()

    private val _desktopSetupDraft = MutableStateFlow(DesktopSetupDraft())
    val desktopSetupDraft: StateFlow<DesktopSetupDraft> = _desktopSetupDraft.asStateFlow()

    fun openDesktopSetup(de: ProotManager.DesktopEnvironment) {
        _desktopSetupDraft.value = DesktopSetupDraft()
        _setupDesktopDe.value = de
    }

    fun setDesktopSetupDraft(draft: DesktopSetupDraft) { _desktopSetupDraft.value = draft }

    fun dismissDesktopSetup() {
        _setupDesktopDe.value = null
        _desktopSetupDraft.value = DesktopSetupDraft()
    }

    /** The bring-your-own-rootfs import dialog's draft (#284). */
    data class ImportRootfsDraft(
        val id: String = "",
        val label: String = "",
        val source: String = "",
        val family: sh.haven.core.local.proot.PackageFamily = sh.haven.core.local.proot.PackageFamily.APT,
    )

    private val _showImportRootfs = MutableStateFlow(false)
    val showImportRootfs: StateFlow<Boolean> = _showImportRootfs.asStateFlow()

    private val _importRootfsDraft = MutableStateFlow(ImportRootfsDraft())
    val importRootfsDraft: StateFlow<ImportRootfsDraft> = _importRootfsDraft.asStateFlow()

    fun openImportRootfs() {
        _importRootfsDraft.value = ImportRootfsDraft()
        _showImportRootfs.value = true
    }

    fun setImportRootfsDraft(draft: ImportRootfsDraft) { _importRootfsDraft.value = draft }

    fun dismissImportRootfs() {
        _showImportRootfs.value = false
        _importRootfsDraft.value = ImportRootfsDraft()
    }

    /** The custom-binds editor's rows as (host, guest) pairs — a plain list, replaced wholesale on each edit. */
    private val _showCustomBinds = MutableStateFlow(false)
    val showCustomBinds: StateFlow<Boolean> = _showCustomBinds.asStateFlow()

    private val _customBindsDraft = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val customBindsDraft: StateFlow<List<Pair<String, String>>> = _customBindsDraft.asStateFlow()

    fun openCustomBinds(initial: List<sh.haven.core.local.proot.CustomBind>) {
        _customBindsDraft.value = initial.map { it.host to it.guest }
        _showCustomBinds.value = true
    }

    fun setCustomBindsDraft(rows: List<Pair<String, String>>) { _customBindsDraft.value = rows }

    fun dismissCustomBinds() {
        _showCustomBinds.value = false
        _customBindsDraft.value = emptyList()
    }

    private val _showUsbWritableConfirm = MutableStateFlow(false)
    val showUsbWritableConfirm: StateFlow<Boolean> = _showUsbWritableConfirm.asStateFlow()

    fun setShowUsbWritableConfirm(open: Boolean) { _showUsbWritableConfirm.value = open }

    /** The distro a delete confirmation is currently asking about — the target has to survive with the flag, or the dialog returns pointing at nothing. */
    private val _distroPendingDelete = MutableStateFlow<sh.haven.core.local.proot.Distro?>(null)
    val distroPendingDelete: StateFlow<sh.haven.core.local.proot.Distro?> = _distroPendingDelete.asStateFlow()

    fun setDistroPendingDelete(distro: sh.haven.core.local.proot.Distro?) { _distroPendingDelete.value = distro }

    /**
     * A distro add the confirm dialog is currently asking about (#620). Tapping
     * "+ <name> (~MB)" in the distro dropdown downloads a few hundred MB, so it
     * goes through a confirm first, mirroring [distroPendingDelete]. Holds the
     * target (and the arch for emulated foreign adds) so the dialog can act on it.
     */
    sealed interface PendingAdd {
        data class Native(val distro: Distro) : PendingAdd
        data class Foreign(val distro: Distro, val arch: sh.haven.core.local.proot.Arch) : PendingAdd
    }

    private val _distroPendingAdd = MutableStateFlow<PendingAdd?>(null)
    val distroPendingAdd: StateFlow<PendingAdd?> = _distroPendingAdd.asStateFlow()

    fun requestAddDistro(distro: Distro) { _distroPendingAdd.value = PendingAdd.Native(distro) }

    fun requestAddForeignDistro(distro: Distro, arch: sh.haven.core.local.proot.Arch) {
        _distroPendingAdd.value = PendingAdd.Foreign(distro, arch)
    }

    fun dismissDistroPendingAdd() { _distroPendingAdd.value = null }

    fun confirmDistroPendingAdd() {
        when (val pending = _distroPendingAdd.value) {
            is PendingAdd.Native -> addDistro(pending.distro)
            is PendingAdd.Foreign -> addForeignDistro(pending.distro, pending.arch)
            null -> {}
        }
        _distroPendingAdd.value = null
    }

    private val _tabs = MutableStateFlow<List<DesktopTab>>(emptyList())
    val tabs: StateFlow<List<DesktopTab>> = _tabs.asStateFlow()

    private val _activeTabIndex = MutableStateFlow(0)
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    /** The currently active tab, derived for convenience. */
    val activeTab: StateFlow<DesktopTab?> = combine(_tabs, _activeTabIndex) { tabs, idx ->
        tabs.getOrNull(idx)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Whether the active tab is connected (used to disable pager swipe). */
    val activeTabConnected: StateFlow<Boolean> = combine(_tabs, _activeTabIndex) { tabs, idx ->
        tabs.getOrNull(idx)?.connected?.value == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // --- Tab management ---

    fun selectTab(index: Int) {
        val tabs = _tabs.value
        if (index in tabs.indices) {
            pauseAllExcept(index)
            _activeTabIndex.value = index
        }
    }

    fun moveTab(fromIndex: Int, direction: Int) {
        val tabs = _tabs.value.toMutableList()
        val toIndex = fromIndex + direction
        if (fromIndex !in tabs.indices || toIndex !in tabs.indices) return
        val tab = tabs.removeAt(fromIndex)
        tabs.add(toIndex, tab)
        _tabs.value = tabs
        if (_activeTabIndex.value == fromIndex) _activeTabIndex.value = toIndex
        else if (_activeTabIndex.value == toIndex) _activeTabIndex.value = fromIndex
    }

    /**
     * Reconnect a tab that hit "connection lost" (e.g. no server listening),
     * from the inline Retry button — so a dead desktop isn't a long-press dead
     * end (#121, KoriKraut). Profile-backed tabs re-run the full connect via the
     * AgentUiCommand bus (same path a tap uses), which re-establishes the SSH
     * tunnel with a fresh session instead of reusing the torn-down one.
     */
    fun retryTab(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        val profileId = when (tab) {
            is DesktopTab.Rdp -> tab.profileId
            is DesktopTab.Spice -> tab.profileId
            else -> null
        }
        if (profileId != null) {
            closeTab(tabId)
            agentUiCommandBus.emit(
                sh.haven.core.data.agent.AgentUiCommand.ConnectProfile(profileId),
            )
            return
        }
    }

    fun closeTab(tabId: String) {
        val tabs = _tabs.value.toMutableList()
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val tab = tabs.removeAt(index)
        disconnectTab(tab)
        _tabs.value = tabs
        if (_activeTabIndex.value >= tabs.size && tabs.isNotEmpty()) {
            _activeTabIndex.value = tabs.size - 1
        }
        pauseAllExcept(_activeTabIndex.value)
    }

    // --- Tab deduplication ---

    /**
     * Find an existing tab matching a connection. Matches by profileId first,
     * then by host:port.
     * Returns the tab index, or -1 if not found.
     */
    private fun findExistingTab(
        profileId: String?,
        host: String,
        port: Int,
        protocol: String,
        username: String? = null,
    ): Int {
        val tabs = _tabs.value
        // Match by profileId if available
        if (profileId != null) {
            val idx = tabs.indexOfFirst { tab ->
                when (tab) {
                    is DesktopTab.Rdp -> tab.profileId == profileId
                    is DesktopTab.Spice -> tab.profileId == profileId
                    else -> false
                }
            }
            if (idx >= 0) return idx
        }
        // Match by host+port (and username for RDP)
        return tabs.indexOfFirst { tab ->
            when {
                protocol == "RDP" && tab is DesktopTab.Rdp && tab.profileId == null ->
                    tab.label == "$host:$port"
                protocol == "SPICE" && tab is DesktopTab.Spice && tab.profileId == null ->
                    tab.label == "$host:$port"
                else -> false
            }
        }
    }

    // --- RDP sessions ---

    fun addRdpSession(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String = "",
        sshForward: Boolean = false,
        sshSessionId: String? = null,
        profileId: String? = null,
        useNla: Boolean = true,
        colorDepth: Int = 16,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Deduplicate: if a tab for the same connection exists, reuse or replace
            val existingIdx = findExistingTab(profileId, host, port, "RDP", username)
            if (existingIdx >= 0) {
                val existing = _tabs.value[existingIdx]
                if (existing.connected.value) {
                    pauseAllExcept(existingIdx)
                    _activeTabIndex.value = existingIdx
                    return@launch
                }
                closeTab(existing.id)
            }

            val label = resolveLabel(profileId) ?: "$host:$port"
            val colorTag = resolveColorTag(profileId)
            val tabId = UUID.randomUUID().toString()

            // Hoisted out of try so the catch / onError can clean it up
            // when the dial fails (#121). tunnelLease owns the forward +
            // dependent release + the parent-gone teardown callback.
            var tunnelLease: SshSessionManager.TunnelLease? = null
            try {
                val actualHost: String
                val actualPort: Int

                // SOCKS5 endpoint of any WireGuard / Tailscale tunnel the
                // profile selected (#149 step 4 + 9). Only consulted when
                // not going through SSH RemoteForward — that already
                // tunnels the connection via 127.0.0.1:<localPort>.
                var rdpSocksProxy: sh.haven.rdp.SocksProxyConfig? = null

                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, host, port)
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "RDP SSH tunnel: localhost:$lp -> ${LogRedact.host(host, port)}")
                    // Tie this tab to the SSH session so it closes if the SSH
                    // is torn down for any reason (#121).
                    if (profileId != null) {
                        tunnelLease = sshSessionManager.acquireTunnelLease(
                            sessionId = sshSessionId,
                            dependentProfileId = profileId,
                            localForwardPort = lp,
                        ) { viewModelScope.launch { closeTab(tabId) } }
                    }
                } else {
                    actualHost = host
                    actualPort = port
                    if (profileId != null) {
                        val profile = connectionRepository.getById(profileId)
                        if (profile != null) {
                            tunnelResolver.socksEndpoint(profile)?.let { addr ->
                                rdpSocksProxy = sh.haven.rdp.SocksProxyConfig(
                                    host = addr.hostString,
                                    port = addr.port.toUShort(),
                                )
                                Log.d(TAG, "RDP routed via SOCKS5 ${LogRedact.of(addr.hostString)}:${addr.port} -> ${LogRedact.host(host, port)}")
                            }
                        }
                    }
                }

                val connected = MutableStateFlow(false)
                val frame = MutableStateFlow<Bitmap?>(null)
                val error = MutableStateFlow<String?>(null)
                val cursor = MutableStateFlow<CursorOverlay?>(null)
                val pointerPos = MutableStateFlow(0 to 0)

                val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
                val verboseBuffer = if (verboseEnabled) ConcurrentLinkedQueue<String>() else null

                // Was hardcoded to the RdpSession defaults (1920x1080) with no
                // way to change it, so a server drawing anything else had its
                // updates discarded (#422 — VirtualBox defaults to 2560x1600
                // and never announces a resize).
                val rdpWidth = preferencesRepository.rdpDesktopWidth.first()
                val rdpHeight = preferencesRepository.rdpDesktopHeight.first()

                val session = RdpSession(
                    sessionId = "rdp-$tabId",
                    host = actualHost,
                    port = actualPort,
                    width = rdpWidth,
                    height = rdpHeight,
                    username = username,
                    password = password,
                    domain = domain,
                    useNla = useNla,
                    colorDepth = colorDepth,
                    verboseBuffer = verboseBuffer,
                    socksProxy = rdpSocksProxy,
                )
                session.onFrameUpdate = { bitmap -> frame.value = bitmap }
                session.onCursorUpdate = { bmp, hx, hy ->
                    cursor.value = if (bmp == null) null else CursorOverlay(bmp, hx, hy)
                }
                session.onCursorPosition = { x, y -> pointerPos.value = x to y }
                session.onError = { e ->
                    Log.e(TAG, "RDP error on tab $tabId", e)
                    error.value = RdpViewModel.describeError(e, host, port)
                    connected.value = false
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                        }
                    }
                    // RdpSession.start() reports connect failures via this
                    // callback rather than throwing, so the catch block never
                    // runs for them — release the SSH tunnel lease + any WG
                    // dependent so nothing lingers with a green dot (#121,
                    // same shape as the VNC path). Idempotent with disconnectTab.
                    tunnelLease?.close()
                    releaseSshTunnelDependent(profileId)
                }
                session.onConnected = { _, _ ->
                    // Real handshake complete — only now flip the tab to
                    // "connected". Before this the UI stays on a Connecting
                    // state rather than a misleading empty framebuffer.
                    connected.value = true
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTED)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val startLog = session.drainVerboseLog()
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, verboseLog = startLog)
                        }
                    }
                }
                session.onDisconnected = {
                    // Session ended without a surfaced error (server logoff /
                    // transport death). Skip if the tab was already closed by
                    // the user — disconnectTab has cleared the registry and a
                    // late status write would resurrect a ghost entry (#437).
                    if (_tabs.value.any { it.id == tabId }) {
                        connected.value = false
                        desktopSessionRegistry.setStatus(profileId, DesktopStatus.DISCONNECTED)
                        if (profileId != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                connectionLogRepository.logEvent(
                                    profileId,
                                    ConnectionLog.Status.DISCONNECTED,
                                    details = "Session ended by server",
                                    verboseLog = session.drainVerboseLog(),
                                )
                            }
                        }
                    }
                }

                // Knock only on the direct path. SSH-forward goes via a
                // localhost tunnel (knock happened at the SSH connect)
                // and the SOCKS path runs through a userspace tunnel
                // that knockd can't observe.
                if (!sshForward && rdpSocksProxy == null && profileId != null) {
                    runKnockIfConfigured(profileId, actualHost)
                }

                desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTING)
                session.start()
                // NB: intentionally no `connected.value = true` here — that
                // happens in session.onConnected once the Rust worker
                // thread completes the handshake.

                val tab = DesktopTab.Rdp(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    session = session,
                    _connected = connected,
                    _frame = frame,
                    _error = error,
                    _cursor = cursor,
                    _pointerPos = pointerPos,
                    tunnelLease = tunnelLease,
                    profileId = profileId,
                )

                val tabs = _tabs.value.toMutableList()
                tabs.add(tab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1
                // Expose the rendered frame + cursor to MCP capture_desktop_tab.
                desktopSessionRegistry.registerFrameHandle(
                    profileId,
                    DesktopFrameHandle(
                        protocol = "RDP",
                        frame = { frame.value },
                        cursor = { cursor.value?.let { CursorSnapshot(it.bitmap, it.hotspotX, it.hotspotY) } },
                        pointer = { pointerPos.value },
                    ),
                )
                // Expose mouse/clipboard input to the MCP remote-desktop tools.
                desktopSessionRegistry.registerInputHandle(
                    profileId,
                    DesktopInputHandle(
                        protocol = "RDP",
                        mouseMove = { x, y -> tab.remoteDesktop.sendMouseMove(x, y) },
                        mouseClick = { x, y, button -> tab.remoteDesktop.sendMouseClick(x, y, button) },
                        mouseWheel = { deltaY -> tab.remoteDesktop.sendMouseWheel(deltaY) },
                        clipboard = { text -> tab.remoteDesktop.sendClipboardText(text) },
                    ),
                )
                // Let MCP disconnect_profile close this tab even when there's
                // no tunnel lease to cascade through (direct connections, #437).
                desktopSessionRegistry.registerCloseHandle(profileId) {
                    viewModelScope.launch { closeTab(tabId) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RDP connect failed", e)
                if (profileId != null) {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                }
                // Connect-failure cleanup — same reasoning as the VNC
                // path above (#121): release the lease + WG dependent.
                tunnelLease?.close()
                releaseSshTunnelDependent(profileId)
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                // Show error in a temporary tab (no session to close)
                val errorTab = DesktopTab.Rdp(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    session = RdpSession("err", host, port, username, password, domain),
                    _error = MutableStateFlow(RdpViewModel.describeError(e, host, port)),
                    profileId = profileId,
                )
                val tabs = _tabs.value.toMutableList()
                tabs.add(errorTab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1
            }
        }
    }

    // --- SPICE sessions ---

    fun addSpiceSession(
        host: String,
        port: Int,
        password: String?,
        sshForward: Boolean = false,
        sshSessionId: String? = null,
        profileId: String? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val existingIdx = findExistingTab(profileId, host, port, "SPICE")
            if (existingIdx >= 0) {
                val existing = _tabs.value[existingIdx]
                if (existing.connected.value) {
                    pauseAllExcept(existingIdx)
                    _activeTabIndex.value = existingIdx
                    return@launch
                }
                closeTab(existing.id)
            }

            val label = resolveLabel(profileId) ?: "$host:$port"
            val colorTag = resolveColorTag(profileId)
            val tabId = UUID.randomUUID().toString()

            var tunnelLease: SshSessionManager.TunnelLease? = null
            try {
                val actualHost: String
                val actualPort: Int
                // SPICE's Rust client does its own TCP dial — no socket/SOCKS
                // injection like VNC/RDP — so SSH-forward goes via a local
                // -L forward and we hand it 127.0.0.1:<localPort>.
                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, host, port)
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "SPICE SSH tunnel: localhost:$lp -> ${LogRedact.host(host, port)}")
                    if (profileId != null) {
                        tunnelLease = sshSessionManager.acquireTunnelLease(
                            sessionId = sshSessionId,
                            dependentProfileId = profileId,
                            localForwardPort = lp,
                        ) { viewModelScope.launch { closeTab(tabId) } }
                    }
                } else {
                    actualHost = host
                    actualPort = port
                }

                val connected = MutableStateFlow(false)
                val frame = MutableStateFlow<Bitmap?>(null)
                val error = MutableStateFlow<String?>(null)
                val cursor = MutableStateFlow<CursorOverlay?>(null)
                val pointerPos = MutableStateFlow(0 to 0)

                val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
                val verboseBuffer = if (verboseEnabled) ConcurrentLinkedQueue<String>() else null

                val session = SpiceSession(
                    sessionId = "spice-$tabId",
                    host = actualHost,
                    port = actualPort,
                    password = password,
                    verboseBuffer = verboseBuffer,
                )
                session.onFrameUpdate = { bitmap -> frame.value = bitmap }
                session.onCursorUpdate = { bmp, hx, hy ->
                    cursor.value = if (bmp == null) null else CursorOverlay(bmp, hx, hy)
                }
                session.onCursorPosition = { x, y -> pointerPos.value = x to y }
                session.onError = { e ->
                    Log.e(TAG, "SPICE error on tab $tabId", e)
                    error.value = e.message ?: "SPICE connection to $host:$port failed"
                    connected.value = false
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                        }
                    }
                    tunnelLease?.close()
                    releaseSshTunnelDependent(profileId)
                }
                session.onConnected = { _, _ ->
                    connected.value = true
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTED)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val startLog = session.drainVerboseLog()
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, verboseLog = startLog)
                        }
                    }
                }
                session.onDisconnected = {
                    // Session ended without a surfaced error — mirror the RDP
                    // path so the tab never claims "connected" past death (#437).
                    if (_tabs.value.any { it.id == tabId }) {
                        connected.value = false
                        desktopSessionRegistry.setStatus(profileId, DesktopStatus.DISCONNECTED)
                        if (profileId != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                connectionLogRepository.logEvent(
                                    profileId,
                                    ConnectionLog.Status.DISCONNECTED,
                                    details = "Session ended by server",
                                    verboseLog = session.drainVerboseLog(),
                                )
                            }
                        }
                    }
                }

                // Create + show the tab before start() — SPICE's connect()
                // blocks until established, so the UI sits on "Connecting"
                // until onConnected flips it (or onError sets the error).
                val tab = DesktopTab.Spice(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    session = session,
                    _connected = connected,
                    _frame = frame,
                    _error = error,
                    _cursor = cursor,
                    _pointerPos = pointerPos,
                    tunnelLease = tunnelLease,
                    profileId = profileId,
                )
                val tabs = _tabs.value.toMutableList()
                tabs.add(tab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1
                desktopSessionRegistry.registerFrameHandle(
                    profileId,
                    DesktopFrameHandle(
                        protocol = "SPICE",
                        frame = { frame.value },
                        cursor = { cursor.value?.let { CursorSnapshot(it.bitmap, it.hotspotX, it.hotspotY) } },
                        pointer = { pointerPos.value },
                    ),
                )
                desktopSessionRegistry.registerInputHandle(
                    profileId,
                    DesktopInputHandle(
                        protocol = "SPICE",
                        mouseMove = { x, y -> tab.remoteDesktop.sendMouseMove(x, y) },
                        mouseClick = { x, y, button -> tab.remoteDesktop.sendMouseClick(x, y, button) },
                        mouseWheel = { deltaY -> tab.remoteDesktop.sendMouseWheel(deltaY) },
                        clipboard = { text -> tab.remoteDesktop.sendClipboardText(text) },
                    ),
                )
                // Let MCP disconnect_profile close this tab even when there's
                // no tunnel lease to cascade through (direct connections, #437).
                desktopSessionRegistry.registerCloseHandle(profileId) {
                    viewModelScope.launch { closeTab(tabId) }
                }

                // Knock only on the direct path (SSH-forward knocked at SSH connect).
                if (!sshForward && profileId != null) {
                    runKnockIfConfigured(profileId, actualHost)
                }

                desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTING)
                session.start() // blocks until established; fires onConnected/onError
            } catch (e: Exception) {
                // SpiceSession.start() invokes onError (which sets the tab's
                // error state) before rethrowing, so just clean up here.
                Log.e(TAG, "SPICE connect failed", e)
                if (profileId != null) {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                }
                tunnelLease?.close()
                releaseSshTunnelDependent(profileId)
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
            }
        }
    }

    fun sendSpiceKey(scancode: Int, pressed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            (activeTab.value as? DesktopTab.Spice)?.session?.sendKey(scancode, pressed)
        }
    }

    // --- Wayland ---

    fun addWaylandTab() {
        val tabs = _tabs.value
        val existing = tabs.indexOfFirst { it is DesktopTab.Wayland }
        if (existing >= 0) {
            selectTab(existing)
            return
        }
        val newTabs = tabs.toMutableList()
        newTabs.add(DesktopTab.Wayland())
        _tabs.value = newTabs
        _activeTabIndex.value = newTabs.size - 1
    }

    fun removeWaylandTab() {
        val tabs = _tabs.value.toMutableList()
        val index = tabs.indexOfFirst { it is DesktopTab.Wayland }
        if (index >= 0) {
            tabs.removeAt(index)
            _tabs.value = tabs
            if (_activeTabIndex.value >= tabs.size && tabs.isNotEmpty()) {
                _activeTabIndex.value = tabs.size - 1
            }
        }
    }

    // --- Input forwarding (operates on active tab) ---

    fun sendPointer(x: Int, y: Int) {
        // Mirror the latest pointer into per-tab state so the UI overlay
        // (cursor / virtual cursor seed) repaints immediately, without
        // waiting for the IO dispatch round-trip.
        when (val tab = activeTab.value) {
            is DesktopTab.Rdp -> tab._pointerPos.value = x to y
            is DesktopTab.Spice -> tab._pointerPos.value = x to y
            else -> {}
        }
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseMove(x, y)
        }
    }

    fun pressButton(button: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseButton(button, pressed = true)
        }
    }

    fun releaseButton(button: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseButton(button, pressed = false)
        }
    }

    fun sendClick(x: Int, y: Int, button: Int = 1) {
        when (val tab = activeTab.value) {
            is DesktopTab.Rdp -> tab._pointerPos.value = x to y
            is DesktopTab.Spice -> tab._pointerPos.value = x to y
            else -> {}
        }
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseClick(x, y, button)
        }
    }

    fun sendRdpKey(scancode: Int, pressed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            (activeTab.value as? DesktopTab.Rdp)?.session?.sendKey(scancode, pressed)
        }
    }

    fun typeRdpUnicode(codepoint: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = (activeTab.value as? DesktopTab.Rdp)?.session ?: return@launch
            s.sendUnicodeKey(codepoint, true)
            s.sendUnicodeKey(codepoint, false)
        }
    }

    /**
     * Per-Desktop-view orientation override. Stored in the ViewModel
     * so it survives any composable subtree recreation that happens
     * when the conditional tab-bar in DesktopScreen comes/goes during
     * an orientation change — that recreation reset a Composable-
     * local `remember` state to its initial value and the
     * LaunchedEffect immediately wrote that back, so the lock never
     * took effect.
     *
     * Default is UNSPECIFIED (auto / follow system) so the Desktop
     * tab behaves like its neighbours when no desktop session is
     * active and the user hasn't explicitly chosen landscape —
     * the previous LANDSCAPE default rotated the activity as soon
     * as the user landed on the empty Desktop tab, breaking the
     * left/right swipe-to-change-tab gesture for anyone using
     * Haven without a desktop connection. Users who want landscape
     * cycle to it via the orientation toolbar button.
     *
     * Values are the raw `ActivityInfo.SCREEN_ORIENTATION_*`
     * constants since the enum is private to feature modules. Cycle
     * order from the toolbar button: Auto -> Landscape -> Portrait
     * -> Auto.
     */
    private val _desktopOrientation = MutableStateFlow(
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    )
    val desktopOrientation: StateFlow<Int> = _desktopOrientation.asStateFlow()

    fun cycleDesktopOrientation() {
        _desktopOrientation.value = when (_desktopOrientation.value) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    fun scrollUp() {
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseWheel(deltaY = 1)
        }
    }

    fun scrollDown() {
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseWheel(deltaY = -1)
        }
    }

    /** Knock against the RDP/SPICE host using the profile's saved sequence,
     *  if any. Failures are logged but not thrown; the real socket open
     *  surfaces the actual symptom. */
    private suspend fun runKnockIfConfigured(profileId: String, host: String) {
        val profile = connectionRepository.getById(profileId) ?: return
        val seq = KnockSequence.parse(
            profile.portKnockSequence,
            delayMs = profile.portKnockDelayMs,
        ).getOrNull() ?: return
        val result = portKnocker.knock(host, seq)
        Log.d(
            TAG,
            if (result.ok) "[knock] ${seq.format()} -> ok in ${result.totalDurationMs}ms"
            else "[knock] ${seq.format()} -> failed after ${result.totalDurationMs}ms: ${result.error?.message}"
        )
    }

    private fun findSshClient(sessionId: String): SshConnection? {
        sshSessionManager.getSession(sessionId)?.let { return it.client }
        moshSessionManager.sessions.value[sessionId]?.sshClient?.let { return it as? SshClient }
        etSessionManager.sessions.value[sessionId]?.sshClient?.let { return it as? SshClient }
        return null
    }

    // --- Lifecycle ---

    private fun disconnectTab(tab: DesktopTab) {
        viewModelScope.launch(Dispatchers.IO) {
            when (tab) {
                is DesktopTab.Rdp -> {
                    if (tab.profileId != null) {
                        val verboseLog = tab.session.drainVerboseLog()
                        connectionLogRepository.logEvent(tab.profileId, ConnectionLog.Status.DISCONNECTED, verboseLog = verboseLog)
                    }
                    tab.session.close()
                    tab.tunnelLease?.close()
                    releaseSshTunnelDependent(tab.profileId)
                    desktopSessionRegistry.clear(tab.profileId)
                    desktopSessionRegistry.clearFrameHandle(tab.profileId)
                    desktopSessionRegistry.clearInputHandle(tab.profileId)
                    desktopSessionRegistry.clearCloseHandle(tab.profileId)
                }
                is DesktopTab.Spice -> {
                    if (tab.profileId != null) {
                        val verboseLog = tab.session.drainVerboseLog()
                        connectionLogRepository.logEvent(tab.profileId, ConnectionLog.Status.DISCONNECTED, verboseLog = verboseLog)
                    }
                    tab.session.close()
                    tab.tunnelLease?.close()
                    releaseSshTunnelDependent(tab.profileId)
                    desktopSessionRegistry.clear(tab.profileId)
                    desktopSessionRegistry.clearFrameHandle(tab.profileId)
                    desktopSessionRegistry.clearInputHandle(tab.profileId)
                    desktopSessionRegistry.clearCloseHandle(tab.profileId)
                }
                is DesktopTab.Wayland -> {} // compositor lifecycle managed externally
            }
        }
    }

    /**
     * Decrement refcounts on any auto-opened tunnels this profile holds.
     *
     * SSH-side: the v5.24.85 wiring in [ConnectionsViewModel.disconnect]
     * called this for connections-tab disconnects, but the bottom-of-
     * Desktop-tab "Disconnect" button routes through [closeTab] /
     * [disconnectTab] instead — without this call the auto-opened SSH
     * idled on with a green dot in the connections list (#121,
     * KoriKraut on v5.24.89).
     *
     * WireGuard / Tailscale side: a profile that dialled through
     * [TunnelResolver.dial] holds a slot in [TunnelManager]'s dependent
     * set. Release here so the underlying tunnel tears down when the
     * last dependent disconnects (#149).
     *
     * No-op when [profileId] is null (older tabs / Wayland) or when the
     * profile was never registered as a tunnel dependent on either side.
     */
    private fun releaseSshTunnelDependent(profileId: String?) {
        if (profileId == null) return
        sshSessionManager.releaseTunnelDependent(profileId)
        viewModelScope.launch { tunnelResolver.release(profileId) }
    }


    private fun pauseAllExcept(activeIndex: Int) {
        _tabs.value.forEachIndexed { index, tab ->
            val rd = tab.remoteDesktop ?: return@forEachIndexed
            if (index == activeIndex) rd.resume() else rd.pause()
        }
    }

    private suspend fun resolveLabel(profileId: String?): String? {
        if (profileId == null) return null
        return try {
            connectionRepository.getById(profileId)?.label
        } catch (_: Exception) { null }
    }

    private suspend fun resolveColorTag(profileId: String?): Int {
        if (profileId == null) return 0
        return try {
            connectionRepository.getById(profileId)?.colorTag ?: 0
        } catch (_: Exception) { 0 }
    }

    override fun onCleared() {
        super.onCleared()
        _tabs.value.forEach { disconnectTab(it) }
    }
}
