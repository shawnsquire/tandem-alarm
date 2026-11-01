package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.schedule.RingState

/** What a [RingSlot] claim did with the slot. */
enum class SlotClaim {
    /** The new ring was written to the slot; the caller rings it. */
    Claimed,

    /** The slot holds a different sounding ring and the new alarm joined its `merged`: the caller plays nothing. */
    Merged,

    /** No longer due (fire) or no longer live (snooze): nothing was written. */
    NotDue,
}

/**
 * Claims the single RingState slot atomically, in the same critical section as dismiss and
 * snooze. A ring that is actively sounding takes the new alarm into its `merged` (one sound plays,
 * the one already playing); it is superseded only when the new alarm's occurrence is unknown. A
 * snoozed ring is parked and keeps its snooze (its PendingIntent, [SnoozeLedger] entry and snoozed
 * notification).
 *
 * A call that throws means the new state was not written.
 */
interface RingSlot {
    /**
     * Merges [newRing] into a sounding ring (supersedes it when [newRing] has no occurrence);
     * otherwise parks a snoozed ring and writes [newRing].
     *
     * [stillDue] runs first, inside the same critical section, so a command stored locally
     * between the caller's decision to ring and this claim is not missed. When it returns false
     * nothing is merged, superseded or written and this returns [SlotClaim.NotDue]. When it throws,
     * the error is logged and the ring counts as due (I1: an error never silences a ring). Returns
     * [SlotClaim.Claimed] when [newRing] was written and [SlotClaim.Merged] when it joined the
     * sounding ring (already a member counts too); a throw from either write still propagates.
     */
    suspend fun claimFire(newRing: RingState, stillDue: suspend () -> Boolean = { true }): SlotClaim

    /**
     * Snooze-fire path. Returns [SlotClaim.NotDue] when the snooze is no longer live (dismissed
     * while snoozed). Otherwise drops the ledger entry and snoozed notification. A different
     * sounding ring takes the snooze into its `merged` ([SlotClaim.Merged]); an entry without an
     * occurrence (written before occurrences were stored) supersedes it instead. A snoozed ring is
     * parked. Unless merged, writes [ringId] as sounding (same ringId, I3) and returns [SlotClaim.Claimed].
     */
    suspend fun claimSnoozeResume(ringId: String, alarmId: String): SlotClaim

    /**
     * Clears a RingState left behind by a dead process: non-null, [isLive] false for its ringId,
     * and not a snooze that is still pending (`snoozedUntil` within the last 10 minutes or later).
     * Not a dismiss: a one-shot stays enabled. Under the same lock as the other calls, so it
     * must never be called from inside a reschedule. Reschedules afterwards when [reschedule]
     * (the boot path passes false because its own reschedule follows and must see the old records).
     */
    suspend fun clearStale(isLive: (String) -> Boolean, reschedule: Boolean = true)
}
