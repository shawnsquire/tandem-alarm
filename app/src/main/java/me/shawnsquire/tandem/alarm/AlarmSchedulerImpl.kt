package me.shawnsquire.tandem.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.nextOccurrence
import java.time.Instant
import java.time.ZoneId

/**
 * Registers one `setAlarmClock` per enabled alarm (plus the pending snooze) from local state only.
 * [now] and [zone] are read on every [rescheduleAll] so time and timezone changes are honored.
 */
class AlarmSchedulerImpl(
    private val context: Context,
    private val alarmRepository: AlarmRepository,
    private val ringStateStore: RingStateStore,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val next: (Alarm, Instant, ZoneId) -> Instant? = ::nextOccurrence,
) : AlarmScheduler {

    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // rescheduleAll() can run concurrently (boot, time change, UI, ringing); serialize the read-modify-write.
    private val mutex = Mutex()

    override suspend fun rescheduleAll() = mutex.withLock {
        rescheduleLocked()
    }

    private suspend fun rescheduleLocked() {
        val nowInstant = now()
        val zoneId = zone()
        val alarms = alarmRepository.alarms.first()
        val ring = ringStateStore.state.first()

        val scheduledNow = mutableSetOf<String>()
        for (alarm in alarms) {
            val at = if (alarm.enabled) next(alarm, nowInstant, zoneId) else null
            if (at == null) continue
            val operation = AlarmIntents.firePendingIntent(context, alarm.id, at)
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(at.toEpochMilli(), AlarmIntents.showPendingIntent(context)),
                operation,
            )
            scheduledNow += alarm.id
        }

        val previous = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty()
        for (id in previous - scheduledNow) {
            cancel(AlarmIntents.existingFirePendingIntent(context, id))
        }
        // commit(): the persisted set must be durable before we return (receivers may kill the process).
        prefs.edit().putStringSet(KEY_SCHEDULED_IDS, scheduledNow).commit()

        val snoozeAt = ring?.snoozedUntil?.takeIf { it.isAfter(nowInstant) }
        if (ring != null && snoozeAt != null) {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(snoozeAt.toEpochMilli(), AlarmIntents.showPendingIntent(context)),
                AlarmIntents.snoozePendingIntent(context, ring.alarmId, ring.ringId, snoozeAt),
            )
        } else {
            cancel(AlarmIntents.existingSnoozePendingIntent(context))
        }
    }

    private fun cancel(pi: PendingIntent?) {
        if (pi == null) return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private companion object {
        const val PREFS = "alarm_scheduler"
        const val KEY_SCHEDULED_IDS = "scheduled_ids"
    }
}
