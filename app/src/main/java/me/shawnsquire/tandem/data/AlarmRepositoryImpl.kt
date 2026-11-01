package me.shawnsquire.tandem.data

import android.util.Log
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink

class AlarmRepositoryImpl(
    private val dao: AlarmDao,
    private val clock: Clock,
) : AlarmRepository {

    override val alarms: Flow<List<Alarm>> = observe(dao.observeAll())

    override val ownAlarms: Flow<List<Alarm>> = observe(dao.observeOwn())

    override val linkedAlarms: Flow<List<Alarm>> = observe(dao.observeLinked())

    private fun observe(rows: Flow<List<AlarmEntity>>): Flow<List<Alarm>> = rows
        .onStart { clearExpiredSkipsBestEffort() }
        .map { list ->
            val now = clock.instant()
            list.map { it.toAlarm().withoutExpiredSkip(now) }
        }

    override suspend fun get(id: String): Alarm? {
        clearExpiredSkipsBestEffort()
        return dao.get(id)?.toAlarm()?.withoutExpiredSkip(clock.instant())
    }

    override suspend fun upsert(alarm: Alarm) = dao.upsert(alarm.toEntity())

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    override suspend fun setSkip(id: String, occurrence: Instant?) = dao.setSkip(id, occurrence?.toEpochMilli())

    override suspend fun setSoloSkip(id: String, occurrence: Instant?) = dao.setSoloSkip(id, occurrence?.toEpochMilli())

    override suspend fun upsertLinked(alarm: Alarm) {
        require(alarm.link != null) { "upsertLinked needs a linked alarm" }
        dao.upsertMerging(alarm.toEntity()) { stored, incoming ->
            // Never turn an own alarm into a linked row (I2): an id collision or LinkSync bug must fail loudly.
            check(stored.toAlarm().link != null) { "upsertLinked: row ${stored.id} exists and is not linked" }
            stored.copy(
                label = incoming.label,
                hour = incoming.hour,
                minute = incoming.minute,
                days = incoming.days,
                enabled = incoming.enabled,
                linkOwnerSkippedOccurrenceAt = incoming.linkOwnerSkippedOccurrenceAt,
            )
        }
    }

    override suspend fun updateLink(id: String, transform: (AlarmLink) -> AlarmLink): Boolean =
        dao.modify(id) { row ->
            val alarm = row.toAlarm()
            val link = alarm.link ?: return@modify null
            alarm.copy(link = transform(link)).toEntity()
        }

    /** The cleanup is only housekeeping: expiry is also applied in memory, so a failed write must not fail reads. */
    private suspend fun clearExpiredSkipsBestEffort() {
        try {
            dao.clearExpiredSkips(expiryCutoffMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Expired-skip cleanup failed; mapping expiry in memory", e) }
        }
    }

    private fun expiryCutoffMillis(): Long = skipExpiryCutoff(clock.instant()).toEpochMilli()
}

private const val TAG = "AlarmRepository"
