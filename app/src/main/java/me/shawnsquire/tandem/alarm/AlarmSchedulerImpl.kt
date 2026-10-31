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
import me.shawnsquire.tandem.schedule.nextOccurrence
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** A snooze that came due this recently (same span as auto-silence) is re-armed instead of dropped. */
internal val OVERDUE_SNOOZE_WINDOW: Duration = Duration.ofMinutes(10)
internal val OVERDUE_SNOOZE_DELAY: Duration = Duration.ofSeconds(5)

/**
 * Registers one `setAlarmClock` per enabled alarm, plus one per armed snooze in [ledger], from
 * local state only.
 * [now] and [zone] are read on every [rescheduleAll] so time and timezone changes are honored.
 */
class AlarmSchedulerImpl(
    private val context: Context,
    private val alarmRepository: AlarmRepository,
    @Suppress("unused") private val ringStateStore: RingStateStore,
    private val ledger: SnoozeLedger,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val next: (Alarm, Instant, ZoneId) -> Instant? = ::nextOccurrence,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AlarmScheduler, SnoozeArmer {

    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // rescheduleAll() can run concurrently (boot, time change, UI, ringing); serialize the read-modify-write.
    private val mutex = Mutex()

    override suspend fun rescheduleAll() = rescheduleAll(rearmOverdueSnooze = false)

    /**
     * [rearmOverdueSnooze] is true only for boot and time changes: a snooze that came due while the
     * phone was off is re-armed. Otherwise an overdue snooze is left untouched, so a ring that
     * could not clear its own snooze state cannot re-arm itself in a loop.
     */
    suspend fun rescheduleAll(rearmOverdueSnooze: Boolean) = mutex.withLock {
        withContext(io) { rescheduleLocked(rearmOverdueSnooze) }
    }

    /** Arms the snooze alarm for [ringId] directly; throws on failure so the caller can keep ringing. */
    override fun armSnooze(alarmId: String, ringId: String, at: Instant) {
        setSnoozeAlarm(alarmId, ringId, at)
    }

    override fun cancelSnooze(ringId: String) {
        cancel(AlarmIntents.existingSnoozePendingIntent(context, ringId))
    }

    private suspend fun rescheduleLocked(rearmOverdueSnooze: Boolean) {
        val nowInstant = now()
        val zoneId = zone()
        val alarms = alarmRepository.alarms.first()

        // Snoozes first: one bad alarm must never cost the user a pending snooze (I1).
        val snoozes = try {
            ledger.all()
        } catch (e: Throwable) {
            Log.e(TAG, "snooze ledger read failed", e)
            emptyList()
        }
        for (entry in snoozes) {
            try {
                armSnoozeFromLedger(entry, nowInstant, rearmOverdueSnooze)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "snooze scheduling failed for ring ${entry.ringId}", e)
            }
        }

        // What the previous run armed (alarmId -> triggerAt), read before anything is replaced.
        val old = readRecords()
        if (rearmOverdueSnooze) {
            armReplays(old.records, alarms, nowInstant)
        }
        // Persisted before any PendingIntent is registered, so a process death in between
        // cannot orphan one.
        val persisted = old.records.toMutableMap()
        val scheduledNow = mutableMapOf<String, Long?>()
        for (alarm in alarms) {
            try {
                val at = if (alarm.enabled) next(alarm, nowInstant, zoneId) else null
                if (at == null) continue
                if (!at.isAfter(nowInstant)) {
                    // Would fire immediately, reschedule itself and loop.
                    Log.w(TAG, "ignoring non-future trigger for alarm ${alarm.id}: $at <= $nowInstant")
                    continue
                }
                if (persisted[alarm.id] != at.toEpochMilli()) {
                    persisted[alarm.id] = at.toEpochMilli()
                    writeRecords(persisted, old.legacyIds)
                }
                // Added before arming: if setAlarmClock throws, the existing PendingIntent survives cleanup.
                scheduledNow[alarm.id] = at.toEpochMilli()
                val operation = AlarmIntents.firePendingIntent(context, alarm.id, at)
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(at.toEpochMilli(), AlarmIntents.showPendingIntent(context)),
                    operation,
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "scheduling failed for alarm ${alarm.id}", e)
                // Keep whatever is already registered for it.
                scheduledNow[alarm.id] = persisted[alarm.id]
            }
        }

        for (id in (old.records.keys + old.legacyIds) - scheduledNow.keys) {
            try {
                cancel(AlarmIntents.existingFirePendingIntent(context, id))
                // A replay armed at boot for an alarm disabled or deleted within its 5 s window.
                cancel(AlarmIntents.existingReplayPendingIntent(context, id))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "cancel failed for alarm $id", e)
            }
        }
        // commit(): the records must be durable before we return (receivers may kill the process).
        writeRecords(scheduledNow.mapNotNull { (id, at) -> at?.let { id to it } }.toMap(), emptySet(), dropLegacy = true)
    }

    /**
     * Boot or time change: an occurrence that fell inside the last [OVERDUE_SNOOZE_WINDOW] while
     * nothing was running rings shortly, through AlarmReceiver like any other fire. One bad record
     * never stops the rest.
     */
    private fun armReplays(previous: Map<String, Long>, alarms: List<Alarm>, nowInstant: Instant) {
        val byId = alarms.associateBy { it.id }
        val earliest = nowInstant.minus(OVERDUE_SNOOZE_WINDOW).toEpochMilli()
        for ((id, atMs) in previous) {
            try {
                if (atMs <= earliest || atMs > nowInstant.toEpochMilli()) continue
                val alarm = byId[id] ?: continue
                val at = Instant.ofEpochMilli(atMs)
                if (!alarm.enabled || alarm.skippedOccurrenceAt == at) continue
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(nowInstant.plus(OVERDUE_SNOOZE_DELAY).toEpochMilli(), AlarmIntents.showPendingIntent(context)),
                    AlarmIntents.replayPendingIntent(context, id, at),
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "replay arming failed for alarm $id", e)
            }
        }
    }

    private class Records(val records: Map<String, Long>, val legacyIds: Set<String>)

    private fun readRecords(): Records = try {
        val records = HashMap<String, Long>()
        for (entry in prefs.getStringSet(KEY_RECORDS, emptySet()).orEmpty()) {
            val sep = entry.indexOf('|')
            val ms = if (sep > 0) entry.substring(0, sep).toLongOrNull() else null
            if (ms != null) records[entry.substring(sep + 1)] = ms
        }
        // Older builds persisted only the ids; they have no time, so they can be cancelled but never replayed.
        val legacy = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().toSet() - records.keys
        Records(records, legacy)
    } catch (e: Throwable) {
        Log.e(TAG, "reading scheduler records failed", e)
        Records(emptyMap(), emptySet())
    }

    private fun writeRecords(records: Map<String, Long>, legacyIds: Set<String>, dropLegacy: Boolean = false) {
        val edit = prefs.edit().putStringSet(KEY_RECORDS, records.map { (id, at) -> "$at|$id" }.toSet())
        if (dropLegacy) edit.remove(KEY_SCHEDULED_IDS) else edit.putStringSet(KEY_SCHEDULED_IDS, legacyIds + records.keys)
        edit.commit()
    }

    /**
     * Only ledger entries are touched: a snooze armed but not yet written to the ledger (the
     * controller arms first) must survive a concurrent reschedule.
     */
    private fun armSnoozeFromLedger(entry: SnoozeEntry, nowInstant: Instant, rearmOverdue: Boolean) {
        val until = entry.until
        val snoozeAt = when {
            until.isAfter(nowInstant) -> until
            until.isAfter(nowInstant.minus(OVERDUE_SNOOZE_WINDOW)) -> {
                if (!rearmOverdue) return // leave whatever is registered untouched
                // Came due while the phone was off: bring the ring back shortly rather than dropping it.
                nowInstant.plus(OVERDUE_SNOOZE_DELAY)
            }
            else -> {
                // Long past: it can no longer ring.
                cancel(AlarmIntents.existingSnoozePendingIntent(context, entry.ringId))
                ledger.remove(entry.ringId)
                return
            }
        }
        setSnoozeAlarm(entry.alarmId, entry.ringId, snoozeAt)
        // Dismissed while we were arming it: the controller removes the entry before cancelling,
        // so re-checking here closes the race in every interleaving.
        if (ledger.get(entry.ringId) == null) cancel(AlarmIntents.existingSnoozePendingIntent(context, entry.ringId))
    }

    private fun setSnoozeAlarm(alarmId: String, ringId: String, at: Instant) {
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(at.toEpochMilli(), AlarmIntents.showPendingIntent(context)),
            AlarmIntents.snoozePendingIntent(context, alarmId, ringId, at),
        )
    }

    private fun cancel(pi: PendingIntent?) {
        if (pi == null) return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private companion object {
        const val TAG = "AlarmScheduler"
        const val PREFS = "alarm_scheduler"
        const val KEY_SCHEDULED_IDS = "scheduled_ids" // legacy: plain id set
        const val KEY_RECORDS = "scheduled_records" // "<triggerAtMs>|<alarmId>"
    }
}
