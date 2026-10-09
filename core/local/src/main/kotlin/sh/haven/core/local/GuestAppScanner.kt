package sh.haven.core.local

import android.util.Log
import java.io.File
import java.security.MessageDigest

/** One installed GUI app discovered from a guest `.desktop` entry. */
data class InstalledApp(
    val name: String,
    /** Runnable guest command (Exec= with field codes stripped). */
    val exec: String,
    /** Absolute HOST path to a decodable PNG icon, or null (UI shows a placeholder). */
    val iconPath: String?,
    val categories: List<String>,
)

/** A scan's apps plus how many had a resolvable icon (reported honestly to the UI/MCP). */
data class AppScanResult(
    val apps: List<InstalledApp>,
    val iconsResolved: Int,
    val total: Int,
)

/**
 * Pure parsing / icon-path resolution for freedesktop `.desktop` entries — no
 * Android, no I/O beyond [File] existence checks — so it unit-tests directly.
 */
object DesktopEntryParser {

    /** A parsed `[Desktop Entry]` we'd surface as a launchable app. */
    data class Entry(
        val name: String,
        val exec: String,
        val icon: String?,
        val categories: List<String>,
    )

    // Exec field codes (Desktop Entry spec §Exec). %% is a literal percent and
    // is handled separately so its '%' isn't consumed as a code.
    private val FIELD_CODE = Regex("""%[uUfFdDnNickvm]""")

    /**
     * Strip Exec field codes (`%U %F %f %u %i %c %k %v %m` …) and unescape `%%`.
     * The codes expand to files/URIs/icon/caption we don't pass when launching a
     * bare app, so they're removed; collapsed whitespace keeps the command tidy.
     */
    fun stripExecCodes(exec: String): String {
        val protectedPct = exec.replace("%%", "\u0000")
        return FIELD_CODE.replace(protectedPct, "")
            .replace("\u0000", "%")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Parse the `[Desktop Entry]` group; null when it isn't a launchable app. */
    fun parse(text: String): Entry? {
        var inGroup = false
        val kv = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                if (inGroup) break // a second group → done with [Desktop Entry]
                inGroup = line == "[Desktop Entry]"
                continue
            }
            if (!inGroup) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (key.contains('[')) continue // locale key (Name[de]=) — v1 uses base
            kv[key] = line.substring(eq + 1).trim()
        }
        if ((kv["Type"] ?: "Application") != "Application") return null
        if (kv["NoDisplay"].isTrue() || kv["Hidden"].isTrue() || kv["Terminal"].isTrue()) return null
        val name = kv["Name"]?.takeIf { it.isNotBlank() } ?: return null
        val exec = kv["Exec"]?.takeIf { it.isNotBlank() }?.let { stripExecCodes(it) }
            ?.takeIf { it.isNotBlank() } ?: return null
        val categories = kv["Categories"]?.split(";")?.filter { it.isNotBlank() } ?: emptyList()
        return Entry(name, exec, kv["Icon"]?.takeIf { it.isNotBlank() }, categories)
    }

    private fun String?.isTrue(): Boolean = this?.trim().equals("true", ignoreCase = true)

    /** Icon size dirs searched, largest first; "scalable" holds SVGs. */
    val ICON_SIZES = listOf("512x512", "256x256", "128x128", "96x96", "64x64", "48x48", "32x32", "scalable")
    private val ICON_THEMES = listOf("hicolor", "Adwaita", "Papirus")

    /**
     * Resolve an `Icon=` value to an existing file under [rootfs]: an absolute
     * path wins; else the theme dirs (png preferred over svg, large→small) then
     * pixmaps. Returns the host [File] or null. (Theme `index.theme` inheritance
     * isn't followed — v1 hardcodes the common themes.)
     */
    fun resolveIconPath(rootfs: File, icon: String, sizes: List<String> = ICON_SIZES): File? {
        if (icon.startsWith("/")) {
            return File(rootfs, icon.removePrefix("/")).takeIf { it.isFile }
        }
        // An Icon= that already carries an extension is a literal pixmaps name.
        if (icon.endsWith(".png") || icon.endsWith(".svg") || icon.endsWith(".xpm")) {
            return File(rootfs, "usr/share/pixmaps/$icon").takeIf { it.isFile }
        }
        for (theme in ICON_THEMES) {
            for (size in sizes) {
                val dir = File(rootfs, "usr/share/icons/$theme/$size/apps")
                File(dir, "$icon.png").let { if (it.isFile) return it }
                File(dir, "$icon.svg").let { if (it.isFile) return it }
            }
        }
        val pm = File(rootfs, "usr/share/pixmaps")
        for (ext in listOf("png", "xpm", "svg")) {
            File(pm, "$icon.$ext").let { if (it.isFile) return it }
        }
        return null
    }
}

/**
 * Discovers launchable GUI apps in the active proot guest by reading its
 * `.desktop` files directly off disk (the rootfs is plain files), resolving
 * each icon, and rasterizing any SVG icons to PNG once via the guest's
 * `rsvg-convert` — so the app side only ever decodes PNGs.
 */
class GuestAppScanner(private val proot: ProotManager) {

    suspend fun scan(): AppScanResult {
        val rootfs = proot.activeRootfsDir
        val appDirs = listOf(
            File(rootfs, "usr/share/applications"),
            File(rootfs, "usr/local/share/applications"),
            File(rootfs, "root/.local/share/applications"),
        )
        val byId = LinkedHashMap<String, DesktopEntryParser.Entry>()
        for (dir in appDirs) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".desktop") } ?: continue
            for (f in files) {
                val entry = runCatching { DesktopEntryParser.parse(f.readText()) }.getOrNull() ?: continue
                byId.putIfAbsent(f.name, entry)
            }
        }
        val entries = byId.values.toList()

        val iconFiles = entries.associateWith { e ->
            e.icon?.let { DesktopEntryParser.resolveIconPath(rootfs, it) }
        }
        val svgs = iconFiles.values.filterNotNull()
            .filter { it.extension.equals("svg", ignoreCase = true) }.distinct()
        val rasterized = if (svgs.isNotEmpty()) rasterizeSvgs(svgs) else emptyMap()

        var resolved = 0
        val apps = entries.map { e ->
            val f = iconFiles[e]
            val iconPath = when {
                f == null -> null
                f.extension.equals("svg", ignoreCase = true) -> rasterized[f.absolutePath]
                f.extension.equals("xpm", ignoreCase = true) -> null // BitmapFactory can't read XPM (v1)
                else -> f.absolutePath
            }
            if (iconPath != null) resolved++
            InstalledApp(e.name, e.exec, iconPath, e.categories)
        }.sortedBy { it.name.lowercase() }

        Log.d(TAG, "scanned ${apps.size} apps, $resolved/${apps.size} icons (${svgs.size} svg)")
        return AppScanResult(apps, resolved, apps.size)
    }

    /**
     * Rasterize SVG icons to 96px PNGs in one guest pass. The host icon-cache
     * dir is bound into the guest at `/tmp`, so the guest writes PNGs the app
     * reads straight off disk. Returns host-svg-path → host-png-path for the
     * ones that produced a file; empty if `rsvg-convert` can't be provisioned.
     */
    private suspend fun rasterizeSvgs(svgs: List<File>): Map<String, String> {
        val (ready, detail) = proot.ensureRenderTools(listOf("rsvg-convert"))
        if (!ready) {
            Log.w(TAG, "rsvg-convert unavailable; SVG icons unresolved: $detail")
            return emptyMap()
        }
        val cacheDir = proot.iconCacheDir.apply { mkdirs() }
        val guestDir = "/tmp/${cacheDir.name}"
        val want = HashMap<String, String>()
        val script = StringBuilder("mkdir -p '$guestDir'; ")
        for (svg in svgs) {
            val guestSvg = hostRootfsToGuest(svg) ?: continue
            val pngName = sha1(svg.absolutePath) + ".png"
            want[svg.absolutePath] = File(cacheDir, pngName).absolutePath
            val guestPng = "$guestDir/$pngName"
            script.append("[ -f '$guestPng' ] || rsvg-convert -w 96 -h 96 '$guestSvg' -o '$guestPng' 2>/dev/null; ")
        }
        script.append("true")
        proot.runCommandInProot(script.toString())
        return want.filterValues { File(it).isFile }
    }

    private fun hostRootfsToGuest(f: File): String? {
        val root = proot.activeRootfsDir.absolutePath
        val p = f.absolutePath
        return if (p.startsWith(root)) p.removePrefix(root).ifEmpty { "/" } else null
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val TAG = "GuestAppScanner"
    }
}
