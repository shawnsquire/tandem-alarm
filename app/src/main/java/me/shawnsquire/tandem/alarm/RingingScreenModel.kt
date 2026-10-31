package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.schedule.RingState

/** How long the ringing screen waits for an active ring before giving up. */
const val RING_GRACE_MS = 10_000L

/**
 * Decides when the ringing screen should finish. The activity launches before the session's
 * pre-fire check, so the first states may be null or stale; only a transition after a ring
 * was seen ends the screen.
 */
class RingingScreenModel {
    /** The ringId of the active ring, once one has been seen. */
    var ringId: String? = null
        private set

    /** Feed each store emission. Returns true when the screen should finish. */
    fun onState(state: RingState?): Boolean {
        val seen = ringId
        if (seen == null) {
            if (state != null && state.snoozedUntil == null) ringId = state.ringId
            return false
        }
        return state == null || state.ringId != seen || state.snoozedUntil != null
    }

    /** The grace period elapsed. Finish only if no active ring was ever seen. */
    fun onGraceElapsed(): Boolean = ringId == null
}

/**
 * Returns when the screen should finish: a ring ended after being seen, or no active ring
 * appeared within [graceMs].
 */
suspend fun awaitRingEnd(
    states: Flow<RingState?>,
    model: RingingScreenModel = RingingScreenModel(),
    graceMs: Long = RING_GRACE_MS,
) = coroutineScope {
    val ended = CompletableDeferred<Unit>()
    val collector = launch { states.collect { if (model.onState(it)) ended.complete(Unit) } }
    val timer = launch {
        delay(graceMs)
        if (model.onGraceElapsed()) ended.complete(Unit)
    }
    ended.await()
    collector.cancel()
    timer.cancel()
}
