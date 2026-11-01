package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.shawnsquire.tandem.schedule.RingMember
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * A REMOTE snooze or dismiss (a command received from the partner's phone) that touched the slot
 * ring [ringId]. [endsRing] is true when the whole ring ended or was snoozed, false when only
 * [member] left it and the ring sounds on. [member] is null for a primary whose occurrence isn't
 * known. [seq] orders notices within the process: a reader keeps the last one it handled and only
 * uses newer ones, since the same ringId comes back after a snooze (I3).
 */
data class RemoteRingEnd(
    val ringId: String,
    val member: RingMember?,
    val kind: MirrorKind,
    val snoozedUntil: Instant?,
    val endsRing: Boolean,
    val seq: Long = 0,
)

/**
 * In-process signal from the controller to the ringing screen: RingState doesn't record who ended
 * a ring. The controller emits before its state write, so a reader that sees the state change can
 * read [latest] and already find the notice, and [withdraw]s it when that write throws, so it never
 * explains a change that didn't happen. Never throws or blocks (it runs under the slot lock).
 */
class RemoteRingNotices {
    private val _latest = MutableStateFlow<RemoteRingEnd?>(null)
    val latest: StateFlow<RemoteRingEnd?> = _latest.asStateFlow()

    /** Kept apart from [latest]: a withdrawal must not restart the numbering a reader compares against. */
    private val lastSeq = AtomicLong()

    /** Publishes [end] with the next [RemoteRingEnd.seq]; returns what was published, for [withdraw]. */
    fun emit(end: RemoteRingEnd): RemoteRingEnd = end.copy(seq = lastSeq.incrementAndGet()).also { _latest.value = it }

    /** Takes [emitted] back, if it is still the latest. */
    fun withdraw(emitted: RemoteRingEnd) {
        _latest.compareAndSet(emitted, null)
    }
}
