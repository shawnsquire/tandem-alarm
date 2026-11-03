package me.shawnsquire.tandem.backup

import android.net.Uri
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.backup.BackupRejected.Reason
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.media.RingtoneImporter
import me.shawnsquire.tandem.media.WallpaperImporter
import me.shawnsquire.tandem.media.sanitizeRingtoneName
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.WallpaperFrame
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

private const val UUID_LENGTH = 36

/** A validated backup whose media is staged on disk; [discard] it if it isn't applied. */
class StagedBackup internal constructor(
    val manifest: BackupManifest,
    internal val dir: File,
    /** Zip path to the staged copy. */
    internal val files: Map<String, File>,
    /** Zip paths whose entry was over the size cap; they count as missing media. */
    internal val dropped: Set<String>,
) {
    /** Deletes the staging directory. Safe to call twice. */
    fun discard() {
        dir.deleteRecursively()
    }
}

data class ImportResult(val alarms: Int, val missingMedia: Int)

/**
 * Reads a backup zip and applies it. Nothing here depends on `ui/`: the caller passes alarm writes in.
 * The manifest's `appVersion` is informational and never drives logic.
 */
class BackupImporter(
    private val filesDir: File,
    private val stagingRoot: File,
    private val canOpenUri: (String) -> Boolean,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxMediaBytes: Long = MAX_MEDIA_BYTES_PER_FILE,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val ringtonesDir get() = File(filesDir, RingtoneImporter.RINGTONES_DIR)
    private val wallpapersDir get() = File(filesDir, WallpaperImporter.WALLPAPERS_DIR)

    /**
     * Blocking; call off the main thread. The file is untrusted. Throws [BackupRejected] and then leaves
     * no staged files behind. Does NOT close [input]: the caller owns it.
     */
    fun read(input: InputStream): StagedBackup {
        stagingRoot.listFiles()?.forEach { it.deleteRecursively() }
        val dir = File(stagingRoot, UUID.randomUUID().toString())
        try {
            return readInto(input, dir)
        } catch (e: BackupRejected) {
            dir.deleteRecursively()
            throw e
        } catch (e: ZipException) {
            dir.deleteRecursively()
            throw BackupRejected(Reason.INCOMPLETE)
        } catch (e: UncheckedIOException) {
            dir.deleteRecursively()
            throw e.cause ?: e
        } catch (e: IOException) {
            dir.deleteRecursively()
            throw BackupRejected(Reason.INCOMPLETE)
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }

    private fun readInto(input: InputStream, dir: File): StagedBackup {
        val zip = ZipInputStream(input)
        val first = try {
            zip.nextEntry
        } catch (_: IOException) {
            null
        } ?: throw BackupRejected(Reason.NOT_A_BACKUP)
        if (first.name != MANIFEST_ENTRY) throw BackupRejected(Reason.NOT_A_BACKUP)

        val manifestBytes = zip.readBounded(MAX_MANIFEST_BYTES.toLong())
            ?: throw BackupRejected(Reason.TOO_LARGE)
        val manifest = parseManifest(String(manifestBytes, Charsets.UTF_8))
        val wanted = manifest.mediaPaths()

        check(dir.mkdirs()) { "Cannot create $dir" }
        val staged = HashMap<String, File>()
        val dropped = HashSet<String>()
        val seen = HashSet<String>()
        var count = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            if (++count > maxEntries) throw BackupRejected(Reason.TOO_LARGE)
            val name = entry.name
            if (entry.isDirectory || name !in wanted) continue
            if (!seen.add(name)) throw BackupRejected(Reason.DAMAGED)
            // The entry name is only a lookup key; the file name is ours.
            val out = File(dir, seen.size.toString())
            if (copyBounded(zip, out)) staged[name] = out else dropped += name
        }
        if (wanted.any { it !in staged && it !in dropped }) throw BackupRejected(Reason.INCOMPLETE)
        return StagedBackup(manifest, dir, staged, dropped)
    }

    /** Copies up to [maxMediaBytes] into [out]; false (and no file) if the entry is larger. */
    private fun copyBounded(src: InputStream, out: File): Boolean {
        var total = 0L
        val buf = ByteArray(8192)
        try {
            out.outputStream().use { sink ->
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxMediaBytes) break
                    try {
                        sink.write(buf, 0, n)
                    } catch (e: IOException) {
                        throw UncheckedIOException(e)
                    }
                }
            }
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        if (total > maxMediaBytes) {
            out.delete()
            return false
        }
        return true
    }

    /**
     * Adds the alarms with fresh ids and replaces the settings. Always discards [staged].
     * If anything fails, the alarms added so far and every copied file are removed before rethrowing.
     */
    suspend fun apply(
        staged: StagedBackup,
        addAlarm: suspend (Alarm) -> Unit,
        deleteAlarm: suspend (String) -> Unit,
        settings: SettingsRepository,
        setDisplayName: suspend (String?) -> Unit,
    ): ImportResult {
        val added = mutableListOf<Pair<Alarm, List<File>>>()
        val newDefaults = mutableListOf<File>()
        try {
            var missing = 0
            for (b in staged.manifest.alarms) {
                val copies = mutableListOf<File>()
                try {
                    val ring = resolveSound(staged, b.ringtone, copies)
                    if (b.ringtone != null && ring == null) missing++
                    val wall = resolveWallpaper(staged, b.wallpaper, copies)
                    if (b.wallpaper != null && wall == null) missing++
                    val alarm = Alarm(
                        id = newId(), label = b.label, hour = b.hour, minute = b.minute,
                        days = b.days.toSet(), enabled = b.enabled,
                        skippedOccurrenceAt = null, ringtoneUri = ring, wallpaperPath = wall,
                        link = null, soloSkippedOccurrenceAt = null, lateRing = null,
                        vibration = b.vibration,
                        vibrationOn = b.vibrationOn || !b.soundOn,
                        soundOn = b.soundOn || !b.vibrationOn,
                        wallpaperFrame = if (wall != null) b.wallpaper?.frame else null,
                        textPosition = b.textPosition,
                    )
                    // Counted before the call: a throwing addAlarm may still have stored it.
                    added += alarm to copies
                    addAlarm(alarm)
                } catch (t: Throwable) {
                    copies.forEach { it.delete() }
                    throw t
                }
            }

            val s = staged.manifest.settings
            val oldRingtone = settings.defaultRingtoneUri.first()
            val oldWallpaper = settings.defaultWallpaperPath.first()
            val defaults = newDefaults
            val ring = resolveSound(staged, s.ringtone, defaults)
            if (s.ringtone != null && ring == null) missing++
            val wall = resolveWallpaper(staged, s.wallpaper, defaults)
            if (s.wallpaper != null && wall == null) missing++
            settings.setSnoozeMinutes(s.snoozeMinutes)
            settings.setDefaultRingtoneUri(ring)
            settings.setDefaultWallpaper(wall, if (wall != null) s.wallpaper?.frame else null)
            setDisplayName(s.displayName)

            if (oldRingtone != null && oldRingtone != ring) deleteIn(ringtonesDir, oldRingtone)
            if (oldWallpaper != null && oldWallpaper != wall) deleteIn(wallpapersDir, oldWallpaper)
            return ImportResult(added.size, missing)
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                for ((alarm, files) in added.asReversed()) {
                    try { deleteAlarm(alarm.id) } catch (_: Throwable) { /* best effort */ }
                    files.forEach { it.delete() }
                }
                newDefaults.forEach { it.delete() }
            }
            throw t
        } finally {
            staged.discard()
        }
    }

    /** A file sound becomes its own copy (or null if dropped); a URI is kept if it can be opened. */
    private fun resolveSound(staged: StagedBackup, sound: BackupSound?, copies: MutableList<File>): String? =
        when (sound) {
            null -> null
            is BackupSound.Uri -> sound.uri.takeIf { canOpenUri(it) }
            is BackupSound.File -> staged.files[sound.path]?.let { src ->
                val zipName = sound.path.substringAfterLast('/')
                val fallback = if (zipName.length > UUID_LENGTH + 1 && zipName[UUID_LENGTH] == '-') {
                    zipName.substring(UUID_LENGTH + 1)
                } else {
                    zipName
                }
                val name = sanitizeRingtoneName(sound.title ?: fallback)
                val copy = copyInto(src, ringtonesDir, "${UUID.randomUUID()}-$name", copies)
                Uri.fromFile(copy).toString()
            }
        }

    private fun resolveWallpaper(staged: StagedBackup, w: BackupWallpaper?, copies: MutableList<File>): String? {
        val src = w?.let { staged.files[it.path] } ?: return null
        return copyInto(src, wallpapersDir, "${UUID.randomUUID()}.jpg", copies).absolutePath
    }

    /** Copies via a `.tmp` file and a rename, and records the result in [copies]. */
    private fun copyInto(src: File, dir: File, name: String, copies: MutableList<File>): File {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create $dir" }
        val tmp = File(dir, "${UUID.randomUUID()}.tmp")
        val out = File(dir, name)
        try {
            src.copyTo(tmp, overwrite = true)
            check(tmp.renameTo(out)) { "Cannot move $tmp into place" }
        } catch (t: Throwable) {
            tmp.delete()
            out.delete()
            throw t
        }
        copies += out
        return out
    }

    /** Best-effort delete of [path] (or `file:` URI) only if it sits directly in [dir]. */
    private fun deleteIn(dir: File, path: String) {
        try {
            val raw = if (path.startsWith("file:")) Uri.parse(path).path ?: return else path
            val file = File(raw).canonicalFile
            if (file.parentFile != dir.canonicalFile) return
            if (file.isFile) file.delete()
        } catch (_: Exception) {
            // best effort
        }
    }
}

private fun BackupManifest.mediaPaths(): Set<String> {
    val out = HashSet<String>()
    fun add(s: BackupSound?) { if (s is BackupSound.File) out += s.path }
    fun add(w: BackupWallpaper?) { if (w != null) out += w.path }
    add(settings.ringtone); add(settings.wallpaper)
    alarms.forEach { add(it.ringtone); add(it.wallpaper) }
    return out
}

/** Reads all of the current entry, or null if it is longer than [limit]. */
private fun InputStream.readBounded(limit: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > limit) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
