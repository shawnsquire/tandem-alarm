package me.shawnsquire.tandem.backup

import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.WallpaperFrame
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant

// `manifest.json` is the first entry, so a reader checks `format` and `version` before it reads media.
// An export that fails halfway can leave a file cut off after the manifest, so an importer must reject
// the whole file when the manifest names an entry the zip lacks.
// org.json writes whole floats as integers (a zoom of 2.0 becomes `2`): read numbers with
// `getDouble`/`optDouble`, never `getString` or a type check.
const val BACKUP_FORMAT = "tandem-backup"
const val BACKUP_VERSION = 1
const val MANIFEST_ENTRY = "manifest.json"
const val MEDIA_DIR = "media/"
const val MAX_ALARMS = 200
const val MAX_MANIFEST_BYTES = 1_048_576
const val MAX_MEDIA_BYTES_PER_FILE = 100L * 1024 * 1024
const val MAX_ENTRIES = 1000

/** Total bytes an import may stage on disk, across all media files. */
const val MAX_STAGED_BYTES = 1L shl 30

/** Total bytes `apply` may copy: copies are made per reference, so a shared file counts once per use. */
const val MAX_IMPORT_BYTES = 2L shl 30

/** A ringtone: a copied file in the zip ([File], [path] is the zip entry) or a URI kept verbatim ([Uri]). */
sealed interface BackupSound {
    data class File(val path: String, val title: String?) : BackupSound
    data class Uri(val uri: String) : BackupSound
}

/** A wallpaper copied into the zip at [path], framed by [frame] if set. */
data class BackupWallpaper(val path: String, val frame: WallpaperFrame?)

data class BackupAlarm(
    val label: String,
    val hour: Int,
    val minute: Int,
    /** Empty means a one-shot alarm. */
    val days: List<DayOfWeek>,
    val enabled: Boolean,
    val ringtone: BackupSound?,
    val wallpaper: BackupWallpaper?,
    val vibration: VibrationPattern,
    val vibrationOn: Boolean,
    val soundOn: Boolean,
    val textPosition: TextPosition,
)

data class BackupSettings(
    val snoozeMinutes: Int,
    val ringtone: BackupSound?,
    val wallpaper: BackupWallpaper?,
    val displayName: String?,
)

/** The contents of `manifest.json`. Absent values are omitted from the JSON, never written as null. */
data class BackupManifest(
    val exportedAt: Instant,
    val appVersion: String,
    val settings: BackupSettings,
    val alarms: List<BackupAlarm>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("format", BACKUP_FORMAT)
        .put("version", BACKUP_VERSION)
        .put("exportedAt", exportedAt.toString())
        .put("appVersion", appVersion)
        .put("settings", settings.toJson())
        .put("alarms", JSONArray().also { a -> alarms.forEach { a.put(it.toJson()) } })
}

internal fun BackupSettings.toJson(): JSONObject = JSONObject().apply {
    put("snoozeMinutes", snoozeMinutes)
    ringtone?.let { put("ringtone", it.toJson()) }
    wallpaper?.let { put("wallpaper", it.toJson()) }
    displayName?.let { put("displayName", it) }
}

internal fun BackupAlarm.toJson(): JSONObject = JSONObject().apply {
    put("label", label)
    put("hour", hour)
    put("minute", minute)
    put("days", JSONArray().also { a -> days.forEach { a.put(it.name) } })
    put("enabled", enabled)
    ringtone?.let { put("ringtone", it.toJson()) }
    wallpaper?.let { put("wallpaper", it.toJson()) }
    put("vibration", vibration.name)
    put("vibrationOn", vibrationOn)
    put("soundOn", soundOn)
    put("textPosition", textPosition.name)
}

internal fun BackupSound.toJson(): JSONObject = when (this) {
    is BackupSound.File -> JSONObject().put("file", path).also { o -> title?.let { o.put("title", it) } }
    is BackupSound.Uri -> JSONObject().put("uri", uri)
}

internal fun BackupWallpaper.toJson(): JSONObject = JSONObject().apply {
    put("file", path)
    frame?.let { put("frame", it.toJson()) }
}

internal fun WallpaperFrame.toJson(): JSONObject = JSONObject()
    .put("zoom", zoom.toJsonNumber())
    .put("focusX", focusX.toJsonNumber())
    .put("focusY", focusY.toJsonNumber())

/** Via the shortest decimal, so 0.3f is written as 0.3 and not 0.30000001192092896. */
private fun Float.toJsonNumber(): Double = toString().toDouble()
