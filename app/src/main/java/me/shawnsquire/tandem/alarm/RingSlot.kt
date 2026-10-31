package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.schedule.RingState

/**
 * Claims the single RingState slot atomically, in the same critical section as dismiss and
 * snooze. Only a ring that is actively sounding is superseded; a snoozed ring is parked and
 * keeps its snooze (its PendingIntent, [SnoozeLedger] entry and snoozed notification).
 *
 * A call that throws means the new state was not written.
 */
interface RingSlot {
    /** Supersedes a sounding ring (dismiss), parks a snoozed one, then writes [newRing]. */
    suspend fun claimFire(newRing: RingState)

    /**
     * Snooze-fire path. Returns false when the snooze is no longer live (dismissed while
     * snoozed). Otherwise drops the ledger entry and snoozed notification, supersedes or parks
     * the current ring, writes [ringId] as sounding (same ringId, I3) and returns true.
     */
    suspend fun claimSnoozeResume(ringId: String, alarmId: String): Boolean

    /**
     * Clears a RingState left behind by a dead process: non-null, [isLive] false for its ringId,
     * and not a snooze that is still pending (`snoozedUntil` within the last 10 minutes or later).
     * Not a dismiss: a one-shot stays enabled. Under the same lock as the other calls, so it
     * must never be called from inside a reschedule. Reschedules afterwards when [reschedule]
     * (the boot path passes false because its own reschedule follows and must see the old records).
     */
    suspend fun clearStale(isLive: (String) -> Boolean, reschedule: Boolean = true)
}
