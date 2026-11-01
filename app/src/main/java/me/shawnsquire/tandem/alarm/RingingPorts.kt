package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingMember
import java.time.Instant

enum class MirrorKind { SNOOZE, DISMISS }

/** Outbound side of a ring resolution: lets the sync layer mirror a LOCAL snooze or dismiss. */
interface RingMirrorPort {
    /**
     * Called by the controller after a LOCAL snooze or dismiss resolves [members]; never for REMOTE (I4).
     * [snoozedUntil] is the absolute T for SNOOZE, null for DISMISS. Must not block or call the controller.
     */
    fun onLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?)
}

object NoOpRingMirrorPort : RingMirrorPort {
    override fun onLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?) = Unit
}

/**
 * The engine is built before AppContainer (it must ring before first unlock), so the real mirror is
 * swapped in later through this.
 */
class SwitchableRingMirrorPort : RingMirrorPort {
    @Volatile var delegate: RingMirrorPort = NoOpRingMirrorPort
    override fun onLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?) =
        delegate.onLocal(kind, members, snoozedUntil)
}

/** Loops the first candidate URI that opens; the adapter appends system-default fallbacks after [candidates]. */
interface AlarmSound {
    fun start(candidates: List<String>)
    fun stop()
}

interface AlarmVibration {
    fun start()
    fun stop()
}

/** UI side effects of ringing. Implemented with NotificationManager in the Android layer. */
interface RingingNotifier {
    /** Update the foreground notification: label plus Stop and Snooze actions. */
    fun showRinging(alarm: Alarm?, ringId: String)

    /**
     * Ringing notification for a fallback ring with no stored RingState: full-screen and content
     * intents only, since Stop/Snooze would be ignored by the controller.
     */
    fun showFallbackRinging(ringId: String) {}

    /** Removes [ringId]'s ringing notification (a stale ring cleared without a session to do it). */
    fun cancelRinging(ringId: String) {}

    /** Non-foreground notification with a Dismiss action, one per ring so parked snoozes coexist. */
    fun showSnoozed(ringId: String, until: Instant)
    fun cancelSnoozed(ringId: String)
}

/**
 * Tries each candidate in order and returns the first non-null result of [open].
 * An exception from one candidate moves on to the next; all failing yields null.
 */
internal fun <T> openFirst(candidates: List<String>, open: (String) -> T?): T? =
    openFirstFrom(candidates, 0, open)?.second

/** Like [openFirst] but starts at index [from] and also returns the index that opened. */
internal fun <T> openFirstFrom(candidates: List<String>, from: Int, open: (String) -> T?): Pair<Int, T>? {
    for (i in from until candidates.size) {
        val opened = try {
            open(candidates[i])
        } catch (e: Exception) {
            null
        }
        if (opened != null) return i to opened
    }
    return null
}
