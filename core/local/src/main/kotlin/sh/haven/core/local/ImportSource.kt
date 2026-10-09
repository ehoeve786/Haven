package sh.haven.core.local

/**
 * What the user typed into "Import rootfs tarball" (#284), classified.
 *
 * The field is free text, so people type the three things you would expect:
 * an https URL, a bare filesystem path, and a `file://` URL. Only the first
 * two worked. A `file://` URL went straight to `File("file:///sdcard/x.tar.gz")`,
 * which is not a file, so the import failed with "Local rootfs not found:
 * file:///sdcard/x.tar.gz" — an error that shows the user their own input back
 * and does not tell them the scheme was the problem (#560).
 *
 * `content://` (the Storage Access Framework) is a different matter and still
 * unsupported: those need a ContentResolver and a permission grant, not a path.
 * It gets named explicitly rather than falling into the same unhelpful error,
 * because "pick the file with the system picker" is the obvious thing to try.
 */
sealed class ImportSource {

    /** Download it. */
    data class Remote(val url: String) : ImportSource()

    /** Read it from the filesystem. */
    data class LocalFile(val path: String) : ImportSource()

    /** Recognised, but not something this path can open. [reason] is user-facing. */
    data class Unsupported(val reason: String) : ImportSource()

    companion object {
        fun of(source: String): ImportSource {
            val trimmed = source.trim()
            return when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                    Remote(trimmed)

                trimmed.startsWith("content://") -> Unsupported(
                    "Storage Access Framework URIs (content://) are not supported here yet. " +
                        "Copy the tarball somewhere readable, for example /sdcard/Download, " +
                        "and give its path instead.",
                )

                trimmed.startsWith("file://") -> {
                    val path = trimmed.removePrefix("file://")
                    if (path.startsWith("/")) {
                        LocalFile(path)
                    } else {
                        Unsupported(
                            "A file:// URL needs an absolute path, so three slashes after " +
                                "'file:', as in file:///sdcard/Download/rootfs.tar.gz.",
                        )
                    }
                }

                trimmed.isEmpty() -> Unsupported("No rootfs tarball given.")

                else -> LocalFile(trimmed)
            }
        }
    }
}
