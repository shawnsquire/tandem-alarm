package me.shawnsquire.tandem.sync

import kotlin.time.Duration

/**
 * Pre-fire pull of pending partner commands (I1). Firing never waits beyond a hard 3 s [timeout].
 * Returns whether the pull succeeded; callers re-read local state afterwards. On failure,
 * timeout or offline the alarm rings; suppression is allowed only by an already-stored local
 * command or a check that actually succeeded.
 */
interface PreFireCheck {
    /**
     * For a linked row ([linkedOwnerAlarmId] non-null) it also fetches the owner's device doc with
     * `Source.SERVER`, never the cache, inside the same timeout: a cached read can "succeed" while
     * still showing a skip the owner has since cleared, and suppressing on it would break I1. It
     * applies the doc through LinkSync, and the caller then re-reads local state, as today. The
     * owner's solo skip never suppresses. (L4)
     *
     * The owner's published `nextOccurrenceAt` skips past a solo-skipped occurrence, so "X isn't an
     * occurrence" must be computed from the owner's hour, minute and days, never from
     * `nextOccurrenceAt`; otherwise a solo skip would suppress the joiner.
     *
     * With [linkedOwnerAlarmId], returns true only if the command pull and the `Source.SERVER`
     * owner-doc read both succeeded and the doc was applied; otherwise false, and the caller must
     * not suppress the linked row on copied owner state (I1).
     */
    suspend fun pullAndApply(timeout: Duration, linkedOwnerAlarmId: String? = null): Boolean
}
