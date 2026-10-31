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
 * pre-fire check, so the first states may be null or stale. A different active ring replaces the
 * one shown (a superseding alarm); the screen ends only when the shown ring ends (null or snoozed).
 */
class RingingScreenModel {
    /** The ring currently shown, once one has been seen. */
    var ring: RingState? = null
        private set

    val ringId: String? get() = ring?.ringId

    /** Feed each store emission. Returns true when the screen should finish. */
    fun onState(state: RingState?): Boolean {
        if (state == null) return ringId != null
        if (state.snoozedUntil != null) return ringId != null
        // First ring seen, or a superseding ring: adopt it without finishing.
        ring = state
        return false
    }

    /** The grace period elapsed. Finish only if no active ring was ever seen. */
    fun onGraceElapsed(): Boolean = ringId == null
}

/**
 * [onRing] is called with the ring being shown whenever it changes (first seen or superseded).
 * Returns when the screen should finish: a ring ended after being seen, or no active ring
 * appeared within [graceMs].
 */
suspend fun awaitRingEnd(
    states: Flow<RingState?>,
    model: RingingScreenModel = RingingScreenModel(),
    graceMs: Long = RING_GRACE_MS,
    onRing: (RingState) -> Unit = {},
) = coroutineScope {
    val ended = CompletableDeferred<Unit>()
    val collector = launch {
        states.collect {
            val before = model.ringId
            val done = model.onState(it)
            model.ring?.takeIf { r -> r.ringId != before }?.let(onRing)
            if (done) ended.complete(Unit)
        }
    }
    val timer = launch {
        delay(graceMs)
        if (model.onGraceElapsed()) ended.complete(Unit)
    }
    ended.await()
    collector.cancel()
    timer.cancel()
}
