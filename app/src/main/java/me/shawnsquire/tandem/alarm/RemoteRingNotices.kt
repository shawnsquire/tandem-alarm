package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.shawnsquire.tandem.schedule.RingMember
import java.time.Instant

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
 * read [latest] and already find the notice. Never throws or blocks (it runs under the slot lock).
 */
class RemoteRingNotices {
    private val _latest = MutableStateFlow<RemoteRingEnd?>(null)
    val latest: StateFlow<RemoteRingEnd?> = _latest.asStateFlow()

    fun emit(end: RemoteRingEnd) {
        _latest.update { prev -> end.copy(seq = (prev?.seq ?: 0) + 1) }
    }
}
