package me.shawnsquire.tandem.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
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
import me.shawnsquire.tandem.schedule.isArmed
import me.shawnsquire.tandem.schedule.nextOccurrence
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** A snooze that came due this recently (same span as auto-silence) is re-armed instead of dropped. */
internal val OVERDUE_SNOOZE_WINDOW: Duration = Duration.ofMinutes(10)
internal val OVERDUE_SNOOZE_DELAY: Duration = Duration.ofSeconds(5)

/**
 * Registers one `setAlarmClock` per enabled alarm, plus one per armed snooze in [ledger], from
 * local state only.
 * [now] and [zone] are read on every [rescheduleAll] so time and timezone changes are honored.
 * [onScheduled] gets the alarms read and the triggers armed at the end of every run (it feeds the
 * device-protected mirror); a failure there is logged and never fails the run.
 */
class AlarmSchedulerImpl(
    private val context: Context,
    private val alarmRepository: AlarmRepository,
    @Suppress("unused") private val ringStateStore: RingStateStore,
    private val ledger: SnoozeLedger,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val next: (Alarm, Instant, ZoneId) -> Instant? = ::nextOccurrence,
    private val isArmed: (Alarm) -> Boolean = ::isArmed,
    private val isSkipped: (Alarm, Instant) -> Boolean = ::isSkippedOccurrence,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onScheduled: suspend (alarms: List<Alarm>, triggers: Map<String, Long>) -> Unit = { _, _ -> },
) : AlarmScheduler, SnoozeArmer {

    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val prefs by lazy { deviceProtectedPrefs(context, PREFS) }

    // rescheduleAll() can run concurrently (boot, time change, UI, ringing); serialize the read-modify-write.
    private val mutex = Mutex()

    override suspend fun rescheduleAll() = rescheduleAll(rearmOverdueSnooze = false)

    /**
     * [rearmOverdueSnooze] is true only for boot and time changes: a snooze that came due while the
     * phone was off is re-armed. Otherwise an overdue snooze is left untouched, so a ring that
     * could not clear its own snooze state cannot re-arm itself in a loop. It also replays alarms
     * that came due within the window, including those earlier plain runs set aside as missed.
     */
    suspend fun rescheduleAll(rearmOverdueSnooze: Boolean) = mutex.withLock {
        runs.incrementAndGet()
        withContext(io) { rescheduleLocked(rearmOverdueSnooze) }
    }

    /** Tests only: how many reschedules have started, so startup can be checked to run exactly one. */
    @VisibleForTesting
    internal val runs = AtomicInteger()

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

        // Snoozes first: one bad alarm, or alarms that can't be read, must never cost the user a
        // pending snooze (I1). They come from the ledger, not the alarm repository.
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

        // A throw here (a locked, unreadable mirror) ends the run before any record is written or
        // PendingIntent cancelled. A reboot has already cleared AlarmManager, so the records re-arm
        // what the last readable run armed, and the throw still reaches the caller.
        val alarms = try {
            alarmRepository.alarms.first()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.e(TAG, "reading the alarms failed; re-arming from the scheduler records", e)
            rearmFromRecords(nowInstant, rearmOverdueSnooze)
            throw e
        }

        // What the previous run armed (alarmId -> triggerAt), read before anything is replaced.
        val old = readRecords()
        // Occurrences that came due within the window, from the previous records and from what
        // earlier plain runs set aside. A plain run (full startup, the UI, a ring) can take the
        // mutex before the boot or time-change rearm run, and its records would otherwise drop the
        // occurrence it moves past; so it carries them forward and the rearm run replays them.
        val recent = recentlyDue(old, nowInstant)
        // Replays armed by this very run; the cleanup below must not take them back.
        val replayed = if (rearmOverdueSnooze) armReplays(recent, alarms, nowInstant) else emptySet()
        val missed = if (rearmOverdueSnooze) emptyMap() else recent
        // Persisted before any PendingIntent is registered, so a process death in between
        // cannot orphan one.
        val persisted = old.records.toMutableMap()
        val scheduledNow = mutableMapOf<String, Long?>()
        for (alarm in alarms) {
            try {
                val at = if (isArmed(alarm)) next(alarm, nowInstant, zoneId) else null
                if (at == null) continue
                if (!at.isAfter(nowInstant)) {
                    // Would fire immediately, reschedule itself and loop.
                    Log.w(TAG, "ignoring non-future trigger for alarm ${alarm.id}: $at <= $nowInstant")
                    continue
                }
                if (persisted[alarm.id] != at.toEpochMilli()) {
                    persisted[alarm.id] = at.toEpochMilli()
                    // With the missed entries, so a record replaced here is never lost.
                    writeRecords(persisted, old.legacyIds, missed)
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
                // A replay armed at boot for an alarm disabled or deleted within its 5 s window; not
                // one armed above for an alarm that is due now but has no next occurrence.
                if (id !in replayed) cancel(AlarmIntents.existingReplayPendingIntent(context, id))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "cancel failed for alarm $id", e)
            }
        }
        // commit(): the records must be durable before we return (receivers may kill the process).
        val triggers = scheduledNow.mapNotNull { (id, at) -> at?.let { id to it } }.toMap()
        writeRecords(triggers, emptySet(), missed, dropLegacy = true)
        try {
            onScheduled(alarms, triggers)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.e(TAG, "onScheduled failed", e)
        }
    }

    /**
     * The alarms can't be read: arms every future record again at its time, plus the replays a
     * rearm run would arm, exactly as a normal run would. Records and missed entries stay as they
     * are for the next run that can read the alarms, and nothing is cancelled or mirrored. Each
     * record was enabled and unskipped when it was armed; that can't be re-checked now, and I1
     * says ring when local state can't be read (a fire with an unreadable mirror rings with
     * defaults). One bad record never stops the rest.
     */
    private fun rearmFromRecords(nowInstant: Instant, rearmOverdueSnooze: Boolean) {
        val old = readRecords()
        if (rearmOverdueSnooze) armReplays(recentlyDue(old, nowInstant), alarms = null, nowInstant)
        for ((id, atMs) in old.records) {
            try {
                if (atMs <= nowInstant.toEpochMilli()) continue
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(atMs, AlarmIntents.showPendingIntent(context)),
                    AlarmIntents.firePendingIntent(context, id, Instant.ofEpochMilli(atMs)),
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "re-arming from the record failed for alarm $id", e)
            }
        }
    }

    /**
     * Per alarm, the latest occurrence in `(now - OVERDUE_SNOOZE_WINDOW, now]` among the previous
     * records and the missed entries; older ones are pruned. Latest, because the replay
     * PendingIntent is one per alarm. An entry for an occurrence that did ring is harmless: its
     * replay finds it in the FiredLedger and RingingSession finishes quietly.
     */
    private fun recentlyDue(old: Records, nowInstant: Instant): Map<String, Long> {
        val earliest = nowInstant.minus(OVERDUE_SNOOZE_WINDOW).toEpochMilli()
        val latest = nowInstant.toEpochMilli()
        val recent = HashMap<String, Long>()
        for ((id, atMs) in old.missed.entries + old.records.entries) {
            if (atMs <= earliest || atMs > latest) continue
            if ((recent[id] ?: Long.MIN_VALUE) < atMs) recent[id] = atMs
        }
        return recent
    }

    /**
     * Boot or time change: an occurrence that fell inside the last [OVERDUE_SNOOZE_WINDOW] while
     * nothing was running rings shortly, through AlarmReceiver like any other fire. One bad record
     * never stops the rest. Null [alarms] means they couldn't be read: every one is replayed (I1).
     * Returns the ids whose replay was armed.
     */
    private fun armReplays(previous: Map<String, Long>, alarms: List<Alarm>?, nowInstant: Instant): Set<String> {
        val armed = mutableSetOf<String>()
        val byId = alarms?.associateBy { it.id }
        val earliest = nowInstant.minus(OVERDUE_SNOOZE_WINDOW).toEpochMilli()
        for ((id, atMs) in previous) {
            try {
                if (atMs <= earliest || atMs > nowInstant.toEpochMilli()) continue
                val at = Instant.ofEpochMilli(atMs)
                if (byId != null) {
                    val alarm = byId[id] ?: continue
                    if (!isArmed(alarm) || isSkipped(alarm, at)) continue
                }
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(nowInstant.plus(OVERDUE_SNOOZE_DELAY).toEpochMilli(), AlarmIntents.showPendingIntent(context)),
                    AlarmIntents.replayPendingIntent(context, id, at),
                )
                armed += id
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "replay arming failed for alarm $id", e)
            }
        }
        return armed
    }

    private class Records(val records: Map<String, Long>, val legacyIds: Set<String>, val missed: Map<String, Long>)

    private fun readRecords(): Records = try {
        val records = readTimes(KEY_RECORDS)
        // Older builds persisted only the ids; they have no time, so they can be cancelled but never replayed.
        val legacy = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().toSet() - records.keys
        Records(records, legacy, readTimes(KEY_MISSED))
    } catch (e: Throwable) {
        Log.e(TAG, "reading scheduler records failed", e)
        Records(emptyMap(), emptySet(), emptyMap())
    }

    private fun readTimes(key: String): Map<String, Long> {
        val times = HashMap<String, Long>()
        for (entry in prefs.getStringSet(key, emptySet()).orEmpty()) {
            val sep = entry.indexOf('|')
            val ms = if (sep > 0) entry.substring(0, sep).toLongOrNull() else null
            if (ms != null) times[entry.substring(sep + 1)] = ms
        }
        return times
    }

    /** One commit for the records and the missed entries, so neither is ever durable without the other. */
    private fun writeRecords(records: Map<String, Long>, legacyIds: Set<String>, missed: Map<String, Long>, dropLegacy: Boolean = false) {
        val edit = prefs.edit()
            .putStringSet(KEY_RECORDS, records.map { (id, at) -> "$at|$id" }.toSet())
            .putStringSet(KEY_MISSED, missed.map { (id, at) -> "$at|$id" }.toSet())
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
        const val KEY_MISSED = "missed_records" // same format: came due within the window, not yet replayed
    }
}
