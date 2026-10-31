package me.shawnsquire.tandem.media

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.SettingsRepository
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Deletes imported wallpapers and ringtones that no alarm and no setting references any more.
 * Runs once at full startup, in the background; never on the fire path.
 */
class MediaOrphanSweeper(
    private val filesDir: File,
    private val alarms: AlarmRepository,
    private val settings: SettingsRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Returns the number of files deleted. Never throws, except to cancel. */
    suspend fun sweep(): Int = withContext(Dispatchers.IO) {
        // An unreadable reference list would make every file look orphaned: delete nothing.
        val referenced = try {
            references()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.w(TAG, "reading media references failed; sweeping nothing", e)
            return@withContext 0
        }
        val root = try {
            filesDir.canonicalFile
        } catch (e: Exception) {
            Log.w(TAG, "resolving $filesDir failed; sweeping nothing", e)
            return@withContext 0
        }
        DIRS.sumOf { sweepDir(root, File(filesDir, it), referenced) }
    }

    private suspend fun references(): Set<File> {
        val raw = alarms.alarms.first().flatMap { listOf(it.wallpaperPath, it.ringtoneUri) } +
            settings.defaultWallpaperPath.first() + settings.defaultRingtoneUri.first()
        return raw.mapNotNull(::localFile).map { it.canonicalFile }.toSet()
    }

    private fun sweepDir(root: File, dir: File, referenced: Set<File>): Int {
        if (!dir.isDirectory) return 0
        val canonicalDir = try { dir.canonicalFile } catch (e: Exception) { return 0 }
        // The directory itself must not be a link out of filesDir.
        if (canonicalDir.parentFile != root) return 0
        val cutoff = now() - MIN_AGE_MS
        var deleted = 0
        for (f in dir.listFiles().orEmpty()) {
            try {
                // A symlink is never a regular file here, so it is skipped rather than followed.
                if (!Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                val canonical = f.canonicalFile
                if (canonical.parentFile != canonicalDir || canonical in referenced) continue
                // Young files may belong to an edit that hasn't saved its reference yet.
                if (f.lastModified() > cutoff) continue
                if (f.delete()) deleted++
            } catch (e: Exception) {
                Log.w(TAG, "sweeping $f failed", e)
            }
        }
        return deleted
    }

    private companion object {
        const val TAG = "MediaOrphanSweeper"
        const val MIN_AGE_MS = 60 * 60 * 1000L
        val DIRS = listOf(WallpaperImporter.WALLPAPERS_DIR, RingtoneImporter.RINGTONES_DIR)

        /** A `file://` URI or a plain absolute path; null for content:// and every other URI. */
        fun localFile(s: String?): File? = when {
            s.isNullOrBlank() -> null
            s.startsWith("/") -> File(s)
            s.startsWith("file:") -> Uri.parse(s).path?.let(::File)
            else -> null
        }
    }
}
