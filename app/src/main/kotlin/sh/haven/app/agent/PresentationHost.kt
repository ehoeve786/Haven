package sh.haven.app.agent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import sh.haven.app.MainActivity
import sh.haven.app.R
import sh.haven.core.data.agent.AgentPresentationManager
import sh.haven.core.data.agent.PresentedMedia
import sh.haven.core.data.agent.PresentedMediaKind
import java.io.File
import javax.inject.Inject

/** Max PDF pages [PdfContent] rasterises (bounds memory for a chatty agent). */
private const val MAX_PDF_PAGES = 20

/**
 * Thin Hilt + Compose wrapper around the app-scoped
 * [AgentPresentationManager], mirroring [ConsentHostViewModel].
 */
@HiltViewModel
internal class PresentationHostViewModel @Inject constructor(
    private val manager: AgentPresentationManager,
    private val pipController: PipController,
) : ViewModel() {
    val pending: StateFlow<List<PresentedMedia>> = manager.pending
    val minimizedIds: StateFlow<Set<Long>> = manager.minimizedIds

    /** Background an item to an edge icon. */
    fun minimize(id: Long) = manager.minimize(id)

    /** Restore a backgrounded item to the full overlay. */
    fun restore(id: Long) = manager.restore(id)

    /** Tell the PiP layer which presented item (if any) is currently on screen. */
    fun setActivePipMedia(media: PresentedMedia?) = pipController.setActivePipMedia(media)

    /** Dismiss a presented item. */
    fun dismiss(media: PresentedMedia) {
        manager.dismiss(media.id)
    }
}

/**
 * Top-of-tree host for agent-pushed media. Mounted from
 * `MainActivity.setContent { ... }` next to [ConsentHost] so an image or
 * sound the agent shares floats above whatever screen is active.
 *
 * Renders the **oldest** pending [PresentedMedia]; when the user dismisses
 * it the next (if any) slides in. Unlike the consent sheet this is freely
 * dismissible — showing an image isn't a gate, so a tap-outside or swipe
 * is a perfectly good "I'm done looking".
 *
 * The displayed-snapshot indirection is the same defence ConsentHost uses:
 * tearing a ModalBottomSheet out of composition the instant the manager
 * clears the item leaves a stuck full-screen scrim. We instead animate the
 * sheet out via `sheetState.hide()` and drop the snapshot only once it's
 * gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresentationHost(viewModel: PresentationHostViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val minimized by viewModel.minimizedIds.collectAsStateWithLifecycle()
    // Render the focused item: the oldest pending one that isn't backgrounded.
    val upstream = pending.firstOrNull { it.id !in minimized }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var displayed by remember { mutableStateOf<PresentedMedia?>(null) }
    LaunchedEffect(upstream?.id) {
        val u = upstream
        if (u != null) {
            displayed = u
            if (!sheetState.isVisible) sheetState.show()
        } else if (displayed != null) {
            runCatching { sheetState.hide() }
            displayed = null
        }
        // Tell the PiP layer which item is on screen (for the floating view).
        // Image / web are PiP-eligible;
        // AUDIO has no visual surface so it is excluded. Cleared when nothing is
        // shown. NOT cleared on dispose — an overlay→PiP transition disposes this
        // host but the item must stay PiP-active. (#225)
        viewModel.setActivePipMedia(u?.takeIf { it.kind != PresentedMediaKind.AUDIO })
    }
    val current = displayed ?: return

    ModalBottomSheet(
        onDismissRequest = { viewModel.dismiss(current) },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            // Passive visual kinds (image, web page, PDF) can promote to an
            // immersive full-window Dialog. The toggle lives in the HEADER next
            // to ✕ — not overlaid on the content, which obscured the page — so
            // fullscreen state is hoisted here for the header to drive.
            val canFullscreen = current.kind == PresentedMediaKind.IMAGE ||
                current.kind == PresentedMediaKind.WEB
            var fullscreen by rememberSaveable(current.id) { mutableStateOf(false) }

            // Header: caption + fullscreen + an explicit ✕. The sheet's drag-handle
            // pill alone reads as "minimize" to users; close must look like close.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = current.caption.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (canFullscreen) {
                    IconButton(onClick = { fullscreen = true }) {
                        Icon(
                            Icons.Filled.Fullscreen,
                            contentDescription = stringResource(R.string.app_present_fullscreen),
                        )
                    }
                }
                IconButton(onClick = { viewModel.dismiss(current) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.app_present_dismiss),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            when (current.kind) {
                PresentedMediaKind.IMAGE -> FullscreenableContent(
                    fullscreen = fullscreen,
                    onExitFullscreen = { fullscreen = false },
                ) { modifier -> ImageView(current, modifier) }
                PresentedMediaKind.AUDIO -> AudioContent(current)
                PresentedMediaKind.WEB -> WebContent(
                    current,
                    fullscreen = fullscreen,
                    onExitFullscreen = { fullscreen = false },
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                // Picture-in-picture floats the item over *other* apps (unlike
                // minimize, which only docks within Haven). Images and web
                // pages/PDFs are visual; audio has nothing to float. (#225)
                if (current.kind == PresentedMediaKind.IMAGE ||
                    current.kind == PresentedMediaKind.WEB
                ) {
                    val context = LocalContext.current
                    OutlinedButton(onClick = {
                        // Pin the active PiP item to what's on screen *now* — the
                        // LaunchedEffect that normally tracks it is fragile across
                        // dismiss/re-present and PiP enter/exit cycles, so set it
                        // synchronously here so enterPipForMedia never sees null. (#225)
                        viewModel.setActivePipMedia(current)
                        (context.findActivity() as? MainActivity)?.enterPipForMedia()
                    }) {
                        Text(stringResource(R.string.app_present_pip))
                    }
                }
                // Minimize parks it as an edge icon (kept until dismissed) so
                // the user can glance back at an image/page while they work.
                // Dismiss lives as the header's ✕ (users read the drag pill
                // as minimize, so close needs an unambiguous ✕ up top).
                OutlinedButton(onClick = { viewModel.minimize(current.id) }) {
                    Text(stringResource(R.string.app_present_minimize))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ImageView(media: PresentedMedia, modifier: Modifier = Modifier) {
    val path = media.filePath ?: return
    // Decode off the main thread; null until ready / on failure.
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                .getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        // Pinch-zoom + pan. Pan is enabled only past 1× and bounded to the
        // scaled overflow so the image can't be flung out of its card; a
        // double-tap toggles 1×↔2×. detectTransformGestures consumes the
        // drags, so a zoomed image pans cleanly rather than fighting the
        // bottom sheet's swipe-to-dismiss (dismiss stays on the X / tap-out).
        var scale by remember(media.id) { mutableStateOf(1f) }
        var offset by remember(media.id) { mutableStateOf(Offset.Zero) }
        Image(
            bitmap = bmp,
            contentDescription = media.caption ?: stringResource(R.string.app_present_image_shared_cd),
            // FillWidth scales the bitmap up/down to the card width (height
            // follows aspect ratio) so a small image
            // is shown prominently rather than as a tiny centred dot, while
            // a large screenshot is fit to width. Tall images crop centred.
            contentScale = ContentScale.Fit,
            modifier = modifier
                .clipToBounds()
                .pointerInput(media.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (newScale > 1f) {
                            val maxX = size.width * (newScale - 1f) / 2f
                            val maxY = size.height * (newScale - 1f) / 2f
                            Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                        } else {
                            Offset.Zero
                        }
                        scale = newScale
                    }
                }
                .pointerInput(media.id) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2f
                            }
                        },
                    )
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            // No bitmap yet means either still decoding or undecodable. We
            // can't tell them apart here without extra state; a spinner is
            // the honest default and resolves to the image when it lands.
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun AudioContent(media: PresentedMedia) {
    val path = media.filePath ?: return
    var playing by remember(media.id) { mutableStateOf(false) }
    var ready by remember(media.id) { mutableStateOf(false) }

    val player = remember(media.id) {
        MediaPlayer().apply {
            runCatching {
                setDataSource(path)
                setOnPreparedListener {
                    ready = true
                    if (media.autoPlay) {
                        it.start()
                        playing = true
                    }
                }
                setOnCompletionListener { playing = false }
                prepareAsync()
            }
        }
    }
    DisposableEffect(player) {
        onDispose { runCatching { player.release() } }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            enabled = ready,
            onClick = {
                if (playing) {
                    runCatching { player.pause() }
                    playing = false
                } else {
                    runCatching { player.start() }
                    playing = true
                }
            },
        ) {
            Text(if (playing) stringResource(R.string.app_present_pause) else stringResource(R.string.app_present_play))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (ready) File(path).name else stringResource(R.string.app_present_preparing_audio),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Web content: HTML/SVG loaded from a loopback-served [PresentedMedia.url] in
 * an in-app WebView (cleartext to 127.0.0.1 is permitted by the network
 * security config), or a downloaded PDF ([PresentedMedia.filePath]) paged via
 * [PdfContent]. The rung between a static image and a live app window.
 */
@Composable
private fun WebContent(
    media: PresentedMedia,
    fullscreen: Boolean,
    onExitFullscreen: () -> Unit,
) {
    val pdfPath = media.filePath
    if (media.mimeType == "application/pdf" && pdfPath != null) {
        FullscreenableContent(fullscreen, onExitFullscreen) { modifier ->
            PdfContent(pdfPath, modifier)
        }
        return
    }
    val url = media.url ?: return
    // key() so a new URL (a different presented item reusing this node) forces
    // a fresh WebView + load rather than keeping the old page. The WebView
    // rides movableContentOf inside FullscreenableContent so the sheet↔Dialog
    // re-parent moves the SAME view — heavy pages (code-server) don't reload.
    key(url) {
        FullscreenableContent(fullscreen, onExitFullscreen) { modifier ->
            WebPageView(url, modifier)
        }
    }
}

/**
 * Render passive presented content (image / web / PDF) in the sheet's 480dp
 * box, or — when [fullscreen] — promote it to an immersive full-window Dialog,
 * the same affordance app windows get. Controlled: the enter-fullscreen button
 * lives in the sheet HEADER (so it doesn't obscure the content); this owns only
 * the EXIT affordance (the immersive Dialog has no header) plus back-press.
 * [content] renders through movableContentOf, so the SAME node is re-parented
 * sheet↔Dialog — no reload / state loss on toggle.
 */
@Composable
private fun FullscreenableContent(
    fullscreen: Boolean,
    onExitFullscreen: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val movable = remember { movableContentOf { m: Modifier -> content(m) } }
    if (fullscreen) {
        Dialog(
            onDismissRequest = onExitFullscreen,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = true,
            ),
        ) {
            val dialogView = LocalView.current
            LaunchedEffect(dialogView) {
                val w = (dialogView.parent as? DialogWindowProvider)?.window
                    ?: return@LaunchedEffect
                WindowCompat.getInsetsController(w, dialogView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                movable(Modifier.fillMaxSize())
                FullscreenToggle(true, onExitFullscreen, Modifier.align(Alignment.TopEnd))
            }
        }
        // Opaque dialog covers this; keep the sheet height stable.
        Box(modifier = Modifier.fillMaxWidth().height(480.dp))
    } else {
        // In-sheet: content only. The enter-fullscreen button is in the header.
        Box(modifier = Modifier.fillMaxWidth().height(480.dp)) {
            movable(Modifier.fillMaxSize())
        }
    }
}

/**
 * Exit-fullscreen affordance overlaid on the immersive Dialog's top-right (the
 * Dialog has no header to host it; enter-fullscreen lives in the sheet header).
 * The content behind can be any colour, so the white glyph sits on a
 * translucent dark scrim — otherwise it vanished on a light page.
 */
@Composable
private fun FullscreenToggle(
    fullscreen: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
    ) {
        Icon(
            Icons.Filled.FullscreenExit,
            contentDescription = stringResource(R.string.app_present_exit_fullscreen),
            tint = Color.White,
        )
    }
}

/** The WebView itself — hoisted so movableContentOf can re-parent it. */
@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
@Composable
private fun WebPageView(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    // JS + DOM storage are off by default in WebView — without
                    // them any scripted page (code-server, dashboards, even a
                    // chart in presented HTML) renders blank white. No JS
                    // bridge is added, so page script stays sandboxed to web
                    // content exactly as in a browser.
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    // Keep the bottom sheet from stealing pan/zoom drags inside
                    // the page: claim the gesture from the parent on touch-down,
                    // release it on up/cancel. Without this the sheet intercepts
                    // vertical drags and panning a zoomed page feels broken.
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_POINTER_DOWN ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL ->
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false // never consume — WebView handles scroll/zoom/links
                    }
                    loadUrl(url)
                }
        },
    )
}

/**
 * Render a downloaded PDF to a vertical run of page bitmaps via
 * [android.graphics.pdf.PdfRenderer] (which needs a seekable local fd, hence
 * the cache download rather than a served URL). Mirrors [ImageContent]'s
 * "spinner until ready / on failure" honesty — null or empty both show the
 * spinner. Capped at [MAX_PDF_PAGES].
 */
@Composable
private fun PdfContent(path: String, modifier: Modifier = Modifier) {
    val pages by produceState<List<ImageBitmap>?>(initialValue = null, path) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    PdfRenderer(pfd).use { renderer ->
                        val count = renderer.pageCount.coerceAtMost(MAX_PDF_PAGES)
                        (0 until count).map { i ->
                            renderer.openPage(i).use { page ->
                                val w = 1080
                                val h = (w.toFloat() * page.height / page.width)
                                    .toInt().coerceAtLeast(1)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp.asImageBitmap()
                            }
                        }
                    }
                } finally {
                    runCatching { pfd.close() }
                }
            }.getOrNull()
        }
    }
    val list = pages
    if (list.isNullOrEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    } else {
        Column(
            modifier = modifier
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            list.forEach { pg ->
                Image(
                    bitmap = pg,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}

/** Unwrap a Compose [Context] to its hosting [Activity], or null. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
