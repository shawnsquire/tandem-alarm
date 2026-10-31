package me.shawnsquire.tandem.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: String): AlarmEntity?

    @Upsert
    suspend fun upsert(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE alarms SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE alarms SET skippedOccurrenceAt = :skippedAtMillis WHERE id = :id")
    suspend fun setSkip(id: String, skippedAtMillis: Long?)

    @Query("UPDATE alarms SET skippedOccurrenceAt = NULL WHERE skippedOccurrenceAt IS NOT NULL AND skippedOccurrenceAt <= :nowMillis")
    suspend fun clearExpiredSkips(nowMillis: Long)
}
