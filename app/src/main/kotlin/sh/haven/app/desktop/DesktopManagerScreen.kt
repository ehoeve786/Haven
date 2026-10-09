package sh.haven.app.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.ProotDnsMode
import sh.haven.core.local.ProotManager
import sh.haven.core.local.VmArch
import sh.haven.core.local.proot.Compatibility
import sh.haven.core.local.proot.Distro
import sh.haven.core.local.proot.DistroCatalog
import sh.haven.core.local.proot.MirrorCatalog
import sh.haven.core.local.proot.MirrorRegion
import sh.haven.core.local.proot.PackageFamily
import sh.haven.feature.connections.R
import sh.haven.app.R as AppR

/**
 * Desktop-tab Manage view (issue #162 Phase 3c). Hosts the distro picker
 * + rootfs setup progress + DE install/start/stop rows that used to live
 * in a full-screen dialog behind the Connections topbar. Co-located with
 * the session viewer ([DesktopScreen]) so the install path and the run
 * path share one home — `DesktopScreen` toggles between this view and
 * the session tabs via the TopAppBar Manage action.
 *
 * State and actions are read from [DesktopViewModel]; the three
 * composables below (`DesktopManagerSection`, `DesktopRow`,
 * `DesktopSetupDialog`) are otherwise the same shape they had in
 * `feature/connections/.../ConnectionsScreen.kt` pre-3c — moving here
 * was a relocation, not a rewrite.
 */
@Composable
fun DesktopManagerScreen(viewModel: DesktopViewModel = hiltViewModel()) {
    val installedDesktops = viewModel.installedDesktops
    val desktopStates by viewModel.desktopStates.collectAsState()
    val desktopSetupState by viewModel.desktopSetupState.collectAsState()
    val activeDistroId by viewModel.activeDistroId.collectAsState()
    val rootfsSetupState by viewModel.rootfsSetupState.collectAsState()
    val installedDistros = viewModel.installedDistros
    val availableDistros = viewModel.availableDistros
    val availableForeignDistros = viewModel.availableForeignDistros
    val usbDrivePicker by viewModel.usbDrivePicker.collectAsState()
    val usbRouteChoice by viewModel.usbRouteChoice.collectAsState()
    val isRootfsReady = rootfsSetupState is ProotManager.SetupState.Ready
    val mirrorRegion by viewModel.mirrorRegion.collectAsState()
    val dnsMode by viewModel.dnsMode.collectAsState()
    val dnsServers by viewModel.dnsServers.collectAsState()
    val remapLowPorts by viewModel.remapLowPorts.collectAsState()
    val shareStorageWithGuest by viewModel.shareStorageWithGuest.collectAsState()
    val bindAndroidSystem by viewModel.bindAndroidSystem.collectAsState()
    val customBindsRev by viewModel.customBindsRev.collectAsState()
    val usbDriveSessions by viewModel.usbDriveSessions.collectAsState()
    val usbLiveSessions by viewModel.usbLiveSessions.collectAsState()
    val applianceProvisioned by viewModel.applianceProvisioned.collectAsState()

    val setupDesktopDe by viewModel.setupDesktopDe.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        DesktopManagerSection(
            viewModel = viewModel,
            installedDesktops = installedDesktops,
            desktopStates = desktopStates,
            desktopSetupState = desktopSetupState,
            activeDistroId = activeDistroId,
            installedDistros = installedDistros,
            availableDistros = availableDistros,
            availableForeignDistros = availableForeignDistros,
            rootfsSetupState = rootfsSetupState,
            isRootfsReady = isRootfsReady,
            mirrorRegion = mirrorRegion,
            onSetMirrorRegion = { viewModel.setMirrorRegion(it) },
            dnsMode = dnsMode,
            onSetDnsMode = { viewModel.setDnsMode(it) },
            dnsServers = dnsServers,
            onSetDnsServers = { viewModel.setDnsServers(it) },
            remapLowPorts = remapLowPorts,
            onSetRemapLowPorts = { viewModel.setRemapLowPorts(it) },
            shareStorageWithGuest = shareStorageWithGuest,
            onSetShareStorageWithGuest = { viewModel.setShareStorageWithGuest(it) },
            bindAndroidSystem = bindAndroidSystem,
            onSetBindAndroidSystem = { viewModel.setBindAndroidSystem(it) },
            customBindsRev = customBindsRev,
            customBindsFor = { viewModel.customBinds(it) },
            onSetCustomBinds = { id, binds -> viewModel.setCustomBinds(id, binds) },
            onImportRootfs = { id, label, family, source -> viewModel.importRootfs(id, label, family, source) },
            usbDriveSessions = usbDriveSessions,
            onOpenUsbDrive = { viewModel.openUsbDrive() },
            onOpenUsbDriveWritable = { viewModel.openUsbDrive(writable = true) },
            onCloseUsbDrive = { busid -> viewModel.closeUsbDrive(busid) },
            usbLiveSessions = usbLiveSessions,
            onOpenUsbLive = { viewModel.openUsbDriveLive() },
            onOpenUsbLiveWritable = { viewModel.openUsbDriveLive(writable = true) },
            onCloseUsbLive = { busid -> viewModel.closeUsbLive(busid) },
            onUnlockUsbDrivePartition = { busid, devicePath, passphrase -> viewModel.unlockUsbDrivePartition(busid, devicePath, passphrase) },
            applianceProvisioned = applianceProvisioned,
            onDeleteUsbAppliance = { viewModel.deleteUsbAppliance() },
            onSwitchDistro = { viewModel.switchActiveDistro(it) },
            onOpenShellForDistro = { viewModel.openShellForDistro(it) },
            onAddDistro = { viewModel.addDistro(it) },
            onAddForeignDistro = { distro, arch -> viewModel.addForeignDistro(distro, arch) },
            onDeleteDistro = { viewModel.deleteDistro(it.id) },
            onInstall = { de -> viewModel.openDesktopSetup(de) },
            onStart = { viewModel.startDesktop(it) },
            onStop = { viewModel.stopDesktop(it) },
            onOpenTerminalInDesktop = { viewModel.openTerminalInDesktop(it) },
            onUninstall = { viewModel.uninstallDesktop(it) },
            onRetryRootfs = { viewModel.retryRootfsInstall() },
        )
    }

    // Several USB drives attached and the menu item can't guess — list them
    // (the MCP tool disambiguates by deviceName; this is the UI's equivalent).
    usbDrivePicker?.let { picker ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissUsbDrivePicker() },
            title = { Text(stringResource(AppR.string.app_desktop_usb_drive_picker_title)) },
            text = {
                Column {
                    picker.drives.forEach { drive ->
                        val line = listOfNotNull(
                            drive.productName?.takeIf { it.isNotBlank() },
                            drive.deviceName,
                        ).joinToString("  ·  ")
                        TextButton(
                            onClick = {
                                if (picker.live) {
                                    viewModel.openUsbDriveLive(drive.deviceName, picker.writable)
                                } else {
                                    viewModel.openUsbDrive(drive.deviceName, picker.writable)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(line)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUsbDrivePicker() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // #603: Android mounted this drive itself — choose the cheap route (browse
    // the mounted volume in Files) or commit to the Linux-VM boot.
    usbRouteChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissUsbRouteChoice() },
            title = {
                Text(
                    stringResource(
                        AppR.string.app_desktop_usb_route_title,
                        listOfNotNull(
                            choice.productName?.takeIf { it.isNotBlank() },
                            choice.deviceName,
                        ).joinToString("  ·  "),
                    ),
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(
                            AppR.string.app_desktop_usb_route_body,
                            choice.match.volume.description ?: choice.match.volume.path,
                        ),
                    )
                    if (!choice.match.writable) {
                        Text(
                            stringResource(AppR.string.app_desktop_usb_route_readonly_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.openUsbDriveDirectly(choice) }) {
                    Text(stringResource(AppR.string.app_desktop_usb_route_browse))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUsbRouteChoice(); viewModel.openUsbDriveVm(choice.deviceName, choice.writable) }) {
                    Text(stringResource(AppR.string.app_desktop_usb_route_vm))
                }
            },
        )
    }

    setupDesktopDe?.let { de ->
        androidx.compose.runtime.LaunchedEffect(desktopSetupState) {
            if (desktopSetupState is ProotManager.DesktopSetupState.Complete) {
                viewModel.dismissDesktopSetup()
                viewModel.resetDesktopSetupState()
            }
        }
        val activeFamily = DistroCatalog.lookup(activeDistroId)?.family
        val setupDraft by viewModel.desktopSetupDraft.collectAsState()
        DesktopSetupDialog(
            desktopState = desktopSetupState,
            selectedDe = de,
            activeFamily = activeFamily,
            draft = setupDraft,
            onDraftChange = { viewModel.setDesktopSetupDraft(it) },
            onStart = { addons -> viewModel.setupDesktop(de, addons) },
            onDismiss = {
                viewModel.dismissDesktopSetup()
                viewModel.resetDesktopSetupState()
            },
        )
    }
}

@Composable
private fun mirrorRegionLabel(r: MirrorRegion): String = when (r) {
    MirrorRegion.DEFAULT -> stringResource(AppR.string.app_desktop_mirror_default)
    MirrorRegion.EUROPE -> stringResource(AppR.string.app_desktop_mirror_europe)
    MirrorRegion.ASIA -> stringResource(AppR.string.app_desktop_mirror_asia)
    MirrorRegion.AMERICAS -> stringResource(AppR.string.app_desktop_mirror_americas)
}

/**
 * A tappable header that folds [content] away — collapsed by default — so the
 * accumulating Manage-screen options (mirror region, launch toggles, custom
 * mounts) don't push the distro picker + desktop list off the bottom.
 */
@Composable
private fun ExpandableSection(title: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
        )
    }
    if (expanded) {
        Column { content() }
    }
}

/** Global package-mirror region picker (#263), styled like the distro chip. */
@Composable
private fun MirrorRegionRow(region: MirrorRegion, onSelect: (MirrorRegion) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(
        stringResource(AppR.string.app_desktop_mirror_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        stringResource(AppR.string.app_desktop_mirror_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(mirrorRegionLabel(region)) },
            trailingIcon = {
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MirrorRegion.entries.forEach { r ->
                DropdownMenuItem(
                    text = { Text(mirrorRegionLabel(r)) },
                    leadingIcon = if (r == region) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        onSelect(r)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * Guest DNS selection (#446). Android has no /etc/resolv.conf, so Haven writes the
 * guest's; it used to hardcode public resolvers, which fails *silently* on networks
 * that only route DNS to their own resolver - installs just hang with no clue why.
 */
@Composable
private fun GuestDnsRow(
    mode: ProotDnsMode,
    servers: String,
    onSelectMode: (ProotDnsMode) -> Unit,
    onServersChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Text(
        stringResource(AppR.string.app_desktop_dns_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        stringResource(AppR.string.app_desktop_dns_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(dnsModeLabel(mode)) },
            trailingIcon = {
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ProotDnsMode.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(dnsModeLabel(candidate)) },
                    leadingIcon = if (candidate == mode) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        onSelectMode(candidate)
                        open = false
                    },
                )
            }
        }
    }
    if (mode == ProotDnsMode.CUSTOM) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = servers,
            onValueChange = onServersChange,
            label = { Text(stringResource(AppR.string.app_desktop_dns_custom_label)) },
            placeholder = { Text(stringResource(AppR.string.app_desktop_dns_custom_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun dnsModeLabel(mode: ProotDnsMode): String = when (mode) {
    ProotDnsMode.SYSTEM -> stringResource(AppR.string.app_desktop_dns_mode_system)
    ProotDnsMode.PUBLIC -> stringResource(AppR.string.app_desktop_dns_mode_public)
    ProotDnsMode.CUSTOM -> stringResource(AppR.string.app_desktop_dns_mode_custom)
}

/** A title + description + Switch row, styled like [MirrorRegionRow]. */
@Composable
private fun BindingToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DesktopManagerSection(
    installedDesktops: Set<ProotManager.DesktopEnvironment>,
    desktopStates: Map<ProotManager.DesktopEnvironment, DesktopManager.DesktopInstance>,
    desktopSetupState: ProotManager.DesktopSetupState,
    activeDistroId: String,
    installedDistros: List<Distro>,
    availableDistros: List<Distro>,
    availableForeignDistros: List<Pair<Distro, sh.haven.core.local.proot.Arch>>,
    rootfsSetupState: ProotManager.SetupState,
    isRootfsReady: Boolean,
    mirrorRegion: MirrorRegion,
    onSetMirrorRegion: (MirrorRegion) -> Unit,
    dnsMode: ProotDnsMode,
    onSetDnsMode: (ProotDnsMode) -> Unit,
    dnsServers: String,
    onSetDnsServers: (String) -> Unit,
    remapLowPorts: Boolean,
    onSetRemapLowPorts: (Boolean) -> Unit,
    shareStorageWithGuest: Boolean,
    onSetShareStorageWithGuest: (Boolean) -> Unit,
    bindAndroidSystem: Boolean,
    onSetBindAndroidSystem: (Boolean) -> Unit,
    customBindsRev: Int,
    customBindsFor: (String) -> List<sh.haven.core.local.proot.CustomBind>,
    onSetCustomBinds: (String, List<sh.haven.core.local.proot.CustomBind>) -> Unit,
    onImportRootfs: (String, String, sh.haven.core.local.proot.PackageFamily, String) -> Unit,
    usbDriveSessions: Map<String, sh.haven.app.usb.UsbDriveVmManager.Status>,
    onOpenUsbDrive: () -> Unit,
    onOpenUsbDriveWritable: () -> Unit,
    onCloseUsbDrive: (busid: String) -> Unit,
    usbLiveSessions: Map<String, sh.haven.app.usb.UmlRecoveryManager.Status>,
    onOpenUsbLive: () -> Unit,
    onOpenUsbLiveWritable: () -> Unit,
    onCloseUsbLive: (busid: String) -> Unit,
    onUnlockUsbDrivePartition: (busid: String, devicePath: String, passphrase: String) -> Unit,
    applianceProvisioned: Boolean,
    onDeleteUsbAppliance: () -> Unit,
    onSwitchDistro: (String) -> Unit,
    onOpenShellForDistro: (String) -> Unit,
    onAddDistro: (Distro) -> Unit,
    onAddForeignDistro: (Distro, sh.haven.core.local.proot.Arch) -> Unit,
    onDeleteDistro: (Distro) -> Unit,
    onInstall: (ProotManager.DesktopEnvironment) -> Unit,
    onStart: (ProotManager.DesktopEnvironment) -> Unit,
    onStop: (ProotManager.DesktopEnvironment) -> Unit,
    onOpenTerminalInDesktop: (ProotManager.DesktopEnvironment) -> Unit,
    onUninstall: (ProotManager.DesktopEnvironment) -> Unit,
    onRetryRootfs: () -> Unit,
    // Dialog drafts live in the ViewModel so a rotation cannot take a
    // half-filled dialog with it; this section owns several of them, and threading
    // a dozen state+callback parameters for that would be worse than the
    // coupling.
    viewModel: DesktopViewModel,
) {
    var distroMenuOpen by remember { mutableStateOf(false) }
    val showImportDialog by viewModel.showImportRootfs.collectAsState()
    val showCustomBindsDialog by viewModel.showCustomBinds.collectAsState()
    val showWritableConfirm by viewModel.showUsbWritableConfirm.collectAsState()
    // #379: which distro's delete is awaiting confirmation (null = none).
    // The delete IconButton sits one tap away from Open-shell, so guard the
    // destructive rootfs wipe behind a confirm dialog.
    val distroPendingDelete by viewModel.distroPendingDelete.collectAsState()
    // #620: which distro add is awaiting confirmation (null = none). The
    // "+ <name>" rows start a few-hundred-MB download on tap, so guard them
    // behind a confirm the same way the delete row is guarded above.
    val distroPendingAdd by viewModel.distroPendingAdd.collectAsState()
    // Which locked partition's unlock dialog is open (null = none) — the
    // owning session's busid + the mount-dir name (e.g. "sdb2").
    var unlockingPartition by remember { mutableStateOf<Pair<String, String>?>(null) }
    val usbDriveActiveCount = usbDriveSessions.values.count {
        it.phase == sh.haven.app.usb.UsbDriveVmManager.Phase.OPENING || it.phase == sh.haven.app.usb.UsbDriveVmManager.Phase.READY
    }
    val usbDriveAtCapacity = usbDriveActiveCount >= sh.haven.core.local.QemuManager.MAX_CONCURRENT_DRIVES

    val activeDistroLabel = installedDistros.firstOrNull { it.id == activeDistroId }?.label
        ?: DistroCatalog.lookup(activeDistroId)?.label
        ?: activeDistroId

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header so a user arriving on the (now always-visible) Desktop
            // tab understands this is the local-desktop install hub (#215).
            Text(
                stringResource(AppR.string.app_desktop_manager_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(AppR.string.app_desktop_manager_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (installedDistros.size > 1 || availableDistros.isNotEmpty()) {
                Box {
                    AssistChip(
                        onClick = { distroMenuOpen = true },
                        label = { Text(activeDistroLabel) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.DesktopWindows,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = distroMenuOpen,
                        onDismissRequest = { distroMenuOpen = false },
                    ) {
                        // Installed distros are NOT rendered as DropdownMenuItem:
                        // its trailingIcon slot is sized for a single decorative
                        // icon, and nesting interactive IconButtons there made the
                        // shell/delete buttons unhittable on non-first rows
                        // (GlassHaven/Haven#168 — confirmed dead on the 2nd row).
                        // A plain Row with three sibling tap targets (switch /
                        // open-shell / delete), each a full 48dp IconButton or a
                        // weighted clickable label, hit-tests reliably.
                        installedDistros.forEach { distro ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable {
                                            onSwitchDistro(distro.id)
                                            distroMenuOpen = false
                                        }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (distro.id == activeDistroId) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(distro.label)
                                }
                                // Open a proot shell in this distro (#168). Switches
                                // the active distro and opens a local-shell tab.
                                IconButton(onClick = {
                                    distroMenuOpen = false
                                    onOpenShellForDistro(distro.id)
                                }) {
                                    Icon(
                                        Icons.Filled.Terminal,
                                        contentDescription = stringResource(AppR.string.app_desktop_open_shell_cd, distro.label),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(onClick = {
                                    distroMenuOpen = false
                                    viewModel.setDistroPendingDelete(distro)
                                }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(AppR.string.app_desktop_delete_distro_cd, distro.label),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        if (availableDistros.isNotEmpty() && installedDistros.isNotEmpty()) {
                            HorizontalDivider()
                        }
                        availableDistros.forEach { distro ->
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(AppR.string.app_desktop_add_distro, distro.label, distro.sizeEstimateMb))
                                },
                                onClick = {
                                    viewModel.requestAddDistro(distro)
                                    distroMenuOpen = false
                                },
                            )
                        }
                        // Foreign-arch catalog entries — run under qemu-user
                        // emulation (#325); only offered when this build
                        // bundles the matching loader.
                        availableForeignDistros.forEach { (distro, arch) ->
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(AppR.string.app_desktop_add_foreign_distro, distro.label, arch.slug, distro.sizeEstimateMb))
                                },
                                onClick = {
                                    viewModel.requestAddForeignDistro(distro, arch)
                                    distroMenuOpen = false
                                },
                            )
                        }
                        // Import a custom rootfs (#284) — also the path to a
                        // second instance of a distro you already have (#302).
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(AppR.string.app_desktop_import_rootfs)) },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                distroMenuOpen = false
                                viewModel.openImportRootfs()
                            },
                        )
                        // #287: open a USB mass-storage drive in a VM so its
                        // ext4/GPT/block files are reachable (proot can't). Up
                        // to MAX_CONCURRENT_DRIVES at once — each is its own row
                        // below the picker, with its own Eject.
                        if (!usbDriveAtCapacity) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(AppR.string.app_desktop_open_usb_drive)) },
                                leadingIcon = { Icon(Icons.Filled.Usb, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    distroMenuOpen = false
                                    onOpenUsbDrive()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(AppR.string.app_desktop_open_usb_drive_writable)) },
                                leadingIcon = { Icon(Icons.Filled.Usb, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    distroMenuOpen = false
                                    viewModel.setShowUsbWritableConfirm(true)
                                },
                            )
                            // The fast route: the raw card goes straight to the
                            // UML recovery guest over NBD (ddrescue work) —
                            // seconds, not the VM's minutes. Read-only only in
                            // the UI; MCP's route:"guest" takes writable.
                            DropdownMenuItem(
                                text = { Text(stringResource(AppR.string.app_desktop_open_usb_live)) },
                                leadingIcon = { Icon(Icons.Filled.Usb, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    distroMenuOpen = false
                                    onOpenUsbLive()
                                },
                            )
                        }
                        // The helper Linux is provisioned once + kept; offer to
                        // delete it (reclaims ~280 MB, re-provisions next open).
                        if (applianceProvisioned && usbDriveActiveCount == 0) {
                            DropdownMenuItem(
                                text = { Text(stringResource(AppR.string.app_desktop_delete_usb_appliance)) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    distroMenuOpen = false
                                    onDeleteUsbAppliance()
                                },
                            )
                        }
                    }
                }
                // #287: one row per open (or opening/errored) USB-drive VM —
                // live progress while it boots (slow, so make it clear what's
                // happening rather than just spinning), mounts once ready, an
                // Unlock action per locked (LUKS) partition, and Eject.
                usbDriveSessions.entries.sortedBy { it.key }.forEach { (busid, s) ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                s.productName ?: s.deviceName ?: busid,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            when (s.phase) {
                                sh.haven.app.usb.UsbDriveVmManager.Phase.OPENING -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(AppR.string.app_desktop_usb_drive_progress, s.stage),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                sh.haven.app.usb.UsbDriveVmManager.Phase.READY -> Text(
                                    s.mounts.joinToString(", ").ifBlank { "—" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                sh.haven.app.usb.UsbDriveVmManager.Phase.ERROR -> Text(
                                    s.error ?: "Failed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                sh.haven.app.usb.UsbDriveVmManager.Phase.IDLE -> {}
                            }
                        }
                        TextButton(onClick = { onCloseUsbDrive(busid) }) {
                            Text(stringResource(AppR.string.app_desktop_eject_usb_drive))
                        }
                    }
                    // A LUKS-encrypted partition mounts locked — offer to unlock
                    // each one against the still-running VM (no reboot).
                    s.locked.forEach { name ->
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(AppR.string.app_desktop_usb_drive_locked, name),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { unlockingPartition = busid to name }) {
                                Text(stringResource(AppR.string.app_desktop_usb_drive_unlock))
                            }
                        }
                    }
                }
                // Live (fast route) sessions: the raw card exported over NBD to
                // the recovery guest. The rescue console is the transient
                // "USB: … (live)" connection's terminal tab.
                usbLiveSessions.entries.sortedBy { it.key }.forEach { (busid, s) ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                (s.productName ?: s.deviceName ?: busid) + " (live)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            when (s.phase) {
                                sh.haven.app.usb.UmlRecoveryManager.Phase.OPENING -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(AppR.string.app_desktop_usb_drive_progress, s.stage),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                sh.haven.app.usb.UmlRecoveryManager.Phase.READY -> Text(
                                    stringResource(
                                        if (s.readOnly) AppR.string.app_desktop_usb_live_readonly_note
                                        else AppR.string.app_desktop_usb_live_writable_note,
                                        s.capacity,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                sh.haven.app.usb.UmlRecoveryManager.Phase.ERROR -> Text(
                                    s.error ?: "Failed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                sh.haven.app.usb.UmlRecoveryManager.Phase.IDLE -> {}
                            }
                        }
                        TextButton(onClick = { onCloseUsbLive(busid) }) {
                            Text(stringResource(AppR.string.app_desktop_usb_live_close))
                        }
                    }
                }
                when (val s = rootfsSetupState) {
                    is ProotManager.SetupState.Downloading -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(AppR.string.app_desktop_rootfs_downloading, s.progress),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    ProotManager.SetupState.Extracting -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(AppR.string.app_desktop_rootfs_extracting),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is ProotManager.SetupState.Initializing -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(AppR.string.app_desktop_rootfs_initializing, s.step),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is ProotManager.SetupState.Error -> {
                        Spacer(Modifier.height(4.dp))
                        val phaseLabel = when (s.phase) {
                            ProotManager.Phase.RootfsDownload -> stringResource(AppR.string.app_desktop_phase_download)
                            ProotManager.Phase.RootfsExtract -> stringResource(AppR.string.app_desktop_phase_extract)
                            ProotManager.Phase.BootstrapHook -> stringResource(AppR.string.app_desktop_phase_bootstrap_hook)
                            ProotManager.Phase.Baseline -> stringResource(AppR.string.app_desktop_phase_baseline)
                        }
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(AppR.string.app_desktop_setup_failed_phase, phaseLabel), style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            s.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (s.logTail.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                s.logTail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 8,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(AppR.string.app_desktop_proot_log_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // Retry — re-runs the failing layer. For
                        // Download/Extract the underlying retry wipes
                        // the rootfs and starts over (partial state is
                        // the risk); for Hook/Baseline it re-runs
                        // hooks + baseline against the existing
                        // rootfs. Label reflects which path Retry
                        // will take.
                        val retryLabel = when (s.phase) {
                            ProotManager.Phase.RootfsDownload,
                            ProotManager.Phase.RootfsExtract -> stringResource(AppR.string.app_desktop_retry_wipe)
                            ProotManager.Phase.BootstrapHook,
                            ProotManager.Phase.Baseline -> stringResource(AppR.string.app_desktop_retry_step)
                        }
                        TextButton(onClick = onRetryRootfs) { Text(retryLabel) }
                    }
                    else -> { /* Ready / NotInstalled — silent */ }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Local-Linux options collapsed into one expandable section so they
            // don't push the distro picker + desktop list off the bottom of the
            // screen: the package-mirror region (#263) and the proot launch
            // toggles + custom mounts (#300 / #301). Collapsed by default.
            if (installedDistros.isNotEmpty()) {
                ExpandableSection(title = stringResource(AppR.string.app_desktop_options_section)) {
                    if (installedDistros.any { MirrorCatalog.hasMirrors(it.id) }) {
                        MirrorRegionRow(region = mirrorRegion, onSelect = onSetMirrorRegion)
                        Spacer(Modifier.height(8.dp))
                    }
                    GuestDnsRow(
                        mode = dnsMode,
                        servers = dnsServers,
                        onSelectMode = onSetDnsMode,
                        onServersChange = onSetDnsServers,
                    )
                    Spacer(Modifier.height(8.dp))
                    BindingToggleRow(
                        title = stringResource(AppR.string.app_desktop_remap_ports_title),
                        description = stringResource(AppR.string.app_desktop_remap_ports_description),
                        checked = remapLowPorts,
                        onCheckedChange = onSetRemapLowPorts,
                    )
                    Spacer(Modifier.height(8.dp))
                    BindingToggleRow(
                        title = stringResource(AppR.string.app_desktop_share_storage_title),
                        description = stringResource(AppR.string.app_desktop_share_storage_description),
                        checked = shareStorageWithGuest,
                        onCheckedChange = onSetShareStorageWithGuest,
                    )
                    Spacer(Modifier.height(8.dp))
                    BindingToggleRow(
                        title = stringResource(AppR.string.app_desktop_bind_android_system_title),
                        description = stringResource(AppR.string.app_desktop_bind_android_system_description),
                        checked = bindAndroidSystem,
                        onCheckedChange = onSetBindAndroidSystem,
                    )
                    Spacer(Modifier.height(8.dp))
                    // Custom bind mounts for the active distro (#301). Count reads
                    // through customBindsRev so it updates after the dialog saves.
                    val customBindCount = remember(activeDistroId, customBindsRev) {
                        customBindsFor(activeDistroId).size
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openCustomBinds(customBindsFor(activeDistroId)) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(AppR.string.app_desktop_custom_binds_title, customBindCount),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(AppR.string.app_desktop_custom_binds_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

        }
    }

    // Compositor — the local desktop environments (X11 Xfce4/Openbox, nested
    // Wayland/Sway, the native compositor) that run inside the active distro.
    // Its own card, separate from the distribution hub above, so each stays
    // compact (like the Application windows card).
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(AppR.string.app_desktop_compositor_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            // In-progress desktop-install indicator. The DesktopSetupDialog
            // normally shows this, but its "which DE" state is screen-local
            // `remember` and is lost when the user navigates away (e.g. to
            // the Terminal tab) and back while the install keeps running in
            // the ViewModel scope — leaving the rows dimmed with no
            // explanation, which reads as a silent failure. desktopSetupState
            // lives in ProotManager so it survives navigation; drive a
            // banner off it so the Manage screen always shows an install
            // that's still going.
            val deInstalling = desktopSetupState as? ProotManager.DesktopSetupState.Installing
            if (deInstalling != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        deInstalling.step.ifBlank {
                            stringResource(R.string.connections_desktop_installing)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            val activeDistro = DistroCatalog.lookup(activeDistroId)
            // Only native (labwc / Xwayland) desktops: the Xvnc / nested-Wayland
            // ones needed the VNC viewer, which has been removed.
            val compatibleDes = ProotManager.DesktopEnvironment.entries
                .filter { !it.hidden && it.isNative }
                .filter { de ->
                    activeDistro == null ||
                        de.spec.packagesPerFamily.containsKey(activeDistro.family)
                }
            compatibleDes.forEach { de ->
                val isInstalled = de in installedDesktops
                val instance = desktopStates[de]
                DesktopRow(
                    de = de,
                    isInstalled = isInstalled,
                    instance = instance,
                    // #502: only the desktop the install belongs to. This was
                    // "is anything installing", so starting one replaced every
                    // other row's controls with a spinner and they all read as
                    // having been started.
                    isSetupBusy = isInstallingThisDesktop(desktopSetupState, de),
                    isAnySetupBusy = desktopSetupState is ProotManager.DesktopSetupState.Installing,
                    activeFamily = activeDistro?.family,
                    isRootfsReady = isRootfsReady,
                    onInstall = { onInstall(de) },
                    onStart = { onStart(de) },
                    onStop = { onStop(de) },
                    onOpenTerminal = { onOpenTerminalInDesktop(de) },
                    onUninstall = { onUninstall(de) },
                )
            }
        }
    }

    if (showImportDialog) {
        val draft by viewModel.importRootfsDraft.collectAsState()
        ImportRootfsDialog(
            draft = draft,
            onDraftChange = { viewModel.setImportRootfsDraft(it) },
            onDismiss = { viewModel.dismissImportRootfs() },
            onImport = { id, label, family, source ->
                onImportRootfs(id, label, family, source)
                viewModel.dismissImportRootfs()
            },
        )
    }
    if (showCustomBindsDialog) {
        val rows by viewModel.customBindsDraft.collectAsState()
        CustomBindsDialog(
            distroLabel = activeDistroLabel,
            rows = rows,
            onRowsChange = { viewModel.setCustomBindsDraft(it) },
            onDismiss = { viewModel.dismissCustomBinds() },
            onSave = { binds ->
                onSetCustomBinds(activeDistroId, binds)
                viewModel.dismissCustomBinds()
            },
        )
    }
    if (showWritableConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowUsbWritableConfirm(false) },
            title = { Text(stringResource(AppR.string.app_desktop_open_usb_drive_writable_confirm_title)) },
            text = { Text(stringResource(AppR.string.app_desktop_open_usb_drive_writable_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setShowUsbWritableConfirm(false)
                    onOpenUsbDriveWritable()
                }) { Text(stringResource(AppR.string.app_desktop_open_usb_drive_writable)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setShowUsbWritableConfirm(false) }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    distroPendingDelete?.let { distro ->
        AlertDialog(
            onDismissRequest = { viewModel.setDistroPendingDelete(null) },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text(stringResource(AppR.string.app_desktop_delete_distro_confirm_title, distro.label)) },
            text = { Text(stringResource(AppR.string.app_desktop_delete_distro_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteDistro(distro)
                    viewModel.setDistroPendingDelete(null)
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setDistroPendingDelete(null) }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    distroPendingAdd?.let { pending ->
        val (distro, foreignArch) = when (pending) {
            is DesktopViewModel.PendingAdd.Native -> pending.distro to null
            is DesktopViewModel.PendingAdd.Foreign -> pending.distro to pending.arch
        }
        AlertDialog(
            onDismissRequest = { viewModel.dismissDistroPendingAdd() },
            icon = { Icon(Icons.Filled.Download, contentDescription = null) },
            title = { Text(stringResource(AppR.string.app_desktop_add_distro_confirm_title, distro.label)) },
            text = {
                Text(
                    if (foreignArch != null) {
                        stringResource(AppR.string.app_desktop_add_foreign_distro_confirm_body, foreignArch.slug, distro.sizeEstimateMb)
                    } else {
                        stringResource(AppR.string.app_desktop_add_distro_confirm_body, distro.sizeEstimateMb)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (pending) {
                        is DesktopViewModel.PendingAdd.Native -> onAddDistro(pending.distro)
                        is DesktopViewModel.PendingAdd.Foreign -> onAddForeignDistro(pending.distro, pending.arch)
                    }
                    viewModel.dismissDistroPendingAdd()
                }) { Text(stringResource(R.string.common_download)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDistroPendingAdd() }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    unlockingPartition?.let { (busid, name) ->
        UsbLuksUnlockDialog(
            partitionName = name,
            onDismiss = { unlockingPartition = null },
            onUnlock = { passphrase ->
                onUnlockUsbDrivePartition(busid, "/dev/$name", passphrase)
                unlockingPartition = null
            },
        )
    }
}

@Composable
private fun UsbLuksUnlockDialog(
    partitionName: String,
    onDismiss: () -> Unit,
    onUnlock: (passphrase: String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AppR.string.app_desktop_usb_drive_unlock_title, partitionName)) },
        text = {
            sh.haven.core.ui.PasswordField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = stringResource(AppR.string.app_desktop_usb_drive_unlock_passphrase),
                imeAction = ImeAction.Go,
                onImeAction = { if (passphrase.isNotEmpty()) onUnlock(passphrase) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onUnlock(passphrase) }, enabled = passphrase.isNotEmpty()) {
                Text(stringResource(AppR.string.app_desktop_usb_drive_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun ImportRootfsDialog(
    draft: DesktopViewModel.ImportRootfsDraft,
    onDraftChange: (DesktopViewModel.ImportRootfsDraft) -> Unit,
    onDismiss: () -> Unit,
    onImport: (String, String, sh.haven.core.local.proot.PackageFamily, String) -> Unit,
) {
    // Stateless: the draft lives in DesktopViewModel so a rotation cannot wipe
    // a half-typed import. familyMenuOpen stays local — it is transient UI, and
    // a menu that closes on rotation is the expected behaviour anyway.
    val id = draft.id
    val label = draft.label
    val source = draft.source
    val family = draft.family
    var familyMenuOpen by remember { mutableStateOf(false) }
    val valid = id.isNotBlank() && source.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AppR.string.app_desktop_import_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(AppR.string.app_desktop_import_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = id,
                    onValueChange = { onDraftChange(draft.copy(id = it.trim())) },
                    singleLine = true,
                    label = { Text(stringResource(AppR.string.app_desktop_import_id_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { onDraftChange(draft.copy(label = it)) },
                    singleLine = true,
                    label = { Text(stringResource(AppR.string.app_desktop_import_label_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = source,
                    onValueChange = { onDraftChange(draft.copy(source = it.trim())) },
                    singleLine = true,
                    label = { Text(stringResource(AppR.string.app_desktop_import_source_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    AssistChip(
                        onClick = { familyMenuOpen = true },
                        label = { Text("${stringResource(AppR.string.app_desktop_import_family)}: ${family.name}") },
                        trailingIcon = { Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                    DropdownMenu(expanded = familyMenuOpen, onDismissRequest = { familyMenuOpen = false }) {
                        sh.haven.core.local.proot.PackageFamily.entries.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.name) },
                                onClick = { onDraftChange(draft.copy(family = f)); familyMenuOpen = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(id, label, family, source) }, enabled = valid) {
                Text(stringResource(AppR.string.app_desktop_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(AppR.string.app_desktop_cancel)) }
        },
    )
}

@Composable
private fun CustomBindsDialog(
    distroLabel: String,
    rows: List<Pair<String, String>>,
    onRowsChange: (List<Pair<String, String>>) -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<sh.haven.core.local.proot.CustomBind>) -> Unit,
) {
    // (host, guest) pairs held by DesktopViewModel, replaced wholesale on each
    // edit rather than mutated in place — a rotation used to discard the whole
    // in-place list along with the dialog.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AppR.string.app_desktop_custom_binds_dialog_title, distroLabel)) },
        text = {
            Column {
                if (rows.isEmpty()) {
                    Text(
                        stringResource(AppR.string.app_desktop_custom_binds_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rows.forEachIndexed { i, (host, guest) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = host,
                                onValueChange = { v ->
                                    onRowsChange(rows.toMutableList().also { it[i] = v.trim() to it[i].second })
                                },
                                singleLine = true,
                                label = { Text(stringResource(AppR.string.app_desktop_custom_binds_host_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = guest,
                                onValueChange = { v ->
                                    onRowsChange(rows.toMutableList().also { it[i] = it[i].first to v.trim() })
                                },
                                singleLine = true,
                                label = { Text(stringResource(AppR.string.app_desktop_custom_binds_guest_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        IconButton(onClick = { onRowsChange(rows.filterIndexed { idx, _ -> idx != i }) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(AppR.string.app_desktop_custom_binds_remove_cd),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                TextButton(onClick = { onRowsChange(rows + ("" to "")) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(AppR.string.app_desktop_custom_binds_add))
                }
                Text(
                    stringResource(AppR.string.app_desktop_custom_binds_takes_effect),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val binds = rows
                    .map { sh.haven.core.local.proot.CustomBind(it.first.trim(), it.second.trim()) }
                    .filter { it.host.isNotEmpty() }
                onSave(binds)
            }) { Text(stringResource(AppR.string.app_desktop_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(AppR.string.app_desktop_cancel)) }
        },
    )
}

/**
 * Whether the desktop-setup work currently running belongs to [de].
 *
 * #502: the Manage screen used to ask only "is anything installing", and every
 * row read the same answer — start one desktop and all of them swapped their
 * controls for a spinner, which looks exactly like all of them having been
 * started. Add-on installs belong to no desktop and answer false for every row.
 */
internal fun isInstallingThisDesktop(
    state: ProotManager.DesktopSetupState?,
    de: ProotManager.DesktopEnvironment,
): Boolean = (state as? ProotManager.DesktopSetupState.Installing)?.de == de

@Composable
private fun DesktopRow(
    de: ProotManager.DesktopEnvironment,
    isInstalled: Boolean,
    instance: DesktopManager.DesktopInstance?,
    /** An install is running *for this desktop* — show its progress here. */
    isSetupBusy: Boolean = false,
    /**
     * An install is running for some desktop, this one or another. Installs
     * share one package database, so a second one started alongside would
     * collide on its lock — every Install button stays disabled while any is
     * running, even though only the row being installed shows a spinner (#502).
     */
    isAnySetupBusy: Boolean = false,
    activeFamily: PackageFamily? = null,
    isRootfsReady: Boolean = true,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenTerminal: () -> Unit,
    onUninstall: () -> Unit,
) {
    val compatibility = activeFamily?.let { de.spec.compatibilityOn(it) }
        ?: Compatibility.Stable
    var showUninstallConfirm by remember { mutableStateOf(false) }

    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text(stringResource(R.string.connections_desktop_uninstall_title)) },
            text = { Text(stringResource(R.string.connections_desktop_uninstall_message, de.label)) },
            confirmButton = {
                TextButton(onClick = { showUninstallConfirm = false; onUninstall() }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Icon(
            Icons.Filled.Circle,
            contentDescription = null,
            tint = when (instance?.state) {
                DesktopManager.DesktopState.RUNNING -> Color(0xFF4CAF50)
                DesktopManager.DesktopState.STARTING -> Color(0xFFFFC107)
                DesktopManager.DesktopState.ERROR -> Color(0xFFF44336)
                else -> MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(de.label, style = MaterialTheme.typography.bodyMedium)
                if (compatibility == Compatibility.Experimental) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(AppR.string.app_desktop_experimental),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            when {
                instance?.state == DesktopManager.DesktopState.RUNNING ->
                    Text(
                        stringResource(R.string.connections_desktop_native_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                instance?.state == DesktopManager.DesktopState.ERROR ->
                    Text(
                        instance.errorMessage ?: stringResource(AppR.string.app_desktop_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                !isInstalled ->
                    Text(
                        de.sizeEstimate +
                            if (!isRootfsReady) stringResource(AppR.string.app_desktop_install_distro_first) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }

        if (!isInstalled) {
            // Disabled until rootfs reaches Ready — the install path
            // calls into ProotManager.setupDesktop which silently
            // installs the rootfs if missing; we'd rather make the
            // dependency obvious. The subtitle above carries the
            // "install distro first" hint.
            TextButton(onClick = onInstall, enabled = !isAnySetupBusy && isRootfsReady) {
                Text(stringResource(R.string.common_install))
            }
        } else if (isSetupBusy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            when (instance?.state) {
                DesktopManager.DesktopState.RUNNING -> {
                    IconButton(onClick = onOpenTerminal) {
                        Icon(
                            Icons.Filled.Terminal,
                            contentDescription = stringResource(AppR.string.app_desktop_open_terminal_cd, de.label),
                        )
                    }
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.connections_desktop_stop))
                    }
                }
                DesktopManager.DesktopState.STARTING ->
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> {
                    IconButton(onClick = onStart) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.connections_desktop_start))
                    }
                    IconButton(onClick = { showUninstallConfirm = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.common_delete),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopSetupDialog(
    desktopState: ProotManager.DesktopSetupState,
    selectedDe: ProotManager.DesktopEnvironment,
    activeFamily: PackageFamily? = null,
    draft: DesktopViewModel.DesktopSetupDraft,
    onDraftChange: (DesktopViewModel.DesktopSetupDraft) -> Unit,
    onStart: (addons: Set<ProotManager.DesktopAddon>) -> Unit,
    onDismiss: () -> Unit,
) {
    val compatibility = activeFamily?.let { selectedDe.spec.compatibilityOn(it) }
        ?: Compatibility.Stable
    val compatibilityNote = activeFamily?.let { selectedDe.spec.compatibilityNoteOn(it) }
    // Stateless: the draft lives in DesktopViewModel.
    val shellCmd = draft.shellCmd
    val selectedAddons = draft.addons
    val isInstalling = desktopState is ProotManager.DesktopSetupState.Installing

    AlertDialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        title = { Text(stringResource(R.string.connections_desktop_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (desktopState) {
                    is ProotManager.DesktopSetupState.Idle -> {
                        Text(
                            stringResource(AppR.string.app_desktop_de_with_size, selectedDe.label, selectedDe.sizeEstimate),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (compatibility == Compatibility.Experimental && compatibilityNote != null) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        stringResource(AppR.string.app_desktop_experimental_on, activeFamily.name),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        compatibilityNote,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.connections_desktop_wayland_description),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (selectedDe.isNative) {
                            var shellExpanded by remember { mutableStateOf(false) }
                            val shellOptions = listOf("/bin/sh", "/bin/ash", "/bin/bash", "/bin/zsh", "/bin/fish")
                            ExposedDropdownMenuBox(
                                expanded = shellExpanded,
                                onExpandedChange = { shellExpanded = it },
                            ) {
                                OutlinedTextField(
                                    value = shellCmd,
                                    onValueChange = { onDraftChange(draft.copy(shellCmd = it)) },
                                    label = { Text(stringResource(R.string.connections_desktop_shell)) },
                                    singleLine = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = shellExpanded) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                )
                                ExposedDropdownMenu(
                                    expanded = shellExpanded,
                                    onDismissRequest = { shellExpanded = false },
                                ) {
                                    shellOptions.forEach { shell ->
                                        DropdownMenuItem(
                                            text = { Text(shell) },
                                            onClick = {
                                                onDraftChange(draft.copy(shellCmd = shell))
                                                shellExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.connections_desktop_addons_header),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            ProotManager.DesktopAddon.entries.forEach { addon ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Checkbox(
                                        checked = addon in selectedAddons,
                                        onCheckedChange = { checked ->
                                            onDraftChange(
                                                draft.copy(
                                                    addons = if (checked) selectedAddons + addon
                                                    else selectedAddons - addon,
                                                ),
                                            )
                                        },
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(addon.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            stringResource(AppR.string.app_desktop_addon_with_size, addon.description, addon.sizeEstimate),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is ProotManager.DesktopSetupState.Installing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                desktopState.step,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is ProotManager.DesktopSetupState.Complete -> {
                        Text(stringResource(R.string.connections_desktop_installed))
                    }
                    is ProotManager.DesktopSetupState.Error -> {
                        val phaseLabel = when (desktopState.phase) {
                            ProotManager.DePhase.Packages -> stringResource(AppR.string.app_desktop_phase_packages)
                            ProotManager.DePhase.VncConfig -> stringResource(AppR.string.app_desktop_phase_vnc_config)
                            ProotManager.DePhase.Marker -> stringResource(AppR.string.app_desktop_phase_marker)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AssistChip(
                                onClick = {},
                                label = { Text(stringResource(AppR.string.app_desktop_setup_failed_phase, phaseLabel), style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            )
                            Text(
                                stringResource(R.string.connections_desktop_setup_failed, desktopState.message),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (desktopState.logTail.isNotEmpty()) {
                                Text(
                                    desktopState.logTail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 10,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Text(
                                stringResource(AppR.string.app_desktop_proot_log_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (desktopState is ProotManager.DesktopSetupState.Idle) {
                TextButton(onClick = { onStart(selectedAddons) }) {
                    Text(stringResource(R.string.common_install))
                }
            }
        },
        dismissButton = {
            if (!isInstalling) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
