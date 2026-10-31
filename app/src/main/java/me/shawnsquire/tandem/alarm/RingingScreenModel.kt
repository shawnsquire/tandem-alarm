package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.schedule.RingState

/** How long the ringing screen waits for an active ring before giving up. */
const val RING_GRACE_MS = 10_000L

/**
 * How long a null state (after a ring was seen) may last before the screen finishes. A superseding
 * alarm briefly clears the state (dismiss, then the new ring is written), and the full-screen intent
 * does not re-fire, so the screen must survive that gap.
 */
const val END_DEBOUNCE_MS = 2_500L

/**
 * Decides when the ringing screen should finish. The activity launches before the session's
 * pre-fire check, so the first states may be null or stale. A different active ring replaces the
 * one shown (a superseding alarm). A snooze of the shown ring ends the screen at once; a null
 only starts a pending end ([endPending]) that [awaitRingEnd] resolves after [END_DEBOUNCE_MS].
 */
class RingingScreenModel {
    /** The ring currently shown, once one has been seen. */
    var ring: RingState? = null
        private set

    val ringId: String? get() = ring?.ringId

    /** True while the shown ring is gone (null seen) and no replacement has arrived yet. */
    var endPending: Boolean = false
        private set

    /** Feed each store emission. Returns true when the screen should finish immediately. */
    fun onState(state: RingState?): Boolean {
        if (state == null) {
            endPending = ringId != null
            return false
        }
        if (state.snoozedUntil != null) return ringId != null
        // First ring seen, or a superseding ring: adopt it and cancel any pending end.
        ring = state
        endPending = false
        return false
    }

    /** The grace period elapsed. Finish only if no active ring was ever seen. */
    fun onGraceElapsed(): Boolean = ringId == null
}

/**
 * [onRing] is called with the ring being shown whenever it changes (first seen or superseded).
 * Returns when the screen should finish: the ring was snoozed, [userStopped] completed, the ring
 * ended and no new one arrived within [endDebounceMs], or no active ring appeared within [graceMs].
 */
suspend fun awaitRingEnd(
    states: Flow<RingState?>,
    model: RingingScreenModel = RingingScreenModel(),
    graceMs: Long = RING_GRACE_MS,
    endDebounceMs: Long = END_DEBOUNCE_MS,
    userStopped: CompletableDeferred<Unit> = CompletableDeferred(),
    onRing: (RingState) -> Unit = {},
) = coroutineScope {
    val ended = CompletableDeferred<Unit>()
    var endTimer: Job? = null
    val collector = launch {
        states.collect {
            val before = model.ringId
            val done = model.onState(it)
            model.ring?.takeIf { r -> r.ringId != before }?.let(onRing)
            if (done) ended.complete(Unit)
            if (model.endPending) {
                if (endTimer == null) {
                    endTimer = launch {
                        delay(endDebounceMs)
                        ended.complete(Unit)
                    }
                }
            } else {
                endTimer?.cancel()
                endTimer = null
            }
        }
    }
    val timer = launch {
        delay(graceMs)
        if (model.onGraceElapsed()) ended.complete(Unit)
    }
    val stop = launch {
        userStopped.await()
        ended.complete(Unit)
    }
    ended.await()
    collector.cancel()
    endTimer?.cancel()
    timer.cancel()
    stop.cancel()
}
