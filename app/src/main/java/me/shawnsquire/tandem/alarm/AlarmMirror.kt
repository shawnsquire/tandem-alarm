package me.shawnsquire.tandem.alarm

import android.content.Context
import android.util.Log
import me.shawnsquire.tandem.data.AlarmEntity
import me.shawnsquire.tandem.data.toAlarm
import me.shawnsquire.tandem.data.toEntity
import me.shawnsquire.tandem.schedule.Alarm
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
 * [read] always reports `wallpaperPath = null`.
 */
interface AlarmMirror {
    /** Empty when absent or unparseable; never throws. */
    fun read(): MirrorSnapshot
    /** Throws when the write could not be committed. */
    fun write(snapshot: MirrorSnapshot)
    fun update(transform: (MirrorSnapshot) -> MirrorSnapshot)
}

/**
 * The whole snapshot is one JSON string under one key, written with commit(), so a reader never
 * sees half an update and the write survives the process dying right after.
 */
class SharedPrefsAlarmMirror(context: Context) : AlarmMirror {
    private val prefs by lazy { deviceProtectedPrefs(context, PREFS) }

    @Synchronized override fun read(): MirrorSnapshot = try {
        prefs.getString(KEY, null)?.let(::decode) ?: MirrorSnapshot.EMPTY
    } catch (e: Exception) {
        Log.w(TAG, "unreadable alarm mirror", e)
        MirrorSnapshot.EMPTY
    }

    @Synchronized override fun write(snapshot: MirrorSnapshot) {
        check(prefs.edit().putString(KEY, encode(snapshot)).commit()) { "alarm mirror write failed" }
    }

    @Synchronized override fun update(transform: (MirrorSnapshot) -> MirrorSnapshot) = write(transform(read()))

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
            mirrored.nextTriggerAt?.let { put("nextTriggerAt", it.toEpochMilli()) }
        }
    }

    private fun decode(json: String): MirrorSnapshot {
        val root = JSONObject(json)
        val alarms = root.getJSONArray("alarms")
        return MirrorSnapshot(
            alarms = (0 until alarms.length()).mapNotNull { i ->
                try {
                    decode(alarms.getJSONObject(i))
                } catch (e: Exception) {
                    Log.w(TAG, "skipping unreadable mirrored alarm", e)
                    null
                }
            },
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
        ).toAlarm(),
        nextTriggerAt = if (o.isNull("nextTriggerAt")) null else Instant.ofEpochMilli(o.getLong("nextTriggerAt")),
    )

    // optString() turns a JSON null into the string "null".
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    private companion object {
        const val TAG = "AlarmMirror"
        const val PREFS = "alarm_mirror"
        const val KEY = "snapshot"
    }
}

/** In-memory mirror for tests. Drops wallpapers like the durable one. */
class InMemoryAlarmMirror(initial: MirrorSnapshot = MirrorSnapshot.EMPTY) : AlarmMirror {
    private var snapshot = initial.withoutWallpapers()

    @Synchronized override fun read(): MirrorSnapshot = snapshot

    @Synchronized override fun write(snapshot: MirrorSnapshot) {
        this.snapshot = snapshot.withoutWallpapers()
    }

    @Synchronized override fun update(transform: (MirrorSnapshot) -> MirrorSnapshot) = write(transform(snapshot))

    private fun MirrorSnapshot.withoutWallpapers() =
        copy(alarms = alarms.map { it.copy(alarm = it.alarm.copy(wallpaperPath = null)) })
}
