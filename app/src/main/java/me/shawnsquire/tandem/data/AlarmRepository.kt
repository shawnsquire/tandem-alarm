package me.shawnsquire.tandem.data

import kotlinx.coroutines.flow.Flow
import me.shawnsquire.tandem.schedule.Alarm
import java.time.Instant

/** Local alarm store; sole source of truth for this phone's alarms (I2). Works offline (I1). */
interface AlarmRepository {
    val alarms: Flow<List<Alarm>>
    suspend fun get(id: String): Alarm?
    suspend fun upsert(alarm: Alarm)
    suspend fun delete(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun setSkip(id: String, occurrence: Instant?)
}
