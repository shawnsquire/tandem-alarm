package me.shawnsquire.tandem.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.shawnsquire.tandem.schedule.RingState

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
            } else {
                p[ALARM_ID] = state.alarmId
                p[RING_ID] = state.ringId
                p[STARTED_AT] = state.startedAt.toEpochMilli()
                val snoozedUntil = state.snoozedUntil
                if (snoozedUntil == null) p.remove(SNOOZED_UNTIL) else p[SNOOZED_UNTIL] = snoozedUntil.toEpochMilli()
            }
        }
    }

    private companion object {
        val ALARM_ID = stringPreferencesKey("alarmId")
        val RING_ID = stringPreferencesKey("ringId")
        val STARTED_AT = longPreferencesKey("startedAt")
        val SNOOZED_UNTIL = longPreferencesKey("snoozedUntil")
    }
}
