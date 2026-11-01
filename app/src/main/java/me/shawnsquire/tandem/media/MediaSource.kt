package me.shawnsquire.tandem.media

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

enum class MediaKind { WALLPAPER, RINGTONE }

/** What the source knows about the bytes it copied; [title] only matters for ringtones. */
data class SourceInfo(val contentType: String?, val title: String?)

/** Reads a wallpaper path or ringtone URI. All Android access sits behind this so [MediaPublisherImpl] is JVM-testable. */
interface MediaSource {
    /** Blocking. Copies at most [maxBytes] + 1 bytes of [source] into [dest] (so the caller sees an oversize file without reading it all); throws [IOException] if unreadable. */
    fun copy(source: String, kind: MediaKind, dest: File, maxBytes: Long): SourceInfo
}

class AndroidMediaSource(private val context: Context) : MediaSource {
    override fun copy(source: String, kind: MediaKind, dest: File, maxBytes: Long): SourceInfo {
        val uri = if (source.contains("://")) Uri.parse(source) else Uri.fromFile(File(source))
        val resolver = context.contentResolver
        val input = (if (uri.scheme == "file") File(uri.path ?: throw IOException("Bad path $source")).inputStream()
        else resolver.openInputStream(uri)) ?: throw IOException("Cannot open $source")
        input.use { src -> dest.outputStream().use { src.copyToLimited(it, maxBytes) } }
        if (kind == MediaKind.WALLPAPER) return SourceInfo("image/jpeg", null)
        return if (uri.scheme == "file") {
            val name = File(uri.path.orEmpty()).name
            val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            SourceInfo(type, RingtoneImporter.titleOf(context.filesDir, uri) ?: name)
        } else {
            SourceInfo(resolver.getType(uri), systemTitle(uri))
        }
    }

    private fun systemTitle(uri: Uri): String? {
        val title = try { RingtoneManager.getRingtone(context, uri)?.getTitle(context) } catch (_: Exception) { null }
        return title?.takeIf { it.isNotBlank() } ?: try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null }
    }
}
