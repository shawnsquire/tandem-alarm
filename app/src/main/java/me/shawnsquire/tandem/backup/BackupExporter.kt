package me.shawnsquire.tandem.backup

import android.net.Uri
import me.shawnsquire.tandem.media.RingtoneImporter
import me.shawnsquire.tandem.media.WallpaperImporter
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.WallpaperFrame
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What a backup holds: the phone's alarms (linked rows are dropped on export) and its settings. */
data class BackupSnapshot(
    val alarms: List<Alarm>,
    val snoozeMinutes: Int,
    val defaultRingtoneUri: String?,
    val defaultWallpaperPath: String?,
    val defaultWallpaperFrame: WallpaperFrame?,
    val displayName: String?,
)

/** [alarms] is the number of own alarms written; [missingMedia] the ringtone and wallpaper references left out. */
data class ExportResult(val alarms: Int, val missingMedia: Int)

/** Writes a [BackupSnapshot] as a zip: `manifest.json` first, then each media file once. */
class BackupExporter(
    private val filesDir: File,
    private val appVersion: String,
    /** Opens a media file; a seam so tests can make a read fail partway. */
    private val open: (File) -> InputStream = ::FileInputStream,
    private val now: () -> Instant = Instant::now,
) {
    /** Blocking; call off the main thread. Doesn't close [out]. */
    fun export(snapshot: BackupSnapshot, out: OutputStream): ExportResult {
        val media = Media()
        try {
            val own = snapshot.alarms.filter { it.link == null }
            val manifest = BackupManifest(
                exportedAt = now(),
                appVersion = appVersion,
                settings = BackupSettings(
                    snoozeMinutes = snapshot.snoozeMinutes,
                    ringtone = media.sound(snapshot.defaultRingtoneUri),
                    wallpaper = media.wallpaper(snapshot.defaultWallpaperPath, snapshot.defaultWallpaperFrame),
                    displayName = snapshot.displayName?.takeIf { it.isNotBlank() },
                ),
                alarms = own.map {
                    BackupAlarm(
                        label = it.label,
                        hour = it.hour,
                        minute = it.minute,
                        days = it.days.sorted(),
                        enabled = it.enabled,
                        ringtone = media.sound(it.ringtoneUri),
                        wallpaper = media.wallpaper(it.wallpaperPath, it.wallpaperFrame),
                        vibration = it.vibration,
                        vibrationOn = it.vibrationOn,
                        soundOn = it.soundOn,
                        textPosition = it.textPosition,
                    )
                },
            )
            val sink = FlushOnClose(out)
            ZipOutputStream(sink).use { zip ->
                try {
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifest.toJson().toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    for ((path, input) in media.entries) {
                        zip.putNextEntry(ZipEntry(path))
                        input.copyTo(zip)
                        zip.closeEntry()
                    }
                } catch (e: Throwable) {
                    // Closing the zip would finish it with a valid central directory around a truncated file.
                    sink.discard = true
                    throw e
                }
            }
            return ExportResult(own.size, media.missing)
        } finally {
            media.close()
        }
    }

    /**
     * Decides the media set before the manifest is written: a file is opened here, so the manifest
     * never names an entry that the zip lacks (an open handle outlives a delete). Streams stay open
     * until [close], and are copied into the zip one at a time.
     */
    private inner class Media : Closeable {
        val entries = LinkedHashMap<String, InputStream>()
        var missing = 0
            private set

        fun sound(uri: String?): BackupSound? {
            uri ?: return null
            val parsed = Uri.parse(uri)
            if (parsed.scheme != "file") return BackupSound.Uri(uri)
            val file = parsed.path?.let { File(it) }
            val path = file?.let { add(it, RingtoneImporter.RINGTONES_DIR, "ringtones/") }
            if (path == null) {
                missing++
                return null
            }
            return BackupSound.File(path, RingtoneImporter.titleOf(filesDir, parsed))
        }

        fun wallpaper(path: String?, frame: WallpaperFrame?): BackupWallpaper? {
            path ?: return null
            val entry = add(File(path), WallpaperImporter.WALLPAPERS_DIR, "wallpapers/")
            if (entry == null) {
                missing++
                return null
            }
            return BackupWallpaper(entry, frame)
        }

        /** The zip path of [file] once stored, or null if it isn't a readable file directly in [dir]. */
        private fun add(file: File, dir: String, zipDir: String): String? {
            val entry = "$MEDIA_DIR$zipDir${file.name}"
            return try {
                val canonical = file.canonicalFile
                if (canonical.parentFile != File(filesDir, dir).canonicalFile || !canonical.isFile) return null
                if (entry !in entries) entries[entry] = open(canonical)
                entry
            } catch (_: IOException) {
                null
            }
        }

        override fun close() {
            for (input in entries.values) {
                try {
                    input.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    /**
     * Lets [ZipOutputStream.close] release its deflater without closing the caller's stream.
     * Once [discard] is set, nothing more reaches [out], so a failed export is left unfinished.
     */
    private class FlushOnClose(out: OutputStream) : FilterOutputStream(out) {
        var discard = false

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (!discard) out.write(b, off, len)
        }

        override fun flush() {
            if (!discard) out.flush()
        }

        override fun close() = flush()
    }
}
