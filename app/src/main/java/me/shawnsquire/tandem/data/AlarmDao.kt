package me.shawnsquire.tandem.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE linkOwnerUid IS NULL OR linkOwnerAlarmId IS NULL ORDER BY hour, minute, id")
    fun observeOwn(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE linkOwnerUid IS NOT NULL AND linkOwnerAlarmId IS NOT NULL ORDER BY hour, minute, id")
    fun observeLinked(): Flow<List<AlarmEntity>>

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

    @Query("UPDATE alarms SET skippedOccurrenceAt = NULL WHERE skippedOccurrenceAt IS NOT NULL AND skippedOccurrenceAt <= :cutoffMillis")
    suspend fun clearExpiredSkips(cutoffMillis: Long)

    @Query("UPDATE alarms SET soloSkippedOccurrenceAt = :skippedAtMillis WHERE id = :id")
    suspend fun setSoloSkip(id: String, skippedAtMillis: Long?)

    /** Linked rows only (both owner columns set); a missing or own row is left alone. */
    @Query("UPDATE alarms SET linkJoinerEnabled = :enabled WHERE id = :id AND linkOwnerUid IS NOT NULL AND linkOwnerAlarmId IS NOT NULL")
    suspend fun setJoinerEnabled(id: String, enabled: Boolean)

    /** Read-modify-write of one row in a single transaction; false if the row is missing. */
    @Transaction
    suspend fun modify(id: String, transform: (AlarmEntity) -> AlarmEntity?): Boolean {
        val row = get(id) ?: return false
        val next = transform(row) ?: return false
        upsert(next)
        return true
    }

    /** Inserts [alarm], or if the id exists replaces it with [merge] of the stored row and [alarm]. */
    @Transaction
    suspend fun upsertMerging(alarm: AlarmEntity, merge: (stored: AlarmEntity, incoming: AlarmEntity) -> AlarmEntity) {
        val stored = get(alarm.id)
        upsert(if (stored == null) alarm else merge(stored, alarm))
    }
}
