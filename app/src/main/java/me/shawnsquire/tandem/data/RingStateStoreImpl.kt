package me.shawnsquire.tandem.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingState
import org.json.JSONArray
import org.json.JSONObject

class RingStateStoreImpl(private val dataStore: DataStore<Preferences>) : RingStateStore {
    override val state: Flow<RingState?> = dataStore.data.map { p ->
        val alarmId = p[ALARM_ID]
        val ringId = p[RING_ID]
        val startedAt = p[STARTED_AT]
        if (alarmId == null || ringId == null || startedAt == null) {
            null
        } else {
            RingState(
                alarmId = alarmId,
                ringId = ringId,
                startedAt = Instant.ofEpochMilli(startedAt),
                snoozedUntil = p[SNOOZED_UNTIL]?.let(Instant::ofEpochMilli),
                occurrenceAt = p[OCCURRENCE_AT]?.let(Instant::ofEpochMilli),
                merged = decodeMerged(p[MERGED]),
                snoozeCount = decodeSnoozeCount(p),
            )
        }
    }

    override suspend fun set(state: RingState?) {
        dataStore.edit { p ->
            if (state == null) {
                p.remove(ALARM_ID)
                p.remove(RING_ID)
                p.remove(STARTED_AT)
                p.remove(SNOOZED_UNTIL)
                p.remove(OCCURRENCE_AT)
                p.remove(MERGED)
                p.remove(SNOOZE_COUNT)
            } else {
                p[ALARM_ID] = state.alarmId
                p[RING_ID] = state.ringId
                p[STARTED_AT] = state.startedAt.toEpochMilli()
                val snoozedUntil = state.snoozedUntil
                if (snoozedUntil == null) p.remove(SNOOZED_UNTIL) else p[SNOOZED_UNTIL] = snoozedUntil.toEpochMilli()
                val occurrenceAt = state.occurrenceAt
                if (occurrenceAt == null) p.remove(OCCURRENCE_AT) else p[OCCURRENCE_AT] = occurrenceAt.toEpochMilli()
                if (state.merged.isEmpty()) p.remove(MERGED) else p[MERGED] = encodeMerged(state.merged)
                if (state.snoozeCount > 0) p[SNOOZE_COUNT] = state.snoozeCount else p.remove(SNOOZE_COUNT)
            }
        }
    }

    private companion object {
        fun encodeMerged(members: List<RingMember>): String = JSONArray().also { arr ->
            members.forEach {
                arr.put(JSONObject().put("alarmId", it.alarmId).put("occurrenceAt", it.occurrenceAt.toEpochMilli()))
            }
        }.toString()

        // A ring must never be lost to a parse error: bad JSON yields an empty list,
        // and a bad member is dropped while the good ones are kept.
        fun decodeMerged(raw: String?): List<RingMember> {
            if (raw == null) return emptyList()
            val arr = try { JSONArray(raw) } catch (_: Exception) { return emptyList() }
            return (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    RingMember(o.getString("alarmId"), Instant.ofEpochMilli(o.getLong("occurrenceAt")))
                } catch (_: Exception) {
                    null
                }
            }
        }

        // A ring must never be lost to a bad count: a negative or wrongly typed value reads 0.
        fun decodeSnoozeCount(p: Preferences): Int =
            try { (p[SNOOZE_COUNT] ?: 0).coerceAtLeast(0) } catch (_: Exception) { 0 }

        val SNOOZE_COUNT = intPreferencesKey("snoozeCount")
        val OCCURRENCE_AT = longPreferencesKey("occurrenceAt")
        val MERGED = stringPreferencesKey("merged")
        val ALARM_ID = stringPreferencesKey("alarmId")
        val RING_ID = stringPreferencesKey("ringId")
        val STARTED_AT = longPreferencesKey("startedAt")
        val SNOOZED_UNTIL = longPreferencesKey("snoozedUntil")
    }
}
