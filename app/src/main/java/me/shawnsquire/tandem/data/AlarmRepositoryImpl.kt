package me.shawnsquire.tandem.data

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.time.toJavaDuration
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.FIRE_GRACE

class AlarmRepositoryImpl(
    private val dao: AlarmDao,
    private val clock: Clock,
) : AlarmRepository {

    override val alarms: Flow<List<Alarm>> = dao.observeAll()
        .onStart { dao.clearExpiredSkips(expiryCutoffMillis()) }
        .map { rows ->
            val now = clock.instant()
            rows.map { it.toAlarm().withoutExpiredSkip(now) }
        }

    override suspend fun get(id: String): Alarm? {
        dao.clearExpiredSkips(expiryCutoffMillis())
        return dao.get(id)?.toAlarm()?.withoutExpiredSkip(clock.instant())
    }

    override suspend fun upsert(alarm: Alarm) = dao.upsert(alarm.toEntity())

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    override suspend fun setSkip(id: String, occurrence: Instant?) = dao.setSkip(id, occurrence?.toEpochMilli())

    /** A skip at or before this instant is expired; later ones stay for [FIRE_GRACE] past firing. */
    private fun expiryCutoffMillis(): Long = clock.millis() - FIRE_GRACE.inWholeMilliseconds

    private fun Alarm.withoutExpiredSkip(now: Instant): Alarm =
        if (skippedOccurrenceAt != null && !skippedOccurrenceAt.isAfter(now.minus(FIRE_GRACE.toJavaDuration()))) copy(skippedOccurrenceAt = null) else this
}
