package me.shawnsquire.tandem.alarm

import java.time.Instant

/** Controls the active ring. Dismiss is pinned to [ringId] (I3). */
interface RingingController {
    suspend fun dismiss(ringId: String)
    suspend fun snooze()

    /**
     * Runs [write], a skip or turn-off of [alarmId], unless that alarm is ringing (or snoozed) with
     * a ring that started at or after [occurrenceAt] (any ring when it is null); then returns false
     * and writes nothing. Under the same lock as [dismiss] and the fire claim, so a ring starting
     * concurrently either sees the write or is seen here. [write] must not call this controller
     * (the lock is not reentrant) and must stay short; reschedule after it returns.
     */
    suspend fun writeUnlessRinging(alarmId: String, occurrenceAt: Instant?, write: suspend () -> Unit): Boolean
}
