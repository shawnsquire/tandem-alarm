package me.shawnsquire.tandem.alarm

import android.content.Context
import android.util.Log
import me.shawnsquire.tandem.data.AlarmEntity
import me.shawnsquire.tandem.data.toAlarm
import me.shawnsquire.tandem.data.toEntity
import me.shawnsquire.tandem.data.toVibrationPattern
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth
import me.shawnsquire.tandem.schedule.VibrationPattern
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** An alarm as last scheduled from Room, with the trigger that was armed for it. */
data class MirroredAlarm(val alarm: Alarm, val nextTriggerAt: Instant?)

/** What a fire needs before the first unlock, when Room and the settings DataStore are unreadable. */
data class MirrorSnapshot(
    val alarms: List<MirroredAlarm>,
    val snoozeMinutes: Int?,
    val defaultRingtoneUri: String?,
) {
    companion object {
        val EMPTY = MirrorSnapshot(emptyList(), null, null)
    }
}

/**
 * Device-protected copy of the alarm data. Wallpapers are never mirrored: they are CE files, so
 * reads always report `wallpaperPath = null` and `link.overrideWallpaperPath = null`.
 */
interface AlarmMirror {
    /**
     * Null when the mirror is absent, unparseable, or any mirrored alarm fails to decode; never
     * throws. Absent counts as unreadable: once attached, every reschedule writes a snapshot (even
     * one with no alarms), so a missing one never means "no alarms". A partial list would make a
     * locked reschedule cancel the missing alarm, so it is unreadable too.
     */
    fun readOrNull(): MirrorSnapshot?
    /** Lenient, for settings: empty when unreadable, skipping alarms that fail to decode; never throws. */
    fun read(): MirrorSnapshot
    /** Throws when the write could not be committed. */
    fun write(snapshot: MirrorSnapshot)
    /** Throws, writing nothing, when [readOrNull] is null: an empty stand-in must never be saved over the real data. */
    fun update(transform: (MirrorSnapshot) -> MirrorSnapshot)
}

private fun unreadable() = IllegalStateException("alarm mirror unreadable")

/**
 * The whole snapshot is one JSON string under one key, written with commit(), so a reader never
 * sees half an update and the write survives the process dying right after.
 */
class SharedPrefsAlarmMirror(context: Context) : AlarmMirror {
    private val prefs by lazy { deviceProtectedPrefs(context, PREFS) }

    @Synchronized override fun readOrNull(): MirrorSnapshot? = decodeStored(strict = true)

    @Synchronized override fun read(): MirrorSnapshot = decodeStored(strict = false) ?: MirrorSnapshot.EMPTY

    @Synchronized override fun write(snapshot: MirrorSnapshot) {
        check(prefs.edit().putString(KEY, encode(snapshot)).commit()) { "alarm mirror write failed" }
    }

    @Synchronized override fun update(transform: (MirrorSnapshot) -> MirrorSnapshot) =
        write(transform(readOrNull() ?: throw unreadable()))

    /** [strict]: null if any alarm fails to decode; otherwise those alarms are skipped. */
    private fun decodeStored(strict: Boolean): MirrorSnapshot? = try {
        prefs.getString(KEY, null)?.let { decode(it, strict) }
    } catch (e: Exception) {
        Log.w(TAG, "unreadable alarm mirror", e)
        null
    }

    private fun encode(snapshot: MirrorSnapshot): String = JSONObject().apply {
        put("alarms", JSONArray().apply { snapshot.alarms.forEach { put(encode(it)) } })
        snapshot.snoozeMinutes?.let { put("snoozeMinutes", it) }
        snapshot.defaultRingtoneUri?.let { put("defaultRingtoneUri", it) }
    }.toString()

    private fun encode(mirrored: MirroredAlarm): JSONObject {
        // Through AlarmEntity so the days mask matches Room's exactly.
        val e = mirrored.alarm.toEntity()
        return JSONObject().apply {
            put("id", e.id)
            put("label", e.label)
            put("hour", e.hour)
            put("minute", e.minute)
            put("days", e.days)
            put("enabled", e.enabled)
            e.skippedOccurrenceAt?.let { put("skippedOccurrenceAt", it) }
            e.ringtoneUri?.let { put("ringtoneUri", it) }
            put("vibration", e.vibration)
            put("vibrationOn", e.vibrationOn)
            put("soundOn", e.soundOn)
            mirrored.alarm.soloSkippedOccurrenceAt?.let { put("soloSkippedOccurrenceAt", it.toEpochMilli()) }
            mirrored.alarm.link?.let { put("link", encode(it)) }
            mirrored.nextTriggerAt?.let { put("nextTriggerAt", it.toEpochMilli()) }
        }
    }

    // overrideWallpaperPath is left out like wallpaperPath: a CE file.
    private fun encode(link: AlarmLink): JSONObject = JSONObject().apply {
        put("ownerUid", link.ownerUid)
        put("ownerAlarmId", link.ownerAlarmId)
        put("mode", link.mode.name)
        link.ownerSkippedOccurrenceAt?.let { put("ownerSkippedOccurrenceAt", it.toEpochMilli()) }
        put("joinerEnabled", link.joinerEnabled)
        link.pendingBoth?.let { p ->
            put("pendingBoth", JSONObject().apply {
                put("type", p.type.name)
                p.occurrenceAt?.let { put("occurrenceAt", it.toEpochMilli()) }
                put("commandId", p.commandId)
                p.createdAt?.let { put("createdAt", it.toEpochMilli()) }
            })
        }
        link.overrideLabel?.let { put("overrideLabel", it) }
        link.overrideRingtoneUri?.let { put("overrideRingtoneUri", it) }
        link.overrideVibration?.let { put("overrideVibration", it.name) }
        link.overrideVibrationOn?.let { put("overrideVibrationOn", it) }
        link.overrideSoundOn?.let { put("overrideSoundOn", it) }
    }

    private fun decode(json: String, strict: Boolean): MirrorSnapshot? {
        val root = JSONObject(json)
        val alarms = root.getJSONArray("alarms")
        val decoded = (0 until alarms.length()).mapNotNull { i ->
            try {
                decode(alarms.getJSONObject(i))
            } catch (e: Exception) {
                Log.w(TAG, "unreadable mirrored alarm", e)
                if (strict) return null
                null
            }
        }
        return MirrorSnapshot(
            alarms = decoded,
            snoozeMinutes = if (root.isNull("snoozeMinutes")) null else root.getInt("snoozeMinutes"),
            defaultRingtoneUri = root.stringOrNull("defaultRingtoneUri"),
        )
    }

    private fun decode(o: JSONObject): MirroredAlarm = MirroredAlarm(
        alarm = AlarmEntity(
            id = o.getString("id"),
            label = o.getString("label"),
            hour = o.getInt("hour"),
            minute = o.getInt("minute"),
            days = o.getInt("days"),
            enabled = o.getBoolean("enabled"),
            skippedOccurrenceAt = if (o.isNull("skippedOccurrenceAt")) null else o.getLong("skippedOccurrenceAt"),
            ringtoneUri = o.stringOrNull("ringtoneUri"),
            wallpaperPath = null,
            // Absent in mirrors written before vibration: the old behavior, sound plus the pulse.
            vibration = o.stringOrNull("vibration") ?: VibrationPattern.PULSE.name,
            vibrationOn = o.booleanOrDefault("vibrationOn", true),
            soundOn = o.booleanOrDefault("soundOn", true),
        ).toAlarm().copy(
            // Absent in mirrors written before linked alarms.
            soloSkippedOccurrenceAt = o.instantOrNull("soloSkippedOccurrenceAt"),
            link = if (o.isNull("link")) null else decodeLink(o.getJSONObject("link")),
        ),
        nextTriggerAt = if (o.isNull("nextTriggerAt")) null else Instant.ofEpochMilli(o.getLong("nextTriggerAt")),
    )

    private fun decodeLink(o: JSONObject): AlarmLink = AlarmLink(
        ownerUid = o.getString("ownerUid"),
        ownerAlarmId = o.getString("ownerAlarmId"),
        mode = LinkMode.valueOf(o.getString("mode")),
        ownerSkippedOccurrenceAt = o.instantOrNull("ownerSkippedOccurrenceAt"),
        joinerEnabled = o.getBoolean("joinerEnabled"),
        pendingBoth = if (o.isNull("pendingBoth")) null else o.getJSONObject("pendingBoth").let { p ->
            PendingBoth(
                CommandType.valueOf(p.getString("type")),
                p.instantOrNull("occurrenceAt"),
                p.getString("commandId"),
                p.instantOrNull("createdAt"),
            )
        },
        overrideLabel = o.stringOrNull("overrideLabel"),
        overrideRingtoneUri = o.stringOrNull("overrideRingtoneUri"),
        overrideWallpaperPath = null,
        overrideVibration = o.stringOrNull("overrideVibration")?.toVibrationPattern(),
        overrideVibrationOn = o.booleanOrNull("overrideVibrationOn"),
        overrideSoundOn = o.booleanOrNull("overrideSoundOn"),
    )

    private fun JSONObject.instantOrNull(key: String): Instant? = if (isNull(key)) null else Instant.ofEpochMilli(getLong(key))

    private fun JSONObject.booleanOrNull(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)

    private fun JSONObject.booleanOrDefault(key: String, default: Boolean): Boolean = booleanOrNull(key) ?: default

    // optString() turns a JSON null into the string "null".
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    private companion object {
        const val TAG = "AlarmMirror"
        const val PREFS = "alarm_mirror"
        const val KEY = "snapshot"
    }
}

/** In-memory mirror for tests; [initial] null stands for an unreadable one. Drops wallpapers like the durable one. */
class InMemoryAlarmMirror(initial: MirrorSnapshot? = MirrorSnapshot.EMPTY) : AlarmMirror {
    private var snapshot = initial?.withoutWallpapers()

    @Synchronized override fun readOrNull(): MirrorSnapshot? = snapshot

    @Synchronized override fun read(): MirrorSnapshot = snapshot ?: MirrorSnapshot.EMPTY

    @Synchronized override fun write(snapshot: MirrorSnapshot) {
        this.snapshot = snapshot.withoutWallpapers()
    }

    @Synchronized override fun update(transform: (MirrorSnapshot) -> MirrorSnapshot) =
        write(transform(snapshot ?: throw unreadable()))

    private fun MirrorSnapshot.withoutWallpapers() =
        copy(alarms = alarms.map { m ->
            val a = m.alarm
            m.copy(alarm = a.copy(wallpaperPath = null, link = a.link?.copy(overrideWallpaperPath = null)))
        })
}
