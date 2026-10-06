package sh.haven.core.data.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** What kind of media the agent wants the user to perceive. */
enum class PresentedMediaKind {
    IMAGE,
    AUDIO,

    /**
     * HTML / SVG / PDF shown inline. HTML and SVG load from a loopback-served
     * URL ([PresentedMedia.url]) in an in-app WebView; a PDF is downloaded to
     * a cache file ([PresentedMedia.filePath]) and paged via PdfRenderer
     * (which needs a local fd).
     */
    WEB,
}

/**
 * One thing an agent has pushed for the user to look at / listen to /
 * interact with. For IMAGE/AUDIO the bytes live in a cache file
 * ([filePath]) — cheap to keep in the StateFlow and what the image decoder
 * / audio player want.
 */
data class PresentedMedia(
    val id: Long,
    val kind: PresentedMediaKind,
    /** IMAGE/AUDIO: absolute cache-file path the UI reads/decodes/plays. WEB: a downloaded PDF cache file. */
    val filePath: String? = null,
    /** WEB: loopback-served URL for HTML/SVG content (a PDF uses [filePath] instead). */
    val url: String? = null,
    val mimeType: String? = null,
    /** Optional one-line caption shown above the media. */
    val caption: String? = null,
    /** Audio only: start playback as soon as the sheet appears. */
    val autoPlay: Boolean = false,
    val presentedAt: Long = System.currentTimeMillis(),
)

/**
 * The agent → user "here, look at / listen to this" channel.
 *
 * Unlike [AgentUiCommandBus] (fire-and-forget navigation *commands* with
 * `replay = 0`), a presented image or sound is **state**: it must stay on
 * screen until the user dismisses it, and survive a recomposition or a
 * brief backgrounding. So this mirrors [AgentConsentManager]'s shape — a
 * `@Singleton` holding a [StateFlow] queue the top-of-tree host renders.
 *
 * It is deliberately *not* a consent gate. Showing an image is not a
 * destructive act, so [present] never suspends and never asks: the agent
 * calls it and returns immediately; the overlay is itself the user-facing
 * artifact, and is freely dismissible. The user keeps the wheel by
 * dismissing, not by pre-approving.
 *
 * ### Backing files
 *
 * Each entry owns a cache file written by the caller. [dismiss] deletes
 * it. To bound disk use against a chatty agent the queue is capped at
 * [MAX_QUEUE]; pushing past the cap drops (and deletes the file of) the
 * oldest entry.
 */
@Singleton
class AgentPresentationManager @Inject constructor() {

    private val nextId = AtomicLong(1)

    private val _pending = MutableStateFlow<List<PresentedMedia>>(emptyList())
    /** All currently-showing media, oldest first. Drives the presentation host. */
    val pending: StateFlow<List<PresentedMedia>> = _pending.asStateFlow()

    private val _minimizedIds = MutableStateFlow<Set<Long>>(emptySet())
    /**
     * Ids of entries the user has backgrounded to an edge icon. They stay in
     * [pending] but the host skips rendering them; the edge dock renders an
     * icon per id.
     */
    val minimizedIds: StateFlow<Set<Long>> = _minimizedIds.asStateFlow()

    /** Background an entry to an edge icon. */
    fun minimize(id: Long) {
        _minimizedIds.value = _minimizedIds.value + id
    }

    /** Restore a backgrounded entry, moving it to the front of [pending]. */
    fun restore(id: Long) {
        _minimizedIds.value = _minimizedIds.value - id
        val item = _pending.value.firstOrNull { it.id == id } ?: return
        _pending.value = listOf(item) + _pending.value.filterNot { it.id == id }
    }

    /**
     * Enqueue [filePath] for the user to see/hear. Non-blocking; returns
     * the assigned id so a caller could correlate a later [dismiss] if it
     * wanted to. The file at [filePath] must already exist and is owned by
     * this manager from here on — it is deleted on dismissal / eviction.
     */
    fun present(
        kind: PresentedMediaKind,
        filePath: String,
        mimeType: String,
        caption: String?,
        autoPlay: Boolean = false,
    ): Long = enqueue(
        PresentedMedia(
            id = nextId.getAndIncrement(),
            kind = kind,
            filePath = filePath,
            mimeType = mimeType,
            caption = caption,
            autoPlay = autoPlay,
        ),
    )

    /**
     * Enqueue web content for the user to view inline: HTML/SVG via a
     * loopback-served [url] in a WebView, or a PDF via a downloaded cache
     * [filePath] paged by PdfRenderer. Non-blocking; returns the assigned id.
     * A PDF [filePath] is owned by this manager from here on (deleted on
     * dismissal / eviction); a served [url] is reclaimed by the stream
     * server's TTL.
     */
    fun presentWeb(
        url: String?,
        filePath: String?,
        mimeType: String?,
        caption: String?,
    ): Long = enqueue(
        PresentedMedia(
            id = nextId.getAndIncrement(),
            kind = PresentedMediaKind.WEB,
            url = url,
            filePath = filePath,
            mimeType = mimeType,
            caption = caption,
        ),
    )

    private fun enqueue(item: PresentedMedia): Long {
        val next = _pending.value + item
        if (next.size > MAX_QUEUE) {
            // Evict and delete the backing file of the oldest entries so a
            // misbehaving agent can't fill the cache.
            val evicted = next.subList(0, next.size - MAX_QUEUE)
            evicted.forEach { it.filePath?.let { p -> runCatching { File(p).delete() } } }
            _pending.value = next.subList(next.size - MAX_QUEUE, next.size).toList()
        } else {
            _pending.value = next
        }
        return item.id
    }

    /**
     * Called by the UI when the user dismisses an item (taps Dismiss or
     * swipes the sheet away). Removes it from the queue and deletes its
     * backing cache file, if any.
     */
    fun dismiss(id: Long) {
        val current = _pending.value
        val item = current.firstOrNull { it.id == id }
        _pending.value = current.filterNot { it.id == id }
        _minimizedIds.value = _minimizedIds.value - id
        item?.filePath?.let { runCatching { File(it).delete() } }
    }

    private companion object {
        const val MAX_QUEUE = 8
    }
}
