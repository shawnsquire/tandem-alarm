package me.shawnsquire.tandem.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

private const val UUID_LENGTH = 36
private const val MAX_NAME_LENGTH = 64

/** A file-system-safe version of a picked file's display name; "sound" if nothing usable is left. */
internal fun sanitizeRingtoneName(raw: String?): String {
    var name = raw.orEmpty()
        .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == ' ' }
        .trim()
        .trimStart('.')
        .take(MAX_NAME_LENGTH)
        .trim()
    if (name.isEmpty()) name = "sound"
    // Never collide with the *.tmp sweep.
    if (name.endsWith(".tmp", ignoreCase = true)) name += ".audio"
    return name
}

/**
 * Copies a picked audio file into `filesDir/ringtones` so playback at fire time never depends on a
 * document provider or a persisted grant.
 */
class RingtoneImporter(
    private val filesDir: File,
    private val open: (Uri) -> InputStream?,
    private val displayName: (Uri) -> String?,
    private val release: (Uri) -> Unit,
) {
    constructor(context: Context) : this(
        filesDir = context.filesDir,
        open = { context.contentResolver.openInputStream(it) },
        displayName = { uri ->
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        },
        release = { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) },
    )

    private val dir get() = File(filesDir, RINGTONES_DIR)

    /** Copies [uri] and returns the `file://` URI string of the copy. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val dir = dir
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create $dir" }
        // Leftovers from a process death mid-import.
        dir.listFiles { f -> f.extension == "tmp" }?.forEach { it.delete() }
        val id = UUID.randomUUID().toString()
        val name = sanitizeRingtoneName(try { displayName(uri) } catch (e: Exception) { null })
        val tmp = File(dir, "$id.tmp")
        val out = File(dir, "$id-$name")
        try {
            val input = open(uri) ?: throw IOException("Cannot open $uri")
            input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
            check(tmp.renameTo(out)) { "Cannot move $tmp into place" }
        } catch (t: Throwable) {
            tmp.delete()
            out.delete()
            throw t
        }
        // The copy is ours now; give the grant back (the per-app persisted-grant count is limited).
        try { release(uri) } catch (_: Exception) { /* best effort */ }
        Uri.fromFile(out).toString()
    }

    /** Deletes an imported file by path or `file://` URI. Refuses anything outside the ringtones directory. */
    fun delete(path: String): Boolean {
        val root = dir.canonicalFile
        val raw = if (path.startsWith("file:")) Uri.parse(path).path ?: return false else path
        val file = File(raw).canonicalFile
        if (file.parentFile != root) return false
        return file.isFile && file.delete()
    }

    companion object {
        internal const val RINGTONES_DIR = "ringtones"

        /** The display-name part of a copy made by [import], or null if [uri] is not one of ours. */
        internal fun titleOf(filesDir: File, uri: Uri): String? {
            if (uri.scheme != "file") return null
            val file = File(uri.path ?: return null).canonicalFile
            if (file.parentFile != File(filesDir, RINGTONES_DIR).canonicalFile) return null
            val n = file.name
            if (n.length <= UUID_LENGTH + 1 || n[UUID_LENGTH] != '-') return null
            return n.substring(UUID_LENGTH + 1).takeIf { it.isNotBlank() }
        }
    }
}
