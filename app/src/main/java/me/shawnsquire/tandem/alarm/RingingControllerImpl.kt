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
 *
 * [remoteNotices] gets every REMOTE resolution that touches the slot ring, just before its state
 * write, so the ringing screen can tell the partner ended it.
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
    private val remoteNotices: RemoteRingNotices = RemoteRingNotices(),
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

    /**
     * Resolves the primary and every merged member; see [RingingController.dismiss]. Except a REMOTE
     * stop of a sounding merged ring: the partner stopped what it saw, and a member that merged
     * before the command landed was never part of that. Only the primary (the alarm `validate`
     * matched the command to) resolves, as a REMOTE stop of its occurrence does: the next member is
     * promoted under the same ringId, so a stale repeat of the stop no longer validates.
     */
    override suspend fun dismiss(ringId: String, origin: RingOrigin) = withContext(NonCancellable) { mutex.withLock {
        val state = ringStateStore.state.first()
        val local = origin == RingOrigin.LOCAL
        when {
            state == null || state.ringId != ringId -> dismissNotInSlotLocked(ringId, local)
            !local && state.snoozedUntil == null && state.merged.isNotEmpty() -> {
                // A legacy primary (no occurrence) promotes too; its notice just names no member.
                val notice = RemoteRingEnd(state.ringId, state.primaryMember(), MirrorKind.DISMISS, null, endsRing = false)
                dismissMemberLocked(state.alarmId, state.promoteFirstMember(), mirroredAs = null, notice)
            }
            else -> {
                val notice = if (!local) RemoteRingEnd(state.ringId, state.primaryMember(), MirrorKind.DISMISS, null, endsRing = true) else null
                dismissCurrentLocked(state, local, notice)
            }
        }
    } }

    /** Compare-and-dismiss; see [RingSlot.autoSilence]. */
    override suspend fun autoSilence(ringId: String, expected: List<RingMember>): Boolean = withContext(NonCancellable) { mutex.withLock {
        val state = ringStateStore.state.first()
        when {
            state == null || state.ringId != ringId -> {
                // Snoozed, then parked behind a newer ring while auto-silence waited: the snooze stands.
                val parked = ledger.get(ringId)
                if (parked == null || !parked.until.isAfter(now())) dismissNotInSlotLocked(ringId, local = true)
            }
            // Snoozed while auto-silence waited: the snooze stands. One already due is no snooze any
            // more (it fired, and its claim couldn't write): the ring ends like any other.
            state.snoozedUntil != null && state.snoozedUntil.isAfter(now()) -> Unit
            state.snoozedUntil == null && !expected.containsAll(state.members()) -> return@withLock false
            else -> dismissCurrentLocked(state, mirrored = true)
        }
        true
    } }

    /** [dismiss] of a [ringId] the slot doesn't hold. */
    private suspend fun dismissNotInSlotLocked(ringId: String, local: Boolean) {
        // A snooze parked behind a newer ring (Dismiss on its snoozed notification).
        val parked = ledger.get(ringId)
        if (parked != null) {
            dropParkedLocked(parked, local)
        } else {
            // I3: a stop pinned to another (or no) ring is ignored. It stops no ring, but a
            // snoozed notification whose ledger entry is gone would otherwise stay forever.
            bestEffort("cancel stuck snoozed notification") { notifier.cancelSnoozed(ringId) }
        }
    }

    /**
     * Resolves only X, wherever it lives: the slot ring (a merged ring keeps sounding under the same
     * ringId, the next member promoted), one merged member, a parked snooze, or X before it fires
     * here (recorded in [firedLedger] so its fire is quiet). See [RingingController.dismissOccurrence].
     */
    override suspend fun dismissOccurrence(alarmId: String, occurrenceAt: Instant, origin: RingOrigin) = withContext(NonCancellable) { mutex.withLock {
        val x = RingMember(alarmId, occurrenceAt)
        val local = origin == RingOrigin.LOCAL
        val state = ringStateStore.state.first()
        fun remote(endsRing: Boolean) = if (!local && state != null) RemoteRingEnd(state.ringId, x, MirrorKind.DISMISS, null, endsRing) else null
        when {
            // Snoozed or solo, the slot ring is X alone: its members() is just X.
            state != null && state.isPrimary(x) && (state.snoozedUntil != null || state.merged.isEmpty()) ->
                dismissCurrentLocked(state, local, remote(endsRing = true))
            state != null && state.isPrimary(x) -> dismissMemberLocked(alarmId, state.promoteFirstMember(), x.takeIf { local }, remote(endsRing = false))
            state != null && x in state.merged -> dismissMemberLocked(alarmId, state.copy(merged = state.merged - x), x.takeIf { local }, remote(endsRing = false))
            else -> {
                val parked = parkedEntryLocked(x, state)
                when {
                    parked != null -> dropParkedLocked(parked, local)
                    firedLedger.lastRang(alarmId) == occurrenceAt -> Unit // already rang, or resolved in advance
                    else -> dismissInAdvanceLocked(x, local)
                }
            }
        }
    } }

    /**
     * Parks only X until [until]: a member of a sounding ring splits out into its own snooze (the
     * rest keep sounding), a solo slot ring snoozes as [snooze] does, a snooze already pending moves
     * only to a later [until], and X not fired yet gets its snooze armed with no ring first.
     * See [RingingController.snoozeOccurrence].
     */
    override suspend fun snoozeOccurrence(alarmId: String, occurrenceAt: Instant, until: Instant, origin: RingOrigin) =
        withContext(NonCancellable) { mutex.withLock {
            val x = RingMember(alarmId, occurrenceAt)
            val state = ringStateStore.state.first()
            val remote = origin == RingOrigin.REMOTE
            // A split-out member: the ring sounds on without X.
            fun splitNotice() = if (remote && state != null) RemoteRingEnd(state.ringId, x, MirrorKind.SNOOZE, until, endsRing = false) else null
            val resolved = when {
                state != null && state.isPrimary(x) && state.snoozedUntil != null -> {
                    val notice = if (remote) RemoteRingEnd(state.ringId, x, MirrorKind.SNOOZE, until, endsRing = true) else null
                    moveSnoozeLocked(SnoozeEntry(state.ringId, alarmId, state.snoozedUntil, occurrenceAt), until, state, notice)
                }
                state != null && state.isPrimary(x) && state.merged.isEmpty() -> {
                    snoozeSlotLocked(state, until, origin) // mirrors X itself
                    false
                }
                state != null && state.isPrimary(x) -> { splitOutLocked(x, state.promoteFirstMember(), until, splitNotice()); true }
                state != null && x in state.merged -> { splitOutLocked(x, state.copy(merged = state.merged - x), until, splitNotice()); true }
                else -> {
                    val parked = parkedEntryLocked(x, state)
                    when {
                        parked != null -> moveSnoozeLocked(parked, until, slot = null, notice = null)
                        firedLedger.lastRang(alarmId) == occurrenceAt -> false
                        else -> { snoozeInAdvanceLocked(x, until); true }
                    }
                }
            }
            if (resolved && origin == RingOrigin.LOCAL) mirrorLocal(MirrorKind.SNOOZE, listOf(x), until)
        } }

    override suspend fun writeUnlessRinging(alarmId: String, occurrenceAt: Instant?, write: suspend () -> Unit): Boolean =
        withContext(NonCancellable) { mutex.withLock {
            // A failed read throws before anything is written: the command is not applied.
            val state = ringStateStore.state.first()
            val ring = state?.takeIf { it.alarmId == alarmId }
            // A promoted member is the primary of a ring that started before its own occurrence.
            val ringing = ring != null && (occurrenceAt == null || ring.startedAt >= occurrenceAt || ring.occurrenceAt?.let { it >= occurrenceAt } == true)
            if (ringing) return@withLock false
            // A merged member is ringing too.
            val member = state?.merged.orEmpty().any { it.alarmId == alarmId && (occurrenceAt == null || it.occurrenceAt >= occurrenceAt) }
            if (member) return@withLock false
            // Snoozed: a merged ring's members split out into their own entries, and parked snoozes.
            // An entry without an occurrence (written before they were stored) counts for any.
            val snoozed = ledger.all().any {
                it.alarmId == alarmId && (occurrenceAt == null || it.occurrenceAt == null || it.occurrenceAt >= occurrenceAt)
            }
            if (snoozed) return@withLock false
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
        snoozeSlotLocked(state, end, origin)
    } }

    /** Snoozes [state], the sounding ring in the slot, until [end]; see [snooze]. */
    private suspend fun snoozeSlotLocked(state: RingState, end: Instant, origin: RingOrigin) {
        val split = state.merged.map { newRingId() to it }
        // Arm first: if anything throws nothing stays written and the ring continues with all its members.
        val armed = mutableListOf<String>()
        try {
            armSnoozeLocked(state.ringId, state.alarmId, end, state.occurrenceAt, armed)
            for ((id, member) in split) armSnoozeLocked(id, member.alarmId, end, member.occurrenceAt, armed)
            val notice = if (origin == RingOrigin.REMOTE) RemoteRingEnd(state.ringId, state.primaryMember(), MirrorKind.SNOOZE, end, endsRing = true) else null
            writeNoticed(notice) { ringStateStore.set(state.copy(snoozedUntil = end, merged = emptyList())) }
        } catch (e: Throwable) {
            // Not snoozed after all: the ring keeps sounding, so take every snooze back.
            takeBackLocked(armed)
            throw e
        }
        rescheduleAfterArming()
        runCatching { notifier.showSnoozed(state.ringId, end, plainLabel(state.alarmId)) }
        for ((id, member) in split) runCatching { notifier.showSnoozed(id, end, plainLabel(member.alarmId)) }
        if (origin == RingOrigin.LOCAL) mirrorLocal(MirrorKind.SNOOZE, state.members(), end)
    }

    /** The snooze is already armed; the rest of the schedule is best effort. */
    private suspend fun rescheduleAfterArming() {
        try {
            alarmScheduler.rescheduleAll()
        } catch (e: Throwable) {
            log("rescheduleAll failed after arming snooze", e)
        }
    }

    /** Arms [ringId]'s snooze, then writes its ledger entry; [armed] gets [ringId] once there is anything to take back. */
    private fun armSnoozeLocked(ringId: String, alarmId: String, end: Instant, occurrenceAt: Instant?, armed: MutableList<String>) {
        snoozeArmer.armSnooze(alarmId, ringId, end)
        armed += ringId
        ledger.put(ringId, alarmId, end, occurrenceAt)
    }

    /** Takes back every snooze [armSnoozeLocked] armed; never throws. */
    private fun takeBackLocked(armed: List<String>) {
        for (id in armed) {
            runCatching { ledger.remove(id) }
            runCatching { snoozeArmer.cancelSnooze(id) }
        }
    }

    /**
     * Parks [member] of the sounding ring as its own snooze until [until] (fresh ringId) and writes
     * [rest], the ring left sounding. Arms first: on a throw the ring keeps sounding with every member.
     * [notice] (a REMOTE split) is emitted just before the write.
     */
    private suspend fun splitOutLocked(member: RingMember, rest: RingState, until: Instant, notice: RemoteRingEnd?) {
        val id = newRingId()
        val armed = mutableListOf<String>()
        try {
            armSnoozeLocked(id, member.alarmId, until, member.occurrenceAt, armed)
            writeNoticed(notice) { ringStateStore.set(rest) }
        } catch (e: Throwable) {
            takeBackLocked(armed)
            throw e
        }
        rescheduleAfterArming()
        runCatching { notifier.showSnoozed(id, until, plainLabel(member.alarmId)) }
    }

    /**
     * Moves the pending snooze [entry] to [to] (same ringId) only if that is later; true when moved.
     * [slot] is the snoozed slot ring when [entry] is its snooze, written with the new end, [notice]
     * (a REMOTE move) just before. Arms first: on a throw the snooze goes back to its old end.
     */
    private suspend fun moveSnoozeLocked(entry: SnoozeEntry, to: Instant, slot: RingState?, notice: RemoteRingEnd?): Boolean {
        if (!to.isAfter(entry.until)) return false
        val before = ledger.get(entry.ringId)
        snoozeArmer.armSnooze(entry.alarmId, entry.ringId, to)
        try {
            ledger.put(entry.ringId, entry.alarmId, to, entry.occurrenceAt)
            if (slot != null) {
                writeNoticed(notice) { ringStateStore.set(slot.copy(snoozedUntil = to)) }
            }
        } catch (e: Throwable) {
            runCatching { if (before != null) ledger.put(before.ringId, before.alarmId, before.until, before.occurrenceAt) else ledger.remove(entry.ringId) }
            runCatching { snoozeArmer.armSnooze(entry.alarmId, entry.ringId, entry.until) }
            throw e
        }
        rescheduleAfterArming()
        runCatching { notifier.showSnoozed(entry.ringId, to, plainLabel(entry.alarmId)) }
        return true
    }

    /**
     * X has not fired here: arms its snooze, then records X as rung so its fire stays quiet. I1: X is
     * never suppressed without the snooze behind it, so a throw takes the snooze back and X rings.
     */
    private suspend fun snoozeInAdvanceLocked(x: RingMember, until: Instant) {
        val id = newRingId()
        val armed = mutableListOf<String>()
        try {
            armSnoozeLocked(id, x.alarmId, until, x.occurrenceAt, armed)
            markRangForwardLocked(x)
        } catch (e: Throwable) {
            takeBackLocked(armed)
            throw e
        }
        rescheduleAfterArming()
        runCatching { notifier.showSnoozed(id, until, plainLabel(x.alarmId)) }
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

    override suspend fun claimSnoozeResume(ringId: String, alarmId: String, onMerged: (RingMember) -> Unit): SlotClaim =
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

            // Never merge into or supersede itself: if the slot already holds this ring, just overwrite it.
            if (!sameRing) {
                // A pre-upgrade entry has no occurrence. It still merges, under its snooze end as a
                // placeholder X: it is an own alarm from before linked alarms, so no partner command
                // can target it, and superseding would dismiss a ring nobody stopped. Only the merge
                // uses it; a ring written below keeps the unknown occurrence.
                val member = entry?.let { RingMember(it.alarmId, it.occurrenceAt ?: it.until) }
                if (mergeLocked(current, ringId, member)) {
                    dropFiredSnoozeLocked(ringId)
                    member?.let(onMerged)
                    return@withLock SlotClaim.Merged
                }
                supersedeLocked(current, ringId)
            }
            val startedAt = if (sameRing) current.startedAt else now()
            val occurrenceAt = entry?.occurrenceAt ?: current?.takeIf { sameRing }?.occurrenceAt
            val merged = if (sameRing) current.merged else emptyList()
            ringStateStore.set(RingState(alarmId, ringId, startedAt, null, occurrenceAt, merged))
            dropFiredSnoozeLocked(ringId)
            SlotClaim.Claimed
        } }

    /**
     * The snooze [ringId] has fired and its ring is written (or merged): drop its ledger entry,
     * PendingIntent and snoozed notification. Only after that write, so a throwing write leaves the
     * snooze in place. Ledger before the PendingIntent: the scheduler arms from the ledger.
     */
    private fun dropFiredSnoozeLocked(ringId: String) {
        bestEffort("ledger remove") { ledger.remove(ringId) }
        bestEffort("cancel fired snooze") { snoozeArmer.cancelSnooze(ringId) }
        bestEffort("cancel snoozed notification") { notifier.cancelSnoozed(ringId) }
    }

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
        if (!current.isPrimary(member) && member !in current.merged) ringStateStore.set(current.copy(merged = current.merged + member))
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
     * if a later step failed: the ring has stopped either way. [notice] (a REMOTE dismiss) goes out
     * with the clear ([writeNoticed]).
     */
    private suspend fun dismissCurrentLocked(state: RingState, mirrored: Boolean, notice: RemoteRingEnd? = null) {
        writeNoticed(notice) { ringStateStore.set(null) }
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

    /**
     * Ends only [alarmId]'s member of the sounding ring and writes [rest], the ring left sounding.
     * Like [dismissCurrentLocked], once [rest] is written every step runs and [mirroredAs] (a LOCAL
     * stop's member, null for REMOTE) is still reported; [notice] goes out with the write.
     */
    private suspend fun dismissMemberLocked(alarmId: String, rest: RingState, mirroredAs: RingMember?, notice: RemoteRingEnd?) {
        writeNoticed(notice) { ringStateStore.set(rest) }
        val steps = Steps()
        steps.run { disableIfOneShot(alarmId) }
        steps.run { alarmScheduler.rescheduleAll() }
        mirroredAs?.let { mirrorLocal(MirrorKind.DISMISS, listOf(it), null) }
        steps.rethrow()
    }

    /**
     * X has not fired here: records it as rung so its fire stays quiet (executeFire re-checks after
     * the pre-fire check and in the claim), then turns a one-shot off as a normal dismiss does.
     */
    private suspend fun dismissInAdvanceLocked(x: RingMember, mirrored: Boolean) {
        markRangForwardLocked(x)
        val steps = Steps()
        steps.run { disableIfOneShot(x.alarmId) }
        steps.run { alarmScheduler.rescheduleAll() }
        if (mirrored) mirrorLocal(MirrorKind.DISMISS, listOf(x), null)
        steps.rethrow()
    }

    /**
     * Records X in [firedLedger] unless a later occurrence of its alarm is there already: moving it
     * back would let that one ring again on a replay. X is older, so its time has passed either way.
     */
    private fun markRangForwardLocked(x: RingMember) {
        val last = firedLedger.lastRang(x.alarmId)
        if (last == null || !last.isAfter(x.occurrenceAt)) firedLedger.markRang(x.alarmId, x.occurrenceAt)
    }

    /** X's snooze in the ledger, other than the slot ring's own (parked, or a split-out member). */
    private fun parkedEntryLocked(x: RingMember, state: RingState?): SnoozeEntry? =
        ledger.all().firstOrNull { it.ringId != state?.ringId && it.alarmId == x.alarmId && it.occurrenceAt == x.occurrenceAt }

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

    /**
     * [alarmId]'s label for its snoozed notification, as the repository has it (a linked row's
     * override first); null when unreadable. Not the resolved media: that may wait on the cache, and
     * this runs under the lock.
     */
    private suspend fun plainLabel(alarmId: String): String? = try {
        alarmRepository.get(alarmId)?.let { it.link?.overrideLabel ?: it.label }
    } catch (e: Throwable) {
        log("label read for the snoozed notification failed", e)
        null
    }

    /** The primary as a member, null without a known occurrence (never guessed). */
    private fun RingState.primaryMember(): RingMember? = occurrenceAt?.let { RingMember(alarmId, it) }

    /** Never throws: a resolution must not fail over the screen's notice. Null if it couldn't be emitted. */
    private fun emitRemote(notice: RemoteRingEnd): RemoteRingEnd? = try {
        remoteNotices.emit(notice)
    } catch (e: Throwable) {
        log("remote notice failed", e)
        null
    }

    /**
     * Emits [notice] just before [write] and takes it back when the write throws: the ring didn't
     * change, so a later LOCAL clear must not read as the partner's dismiss. The screen checks a
     * snooze or split notice against the state it sees, but a whole-ring dismiss only by its null.
     */
    private suspend fun writeNoticed(notice: RemoteRingEnd?, write: suspend () -> Unit) {
        val emitted = notice?.let(::emitRemote)
        try {
            write()
        } catch (e: Throwable) {
            emitted?.let { bestEffort("withdraw remote notice") { remoteNotices.withdraw(it) } }
            throw e
        }
    }

    private fun RingState.isPrimary(member: RingMember): Boolean =
        alarmId == member.alarmId && occurrenceAt == member.occurrenceAt

    /**
     * This ring without its primary: the first merged member takes its place. ringId and startedAt
     * stay, since the session and the ringing screen key on ringId (a new one would silence the rest).
     */
    private fun RingState.promoteFirstMember(): RingState {
        val next = merged.first()
        return copy(alarmId = next.alarmId, occurrenceAt = next.occurrenceAt, merged = merged.drop(1))
    }

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
