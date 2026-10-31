package me.shawnsquire.tandem.alarm

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.isOneShot
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * App-scoped; works purely on the persisted [RingStateStore] and [SnoozeLedger], so it needs no
 * running service (dismiss while snoozed, remote stop). A live RingingSession observes the store
 * and stops sound and the service when the ring ends or is snoozed.
 *
 * Every public entry point takes [mutex] exactly once; the `*Locked` helpers assume it is held
 * (the Mutex is not reentrant).
 */
class RingingControllerImpl(
    private val alarmRepository: AlarmRepository,
    private val ringStateStore: RingStateStore,
    private val settingsRepository: SettingsRepository,
    private val alarmScheduler: AlarmScheduler,
    private val snoozeArmer: SnoozeArmer,
    private val notifier: RingingNotifier,
    private val ledger: SnoozeLedger,
    private val now: () -> Instant = Instant::now,
) : RingingController, RingSlot {
    private val mutex = Mutex()

    override suspend fun dismiss(ringId: String) = withContext(NonCancellable) { mutex.withLock {
        val state = ringStateStore.state.first()
        when {
            state != null && state.ringId == ringId -> dismissCurrentLocked(state)
            // A snooze parked behind a newer ring (Dismiss on its snoozed notification).
            else -> {
                val parked = ledger.get(ringId)
                if (parked != null) {
                    dropParkedLocked(parked)
                } else {
                    // I3: a stop pinned to another (or no) ring is ignored. It stops no ring, but a
                    // snoozed notification whose ledger entry is gone would otherwise stay forever.
                    bestEffort("cancel stuck snoozed notification") { notifier.cancelSnoozed(ringId) }
                }
            }
        }
        Unit
    } }

    override suspend fun snooze() = withContext(NonCancellable) { mutex.withLock {
        val state = ringStateStore.state.first()
        if (state == null || state.snoozedUntil != null) return@withLock
        val minutes = try {
            settingsRepository.snoozeMinutes.first()
        } catch (e: Exception) {
            DEFAULT_SNOOZE_MINUTES
        }
        val until = now().plus(minutes.toLong(), ChronoUnit.MINUTES)
        // Arm first: if this throws nothing is written and the ring continues.
        snoozeArmer.armSnooze(state.alarmId, state.ringId, until)
        try {
            ledger.put(state.ringId, state.alarmId, until)
        } catch (e: Throwable) {
            runCatching { snoozeArmer.cancelSnooze(state.ringId) }
            throw e
        }
        try {
            ringStateStore.set(state.copy(snoozedUntil = until))
        } catch (e: Throwable) {
            // Not snoozed after all: the ring keeps sounding, so take the snooze back.
            runCatching { ledger.remove(state.ringId) }
            runCatching { snoozeArmer.cancelSnooze(state.ringId) }
            throw e
        }
        try {
            alarmScheduler.rescheduleAll()
        } catch (e: Throwable) {
            // The snooze is already armed; the rest of the schedule is best effort.
            log("rescheduleAll failed after arming snooze", e)
        }
        runCatching { notifier.showSnoozed(state.ringId, until) }
        Unit
    } }

    override suspend fun claimFire(newRing: RingState) = withContext(NonCancellable) { mutex.withLock {
        val current = try {
            ringStateStore.state.first()
        } catch (e: Throwable) {
            log("state read failed; claiming the slot without superseding", e)
            null
        }
        supersedeLocked(current, newRing.ringId)
        // Only this write may throw: a throw means the new ring was not written.
        ringStateStore.set(newRing)
    } }

    override suspend fun claimSnoozeResume(ringId: String, alarmId: String): Boolean =
        withContext(NonCancellable) { mutex.withLock {
            // A failed read here throws: the caller rings anyway (I1).
            val inLedger = ledger.get(ringId) != null
            val current = ringStateStore.state.first()
            val sameRing = current != null && current.ringId == ringId
            val live = inLedger || (sameRing && current.snoozedUntil != null)
            if (!live) {
                // Dismissed while snoozed; make sure no snoozed notification outlives its entry.
                bestEffort("cancel stale snoozed notification") { notifier.cancelSnoozed(ringId) }
                return@withLock false
            }

            bestEffort("ledger remove") { ledger.remove(ringId) }
            bestEffort("cancel fired snooze") { snoozeArmer.cancelSnooze(ringId) }
            bestEffort("cancel snoozed notification") { notifier.cancelSnoozed(ringId) }
            // Never supersede itself: if the slot already holds this ring, just overwrite it.
            if (!sameRing) supersedeLocked(current, ringId)
            val startedAt = if (sameRing) current.startedAt else now()
            ringStateStore.set(RingState(alarmId, ringId, startedAt, null))
            true
        } }

    override suspend fun clearStale(isLive: (String) -> Boolean, reschedule: Boolean) = withContext(NonCancellable) {
        mutex.withLock {
            val state = try {
                ringStateStore.state.first()
            } catch (e: Throwable) {
                log("state read failed; leaving ring state alone", e)
                return@withLock
            } ?: return@withLock
            // A failing registry means we cannot tell: treat the ring as live and leave it.
            val live = try { isLive(state.ringId) } catch (e: Throwable) { log("isLive failed", e); true }
            if (live) return@withLock
            val snoozedUntil = state.snoozedUntil
            if (snoozedUntil != null && snoozedUntil.isAfter(now().minus(STALE_SNOOZE_WINDOW))) return@withLock
            // Not a dismiss: a one-shot stays enabled and the snooze ledger is left to the scheduler.
            ringStateStore.set(null)
            if (snoozedUntil != null) {
                bestEffort("cancel stale snoozed notification") { notifier.cancelSnoozed(state.ringId) }
            } else {
                bestEffort("cancel stale ringing notification") { notifier.cancelRinging(state.ringId) }
            }
            if (reschedule) bestEffort("reschedule after clearing stale ring") { alarmScheduler.rescheduleAll() }
        }
    }

    /** Dismisses [current] if it is a different ring that is actively sounding; parks a snoozed one. Never throws. */
    private suspend fun supersedeLocked(current: RingState?, newRingId: String) {
        if (current == null || current.ringId == newRingId || current.snoozedUntil != null) return
        try {
            dismissCurrentLocked(current)
        } catch (e: Throwable) {
            log("superseding ring ${current.ringId} failed", e)
        }
    }

    /** Ends [state], the ring in the slot: clear it, drop any snooze, disable a one-shot, reschedule. */
    private suspend fun dismissCurrentLocked(state: RingState) {
        ringStateStore.set(null)
        // Once state is cleared every remaining step must still run; surface the first failure last.
        val steps = Steps()
        // Ledger before the PendingIntent and the reschedule: the scheduler arms from the ledger.
        steps.run { ledger.remove(state.ringId) }
        steps.run { snoozeArmer.cancelSnooze(state.ringId) }
        steps.run { disableIfOneShot(state.alarmId) }
        steps.run { notifier.cancelSnoozed(state.ringId) }
        steps.run { alarmScheduler.rescheduleAll() }
        steps.rethrow()
    }

    /** Dismisses a snooze parked behind another ring. RingState is not touched. */
    private suspend fun dropParkedLocked(entry: SnoozeEntry) {
        val steps = Steps()
        steps.run { ledger.remove(entry.ringId) }
        steps.run { snoozeArmer.cancelSnooze(entry.ringId) }
        steps.run { notifier.cancelSnoozed(entry.ringId) }
        steps.run { disableIfOneShot(entry.alarmId) }
        steps.run { alarmScheduler.rescheduleAll() }
        steps.rethrow()
    }

    private suspend fun disableIfOneShot(alarmId: String) {
        val alarm = alarmRepository.get(alarmId)
        if (alarm != null && alarm.isOneShot()) alarmRepository.setEnabled(alarm.id, false)
    }

    private class Steps {
        private var failure: Throwable? = null
        suspend fun run(block: suspend () -> Unit) {
            try { block() } catch (e: Throwable) { if (failure == null) failure = e }
        }
        fun rethrow() { failure?.let { throw it } }
    }

    private inline fun bestEffort(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { log("$what failed", e) }
    }

    private fun log(msg: String, e: Throwable) {
        runCatching { Log.e(TAG, msg, e) }
    }

    private companion object {
        val STALE_SNOOZE_WINDOW: Duration = Duration.ofMinutes(10)
        const val DEFAULT_SNOOZE_MINUTES = 10
        const val TAG = "RingingController"
    }
}
