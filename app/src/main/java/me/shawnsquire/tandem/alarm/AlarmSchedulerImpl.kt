package me.shawnsquire.tandem.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.nextOccurrence
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** A snooze that came due this recently (same span as auto-silence) is re-armed instead of dropped. */
internal val OVERDUE_SNOOZE_WINDOW: Duration = Duration.ofMinutes(10)
internal val OVERDUE_SNOOZE_DELAY: Duration = Duration.ofSeconds(5)

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
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AlarmScheduler {

    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // rescheduleAll() can run concurrently (boot, time change, UI, ringing); serialize the read-modify-write.
    private val mutex = Mutex()

    override suspend fun rescheduleAll() = mutex.withLock {
        withContext(io) { rescheduleLocked() }
    }

    private suspend fun rescheduleLocked() {
        val nowInstant = now()
        val zoneId = zone()
        val alarms = alarmRepository.alarms.first()
        val ring = ringStateStore.state.first()

        // Snooze first: one bad alarm must never cost the user a pending snooze (I1).
        try {
            armSnooze(ring, nowInstant)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.e(TAG, "snooze scheduling failed", e)
        }

        val previous = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().toSet()
        // Ids persisted before any PendingIntent is registered, so a process death in between
        // cannot orphan one.
        val persisted = previous.toMutableSet()
        val scheduledNow = mutableSetOf<String>()
        for (alarm in alarms) {
            try {
                val at = if (alarm.enabled) next(alarm, nowInstant, zoneId) else null
                if (at == null) continue
                if (!at.isAfter(nowInstant)) {
                    // Would fire immediately, reschedule itself and loop.
                    Log.w(TAG, "ignoring non-future trigger for alarm ${alarm.id}: $at <= $nowInstant")
                    continue
                }
                if (persisted.add(alarm.id)) {
                    prefs.edit().putStringSet(KEY_SCHEDULED_IDS, persisted.toSet()).commit()
                }
                // Added before arming: if setAlarmClock throws, the existing PendingIntent survives cleanup.
                scheduledNow += alarm.id
                val operation = AlarmIntents.firePendingIntent(context, alarm.id, at)
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(at.toEpochMilli(), AlarmIntents.showPendingIntent(context)),
                    operation,
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "scheduling failed for alarm ${alarm.id}", e)
                // Keep whatever is already registered for it.
                scheduledNow += alarm.id
            }
        }

        for (id in previous - scheduledNow) {
            try {
                cancel(AlarmIntents.existingFirePendingIntent(context, id))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "cancel failed for alarm $id", e)
            }
        }
        // commit(): the persisted set must be durable before we return (receivers may kill the process).
        prefs.edit().putStringSet(KEY_SCHEDULED_IDS, scheduledNow).commit()
    }

    private fun armSnooze(ring: RingState?, nowInstant: Instant) {
        val until = ring?.snoozedUntil
        val snoozeAt = when {
            until == null -> null
            until.isAfter(nowInstant) -> until
            // Came due while the phone was off: bring the ring back shortly rather than dropping it.
            until.isAfter(nowInstant.minus(OVERDUE_SNOOZE_WINDOW)) -> nowInstant.plus(OVERDUE_SNOOZE_DELAY)
            else -> null
        }
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
        const val TAG = "AlarmScheduler"
        const val PREFS = "alarm_scheduler"
        const val KEY_SCHEDULED_IDS = "scheduled_ids"
    }
}
