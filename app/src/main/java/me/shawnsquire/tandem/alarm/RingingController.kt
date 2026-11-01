package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.schedule.RingOrigin
import java.time.Instant

/** Controls the active ring. Dismiss is pinned to [ringId] (I3). */
interface RingingController {
    /**
     * Dismisses the ring [ringId]. LOCAL (button, notification action, auto-silence) calls
     * RingMirrorPort.onLocal for every member; REMOTE never does (I4).
     */
    suspend fun dismiss(ringId: String, origin: RingOrigin = RingOrigin.LOCAL)

    /**
     * Snoozes the current ring. LOCAL (button, notification action, auto-silence) calls
     * RingMirrorPort.onLocal for every member; REMOTE never does (I4). [until], when non-null, is the
     * absolute snooze end T (from a mirrored SNOOZE) instead of now + snooze length.
     */
    suspend fun snooze(origin: RingOrigin = RingOrigin.LOCAL, until: Instant? = null)

    /**
     * Resolve one occurrence X of [alarmId] — a member of the live or merged ring, a snoozed/parked
     * ring, or one not yet fired. A received STOP/SNOOZE for X removes only X from a merged ring; a
     * SNOOZE splits X out into the SnoozeLedger as a parked snooze until [until].
     * **Resolving X before it fires:** when X hasn't fired yet, dismissOccurrence records X in
     * FiredLedger, and snoozeOccurrence records it and also arms the snooze. executeFire already
     * re-checks FiredLedger before it claims the slot, so the fire is suppressed even if it's partway
     * through the pre-fire check. Validation rule 3 reads the same record. [origin] is REMOTE when
     * applied from a received command (sends nothing, I4).
     */
    suspend fun dismissOccurrence(alarmId: String, occurrenceAt: Instant, origin: RingOrigin)

    /** Snooze counterpart of [dismissOccurrence]; see there. The occurrence is parked until [until]. */
    suspend fun snoozeOccurrence(alarmId: String, occurrenceAt: Instant, until: Instant, origin: RingOrigin)

    /**
     * Runs [write], a skip or turn-off of [alarmId], unless that alarm is ringing (or snoozed) with
     * a ring that started at or after [occurrenceAt] (any ring when it is null); then returns false
     * and writes nothing. Under the same lock as [dismiss] and the fire claim, so a ring starting
     * concurrently either sees the write or is seen here. Unless its claim timed out and it rang
     * without the slot: then the command can report APPLIED while it sounds (I1 still holds).
     * [write] must not call this controller (the lock is not reentrant) and must stay short;
     * reschedule after it returns.
     */
    suspend fun writeUnlessRinging(alarmId: String, occurrenceAt: Instant?, write: suspend () -> Unit): Boolean
}
