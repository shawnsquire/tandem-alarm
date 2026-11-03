package me.shawnsquire.tandem.backup

import me.shawnsquire.tandem.backup.BackupRejected.Reason
import me.shawnsquire.tandem.data.DEFAULT_SNOOZE_MINUTES
import me.shawnsquire.tandem.data.SNOOZE_RANGE
import me.shawnsquire.tandem.data.toTextPosition
import me.shawnsquire.tandem.data.toVibrationPattern
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.WallpaperFrame
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.DateTimeParseException

/** Why a backup file was refused. The UI maps each [reason] to a message. */
class BackupRejected(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_A_BACKUP, NEWER_VERSION, DAMAGED, INCOMPLETE, TOO_LARGE }
}

private const val MAX_LABEL = 200
private const val MAX_DISPLAY_NAME = 60
private const val MAX_TITLE = 128
private const val MAX_NAME = 128
private const val RINGTONE_DIR = "${MEDIA_DIR}ringtones/"
private const val WALLPAPER_DIR = "${MEDIA_DIR}wallpapers/"

private fun reject(reason: Reason): Nothing = throw BackupRejected(reason)

/**
 * Parses and validates `manifest.json`. The input is untrusted: every failure is a [BackupRejected],
 * and every `file` value is checked to be a plain name under `media/ringtones/` or `media/wallpapers/`
 * so a reader can never be walked out of its directory (zip-slip). Values that are merely out of range
 * are coerced to what the app would accept.
 */
fun parseManifest(json: String): BackupManifest {
    val root = try {
        JSONObject(json)
    } catch (_: JSONException) {
        reject(Reason.NOT_A_BACKUP)
    } catch (_: StackOverflowError) {
        // About 1 MiB of `[` recurses org.json past the stack; an Error would escape BackupRejected.
        reject(Reason.DAMAGED)
    }
    if (root.opt("format") != BACKUP_FORMAT) reject(Reason.NOT_A_BACKUP)

    val version = when (val v = root.opt("version")) {
        is Int -> v.toLong()
        is Long -> v
        null -> reject(Reason.DAMAGED)
        else -> reject(Reason.DAMAGED)
    }
    if (version > BACKUP_VERSION) reject(Reason.NEWER_VERSION)
    if (version < 1) reject(Reason.DAMAGED)

    val settings = root.opt("settings") as? JSONObject ?: reject(Reason.DAMAGED)
    val alarms = root.opt("alarms") as? JSONArray ?: reject(Reason.DAMAGED)
    if (alarms.length() > MAX_ALARMS) reject(Reason.TOO_LARGE)

    return BackupManifest(
        exportedAt = (root.opt("exportedAt") as? String)?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        } ?: Instant.EPOCH,
        appVersion = root.opt("appVersion") as? String ?: "",
        settings = parseSettings(settings),
        alarms = List(alarms.length()) { parseAlarm(alarms.opt(it) as? JSONObject ?: reject(Reason.DAMAGED)) },
    )
}

private fun parseSettings(o: JSONObject) = BackupSettings(
    snoozeMinutes = (o.optNumber("snoozeMinutes")?.coerceToInt(SNOOZE_RANGE) ?: DEFAULT_SNOOZE_MINUTES),
    ringtone = o.optObject("ringtone")?.let(::parseSound),
    wallpaper = o.optObject("wallpaper")?.let(::parseWallpaper),
    displayName = (o.opt("displayName") as? String)?.trim()?.take(MAX_DISPLAY_NAME)?.trim()?.ifEmpty { null },
)

private fun parseAlarm(o: JSONObject) = BackupAlarm(
    label = (o.opt("label") as? String ?: reject(Reason.DAMAGED)).trim().take(MAX_LABEL),
    hour = (o.opt("hour") as? Number ?: reject(Reason.DAMAGED)).coerceToInt(0..23),
    minute = (o.opt("minute") as? Number ?: reject(Reason.DAMAGED)).coerceToInt(0..59),
    days = parseDays(o),
    enabled = o.opt("enabled") as? Boolean ?: true,
    ringtone = o.optObject("ringtone")?.let(::parseSound),
    wallpaper = o.optObject("wallpaper")?.let(::parseWallpaper),
    vibration = (o.opt("vibration") as? String)?.toVibrationPattern() ?: VibrationPattern.PULSE,
    vibrationOn = o.opt("vibrationOn") as? Boolean ?: true,
    soundOn = o.opt("soundOn") as? Boolean ?: true,
    textPosition = (o.opt("textPosition") as? String)?.toTextPosition() ?: TextPosition.MIDDLE,
)

private fun parseDays(o: JSONObject): List<DayOfWeek> {
    if (!o.has("days") || o.isNull("days")) return emptyList()
    val a = o.opt("days") as? JSONArray ?: reject(Reason.DAMAGED)
    val named = (0 until a.length()).mapNotNull { i ->
        (a.opt(i) as? String)?.let { n -> DayOfWeek.entries.firstOrNull { it.name == n } }
    }.toSet()
    return DayOfWeek.entries.filter { it in named }
}

/** Exactly one of `file` or `uri`. A non-`content://` URI is dropped (null) so the default sound plays. */
private fun parseSound(o: JSONObject): BackupSound? {
    val hasFile = o.has("file")
    val hasUri = o.has("uri")
    if (hasFile == hasUri) reject(Reason.DAMAGED)
    if (hasFile) {
        val path = checkedPath(o.opt("file"), RINGTONE_DIR)
        val title = (o.opt("title") as? String)?.trim()?.take(MAX_TITLE)
        return BackupSound.File(path, title)
    }
    val uri = o.opt("uri") as? String ?: reject(Reason.DAMAGED)
    return if (uri.startsWith("content://", ignoreCase = true)) BackupSound.Uri(uri) else null
}

private fun parseWallpaper(o: JSONObject): BackupWallpaper {
    val path = checkedPath(o.opt("file"), WALLPAPER_DIR)
    val frame = o.optObject("frame")?.let { f ->
        val zoom = f.optNumber("zoom")
        val x = f.optNumber("focusX")
        val y = f.optNumber("focusY")
        if (zoom == null || x == null || y == null) null
        else WallpaperFrame(zoom.toFloat(), x.toFloat(), y.toFloat()).sanitized()
    }
    return BackupWallpaper(path, frame)
}

/** [value] must be `<dir><name>` with a plain, non-empty name. */
private fun checkedPath(value: Any?, dir: String): String {
    val path = value as? String ?: reject(Reason.DAMAGED)
    if (!path.startsWith(dir)) reject(Reason.DAMAGED)
    val name = path.substring(dir.length)
    if (name.isEmpty() || name.length > MAX_NAME || name == "." || name == ".." ||
        name.any { it == '/' || it == '\\' || it == '\u0000' }
    ) {
        reject(Reason.DAMAGED)
    }
    return path
}

/** Reads numbers through [Number.toDouble] because org.json writes a whole float as an integer. */
private fun Number.coerceToInt(range: IntRange): Int {
    val d = toDouble()
    return if (d.isNaN()) range.first else d.coerceIn(range.first.toDouble(), range.last.toDouble()).toInt()
}

private fun JSONObject.optNumber(key: String): Number? = opt(key) as? Number

/** Null when absent or JSON null; a present value of another type is a damaged file. */
private fun JSONObject.optObject(key: String): JSONObject? {
    if (!has(key) || isNull(key)) return null
    return opt(key) as? JSONObject ?: reject(Reason.DAMAGED)
}
