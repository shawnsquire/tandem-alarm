package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** How long the "partner dismissed / snoozed" notice stays up after a REMOTE end. */
const val REMOTE_NOTICE_MS = 3_000L

/**
 * Decides when the ringing screen should finish. The activity launches before the session's
 * pre-fire check, so the first states may be null or stale. A different active ring replaces the
 * one shown (a superseding alarm). A snooze of the shown ring ends the screen at once; a null
 * only starts a pending end ([endPending]) that [awaitRingEnd] resolves after [END_DEBOUNCE_MS].
 *
 * A REMOTE end (the partner's phone stopped or snoozed it) shows its [notice] first: the whole ring
 * ([closing], the screen finishes after [REMOTE_NOTICE_MS]) or one member (the screen stays). The
 * notice is the controller's latest [RemoteRingEnd], read with each state: it was emitted before the
 * write, so it is already there. It counts only for the shown ring, when newer than any handled
 * (the same ringId comes back after a snooze) and when the state is the change it describes (a
 * notice whose write failed explains nothing). A null state can't be told apart that way, so a
 * whole-ring dismiss is trusted as is: the controller withdraws one whose clear threw, since no
 * state change would ever show it stale. Anything else follows the LOCAL rules.
 */
class RingingScreenModel {
    /** The ring currently shown, once one has been seen. */
    var ring: RingState? = null
        private set

    val ringId: String? get() = ring?.ringId

    /** True while the shown ring is gone (null seen) and no replacement has arrived yet. */
    var endPending: Boolean = false
        private set

    /** The REMOTE end being shown, while its notice is up. */
    var notice: RemoteRingEnd? = null
        private set

    /** True while [notice] ended the shown ring: the screen finishes once it has been shown. */
    var closing: Boolean = false
        private set

    private var handledSeq = 0L

    /** Notices up to [stale] (there before the screen started) are never shown. */
    fun ignoreUpTo(stale: RemoteRingEnd?) {
        handledSeq = maxOf(handledSeq, stale?.seq ?: 0)
    }

    /**
     * Feed each store emission with the latest REMOTE notice. Returns true when the screen should
     * finish immediately.
     */
    fun onState(state: RingState?, remote: RemoteRingEnd? = null): Boolean {
        val fresh = remote?.takeIf { it.seq > handledSeq && it.ringId == ringId }
        if (state == null) {
            if (fresh != null && fresh.endsRing && fresh.kind == MirrorKind.DISMISS) show(fresh, closing = true)
            endPending = ringId != null && !closing
            return false
        }
        if (state.snoozedUntil != null) {
            val remoteSnooze = fresh != null && fresh.endsRing && fresh.kind == MirrorKind.SNOOZE &&
                state.ringId == fresh.ringId && state.snoozedUntil == fresh.snoozedUntil
            if (remoteSnooze) {
                show(fresh!!, closing = true)
                return false
            }
            return ringId != null && !closing
        }
        val shown = ring
        if (state.ringId != ringId) {
            // A superseding ring replaces any notice.
            notice = null
            closing = false
        } else if (fresh != null && !fresh.endsRing && shown != null && fresh.member in shown.members() && fresh.member !in state.members()) {
            show(fresh, closing = false)
        }
        // First ring seen, a superseding ring, or the shown one changed (merge, promotion): adopt it.
        ring = state
        endPending = false
        return false
    }

    /** The notice has been up for [REMOTE_NOTICE_MS]. True when the screen should finish now. */
    fun onNoticeElapsed(): Boolean {
        if (closing) return true
        notice = null
        return false
    }

    /** The grace period elapsed. Finish only if no active ring was ever seen. */
    fun onGraceElapsed(): Boolean = ringId == null

    private fun show(end: RemoteRingEnd, closing: Boolean) {
        handledSeq = end.seq
        notice = end
        this.closing = closing
    }
}

/**
 * [onRing] is called with the ring being shown whenever it changes (first seen, superseded, a member
 * merged or promoted), [onNotice] with each REMOTE notice to show and with null once it is gone.
 * Returns when the screen should finish: the ring was snoozed, [userStopped] completed, the ring
 * ended and no new one arrived within [endDebounceMs] (or [noticeMs] after a REMOTE end), or no
 * active ring appeared within [graceMs]. [remoteNotices] is read with every state; its value at the
 * start is stale.
 */
suspend fun awaitRingEnd(
    states: Flow<RingState?>,
    model: RingingScreenModel = RingingScreenModel(),
    graceMs: Long = RING_GRACE_MS,
    endDebounceMs: Long = END_DEBOUNCE_MS,
    userStopped: CompletableDeferred<Unit> = CompletableDeferred(),
    remoteNotices: StateFlow<RemoteRingEnd?> = MutableStateFlow(null),
    noticeMs: Long = REMOTE_NOTICE_MS,
    onNotice: (RemoteRingEnd?) -> Unit = {},
    onRing: (RingState) -> Unit = {},
) = coroutineScope {
    val ended = CompletableDeferred<Unit>()
    var endTimer: Job? = null
    var noticeTimer: Job? = null
    model.ignoreUpTo(remoteNotices.value)
    val collector = launch {
        states.collect {
            val before = model.ring
            val noticeBefore = model.notice
            val done = model.onState(it, remoteNotices.value)
            model.ring?.takeIf { r -> r != before }?.let(onRing)
            if (model.notice !== noticeBefore) {
                noticeTimer?.cancel()
                noticeTimer = null
                onNotice(model.notice)
                if (model.notice != null) {
                    noticeTimer = launch {
                        delay(noticeMs)
                        if (model.onNoticeElapsed()) ended.complete(Unit) else onNotice(null)
                    }
                }
            }
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
    noticeTimer?.cancel()
    timer.cancel()
    stop.cancel()
}
