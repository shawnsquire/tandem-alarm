package me.shawnsquire.tandem.sync

import kotlin.time.Duration

/**
 * Pre-fire pull of pending partner commands (I1). Firing never waits beyond a hard 3 s [timeout].
 * Returns whether the pull succeeded; callers re-read local state afterwards. On failure,
 * timeout or offline the alarm rings; suppression is allowed only by an already-stored local
 * command or a check that actually succeeded.
 */
interface PreFireCheck {
    suspend fun pullAndApply(timeout: Duration): Boolean
}
