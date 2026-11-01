package me.shawnsquire.tandem.alarm

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.DEFAULT_SNOOZE_MINUTES
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.schedule.ParkedSnooze
import me.shawnsquire.tandem.schedule.RingContext
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingOrigin
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.isOneShot
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * App-scoped; works purely on the persisted [RingStateStore] and [SnoozeLedger], so it needs no
 * running service (dismiss while snoozed, remote stop). A live RingingSession observes the store
 * and stops sound and the service when the ring ends or is snoozed.
 *
 * Every public entry point takes [mutex] exactly once; the `*Locked` helpers assume it is held
 * (the Mutex is not reentrant).
 *
 * [mirror] is called once per LOCAL snooze or dismiss with every member it resolved, never for
 * REMOTE (I4). Which members actually go out (linked, joined) is the port's call.
 */
class RingingControllerImpl(
    private val alarmRepository: AlarmRepository,
    private val ringStateStore: RingStateStore,
    private val settingsRepository: SettingsRepository,
    private val alarmScheduler: AlarmScheduler,
    private val snoozeArmer: SnoozeArmer,
    private val notifier: RingingNotifier,
    private val ledger: SnoozeLedger,
    private val mirror: RingMirrorPort = NoOpRingMirrorPort,
    private val firedLedger: FiredLedger = InMemoryFiredLedger(),
    private val newRingId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Instant = Instant::now,
) : RingingController, RingSlot {
    private val mutex = Mutex()

    override suspend fun ringContext(alarmId: String): RingContext = mutex.withLock {
        val ring = ringStateStore.state.first()
        RingContext(
            ring = ring,
            merged = ring?.merged.orEmpty(),
            snoozes = ledger.all().map { ParkedSnooze(it.alarmId, it.occurrenceAt, it.until) },
            lastRang = firedLedger.lastRang(alarmId),
        )
    }

    /** Resolves the primary and every merged member; see [RingingController.dismiss]. */
    override suspend fun dismiss(ringId: String, origin: RingOrigin) = withContext(NonCancellable) { mutex.withLock {
        val state = ringStateStore.state.first()
        val local = origin == RingOrigin.LOCAL
        when {
            state != null && state.ringId == ringId -> dismissCurrentLocked(state, local)
            // A snooze parked behind a newer ring (Dismiss on its snoozed notification).
            else -> {
                val parked = ledger.get(ringId)
                if (parked != null) {
                    dropParkedLocked(parked, local)
                } else {
                    // I3: a stop pinned to another (or no) ring is ignored. It stops no ring, but a
                    // snoozed notification whose ledger entry is gone would otherwise stay forever.
                    bestEffort("cancel stuck snoozed notification") { notifier.cancelSnoozed(ringId) }
                }
            }
        }
        Unit
    } }

    /** Stub until L2; see [RingingController.dismissOccurrence]. */
    override suspend fun dismissOccurrence(alarmId: String, occurrenceAt: Instant, origin: RingOrigin) = Unit

    /** Stub until L2; see [RingingController.snoozeOccurrence]. */
    override suspend fun snoozeOccurrence(alarmId: String, occurrenceAt: Instant, until: Instant, origin: RingOrigin) = Unit

    override suspend fun writeUnlessRinging(alarmId: String, occurrenceAt: Instant?, write: suspend () -> Unit): Boolean =
        withContext(NonCancellable) { mutex.withLock {
            // A failed read throws before anything is written: the command is not applied.
            val state = ringStateStore.state.first()
            val ring = state?.takeIf { it.alarmId == alarmId }
            if (ring != null && (occurrenceAt == null || ring.startedAt >= occurrenceAt)) return@withLock false
            // A merged member is ringing too.
            val member = state?.merged.orEmpty().any { it.alarmId == alarmId && (occurrenceAt == null || it.occurrenceAt >= occurrenceAt) }
            if (member) return@withLock false
            write()
            true
        } }

    /**
     * Snoozes the primary in the slot and splits each merged member out into its own snooze (fresh
     * ringId, same end): left in the slot's `merged` it would be lost once this snooze is parked, and
     * [ringContext] couldn't see it. They come back at the end as separate snooze fires and re-merge.
     * See [RingingController.snooze].
     */
    override suspend fun snooze(origin: RingOrigin, until: Instant?) = withContext(NonCancellable) { mutex.withLock {
        val state = ringStateStore.state.first()
        if (state == null || state.snoozedUntil != null) return@withLock
        val end = until ?: run {
            val minutes = try {
                settingsRepository.snoozeMinutes.first()
            } catch (e: Exception) {
                DEFAULT_SNOOZE_MINUTES
            }
            now().plus(minutes.toLong(), ChronoUnit.MINUTES)
        }
        val split = state.merged.map { newRingId() to it }
        // Arm first: if anything throws nothing stays written and the ring continues with all its members.
        val armed = mutableListOf<String>()
        try {
            armSnoozeLocked(state.ringId, state.alarmId, end, state.occurrenceAt, armed)
            for ((id, member) in split) armSnoozeLocked(id, member.alarmId, end, member.occurrenceAt, armed)
            ringStateStore.set(state.copy(snoozedUntil = end, merged = emptyList()))
        } catch (e: Throwable) {
            // Not snoozed after all: the ring keeps sounding, so take every snooze back.
            for (id in armed) {
                runCatching { ledger.remove(id) }
                runCatching { snoozeArmer.cancelSnooze(id) }
            }
            throw e
        }
        try {
            alarmScheduler.rescheduleAll()
        } catch (e: Throwable) {
            // The snooze is already armed; the rest of the schedule is best effort.
            log("rescheduleAll failed after arming snooze", e)
        }
        runCatching { notifier.showSnoozed(state.ringId, end) }
        for ((id, _) in split) runCatching { notifier.showSnoozed(id, end) }
        if (origin == RingOrigin.LOCAL) mirrorLocal(MirrorKind.SNOOZE, state.members(), end)
        Unit
    } }

    /** Arms [ringId]'s snooze, then writes its ledger entry; [armed] gets [ringId] once there is anything to take back. */
    private fun armSnoozeLocked(ringId: String, alarmId: String, end: Instant, occurrenceAt: Instant?, armed: MutableList<String>) {
        snoozeArmer.armSnooze(alarmId, ringId, end)
        armed += ringId
        ledger.put(ringId, alarmId, end, occurrenceAt)
    }

    override suspend fun claimFire(newRing: RingState, stillDue: suspend () -> Boolean): SlotClaim = withContext(NonCancellable) { mutex.withLock {
        val due = try {
            stillDue()
        } catch (e: Throwable) {
            log("fire re-check failed; treating the ring as due", e)
            true
        }
        if (!due) return@withLock SlotClaim.NotDue
        val current = try {
            ringStateStore.state.first()
        } catch (e: Throwable) {
            log("state read failed; claiming the slot without superseding", e)
            null
        }
        val member = newRing.occurrenceAt?.let { RingMember(newRing.alarmId, it) }
        // Only the merge write or the write below may throw: a throw means the new ring was not written.
        if (mergeLocked(current, newRing.ringId, member)) return@withLock SlotClaim.Merged
        supersedeLocked(current, newRing.ringId)
        ringStateStore.set(newRing)
        SlotClaim.Claimed
    } }

    override suspend fun claimSnoozeResume(ringId: String, alarmId: String): SlotClaim =
        withContext(NonCancellable) { mutex.withLock {
            // A failed read here throws: the caller rings anyway (I1).
            val entry = ledger.get(ringId)
            val inLedger = entry != null
            val current = ringStateStore.state.first()
            val sameRing = current != null && current.ringId == ringId
            val live = inLedger || (sameRing && current.snoozedUntil != null)
            if (!live) {
                // Dismissed while snoozed; make sure no snoozed notification outlives its entry.
                bestEffort("cancel stale snoozed notification") { notifier.cancelSnoozed(ringId) }
                return@withLock SlotClaim.NotDue
            }

            bestEffort("ledger remove") { ledger.remove(ringId) }
            bestEffort("cancel fired snooze") { snoozeArmer.cancelSnooze(ringId) }
            bestEffort("cancel snoozed notification") { notifier.cancelSnoozed(ringId) }
            // Never merge into or supersede itself: if the slot already holds this ring, just overwrite it.
            if (!sameRing) {
                // A pre-upgrade entry has no occurrence: it supersedes, since a member needs its real X.
                val member = entry?.occurrenceAt?.let { RingMember(entry.alarmId, it) }
                if (mergeLocked(current, ringId, member)) return@withLock SlotClaim.Merged
                supersedeLocked(current, ringId)
            }
            val startedAt = if (sameRing) current.startedAt else now()
            val occurrenceAt = entry?.occurrenceAt ?: current?.takeIf { sameRing }?.occurrenceAt
            val merged = if (sameRing) current.merged else emptyList()
            ringStateStore.set(RingState(alarmId, ringId, startedAt, null, occurrenceAt, merged))
            SlotClaim.Claimed
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

    /**
     * Adds [member] to [current] if it is a different ring that is actively sounding; true when the
     * member is now part of it (already being one counts). The write may throw. False, and nothing
     * written, for a snoozed or empty slot, the same ring, or no [member].
     */
    private suspend fun mergeLocked(current: RingState?, newRingId: String, member: RingMember?): Boolean {
        if (member == null || current == null || current.ringId == newRingId || current.snoozedUntil != null) return false
        val isPrimary = current.alarmId == member.alarmId && current.occurrenceAt == member.occurrenceAt
        if (!isPrimary && member !in current.merged) ringStateStore.set(current.copy(merged = current.merged + member))
        return true
    }

    /** Dismisses [current] if it is a different ring that is actively sounding; parks a snoozed one. Never throws. */
    private suspend fun supersedeLocked(current: RingState?, newRingId: String) {
        if (current == null || current.ringId == newRingId || current.snoozedUntil != null) return
        try {
            // Not a user action: nothing is mirrored.
            dismissCurrentLocked(current, mirrored = false)
        } catch (e: Throwable) {
            log("superseding ring ${current.ringId} failed", e)
        }
    }

    /**
     * Ends [state], the ring in the slot: clear it, drop any snooze, disable every one-shot member,
     * reschedule. When [mirrored], reports every member to [mirror] once the state is cleared, even
     * if a later step failed: the ring has stopped either way.
     */
    private suspend fun dismissCurrentLocked(state: RingState, mirrored: Boolean) {
        ringStateStore.set(null)
        // Once state is cleared every remaining step must still run; surface the first failure last.
        val steps = Steps()
        // Ledger before the PendingIntent and the reschedule: the scheduler arms from the ledger.
        steps.run { ledger.remove(state.ringId) }
        steps.run { snoozeArmer.cancelSnooze(state.ringId) }
        steps.run { disableIfOneShot(state.alarmId) }
        // Merged members have no snooze of their own: the claim that merged them dropped it.
        for (member in state.merged) steps.run { disableIfOneShot(member.alarmId) }
        steps.run { notifier.cancelSnoozed(state.ringId) }
        steps.run { alarmScheduler.rescheduleAll() }
        if (mirrored) mirrorLocal(MirrorKind.DISMISS, state.members(), null)
        steps.rethrow()
    }

    /** Dismisses a snooze parked behind another ring. RingState is not touched. */
    private suspend fun dropParkedLocked(entry: SnoozeEntry, mirrored: Boolean) {
        val steps = Steps()
        steps.run { ledger.remove(entry.ringId) }
        steps.run { snoozeArmer.cancelSnooze(entry.ringId) }
        steps.run { notifier.cancelSnoozed(entry.ringId) }
        steps.run { disableIfOneShot(entry.alarmId) }
        steps.run { alarmScheduler.rescheduleAll() }
        if (mirrored) mirrorLocal(MirrorKind.DISMISS, listOfNotNull(entry.occurrenceAt?.let { RingMember(entry.alarmId, it) }), null)
        steps.rethrow()
    }

    /** The ring's members: the primary (left out without a known occurrence, never guessed) and every merged one. */
    private fun RingState.members(): List<RingMember> =
        listOfNotNull(occurrenceAt?.let { RingMember(alarmId, it) }) + merged

    /** Reports a LOCAL resolution of [members] (I4); best effort, so a resolved ring never turns into a throw. */
    private fun mirrorLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?) {
        if (members.isEmpty()) return
        bestEffort("mirror $kind") { mirror.onLocal(kind, members, snoozedUntil) }
    }

    private suspend fun disableIfOneShot(alarmId: String) {
        val alarm = alarmRepository.get(alarmId)
        if (alarm == null || !alarm.isOneShot()) return
        // A linked row's enabled is the owner's, written only by LinkSync; turn off our own side.
        if (alarm.link != null) alarmRepository.setJoinerEnabled(alarm.id, false)
        else alarmRepository.setEnabled(alarm.id, false)
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
        const val TAG = "RingingController"
    }
}
