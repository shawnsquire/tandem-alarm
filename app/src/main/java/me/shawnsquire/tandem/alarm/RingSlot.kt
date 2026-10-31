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
}
