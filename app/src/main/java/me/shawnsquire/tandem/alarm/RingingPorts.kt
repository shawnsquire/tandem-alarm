package me.shawnsquire.tandem.alarm

import android.util.Log
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
 * swapped in later by assigning [delegate].
 *
 * While no delegate is attached every call is appended raw to [outbox] (device-protected, so it
 * survives a locked-boot process death) and the real mirror sees it later. The owner-side "is it
 * joined" check needs PartnerRepository, which isn't readable while locked, so it is not made here.
 * Assigning [delegate] drains the outbox in order into the new delegate, then forwards directly. A
 * queued call the delegate throws on is retried once, then logged and dropped (as
 * [SwitchableAlarmRepository] does), so it can't replay over later calls; each call leaves the outbox
 * as soon as it is handled. Under [lock] every call is either queued before the drain reads the
 * outbox or forwarded after it. [onLocal] costs one prefs commit while detached and never throws.
 */
class SwitchableRingMirrorPort(private val outbox: MirrorOutbox = InMemoryMirrorOutbox()) : RingMirrorPort {
    private val lock = Any()
    @Volatile private var attached: RingMirrorPort? = null

    /** [NoOpRingMirrorPort] until assigned. Assigning replays the queued calls into the new value, then forwards to it. */
    var delegate: RingMirrorPort
        get() = attached ?: NoOpRingMirrorPort
        set(value) {
            synchronized(lock) {
                drain(value)
                attached = value
            }
        }

    override fun onLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?) {
        val target = synchronized(lock) {
            attached ?: run {
                try {
                    outbox.append(MirrorCall(kind, members, snoozedUntil))
                } catch (e: Throwable) {
                    runCatching { Log.e(TAG, "queueing a mirror call failed; dropped", e) }
                }
                return
            }
        }
        target.onLocal(kind, members, snoozedUntil)
    }

    private fun drain(target: RingMirrorPort) {
        val queued = try {
            outbox.entries()
        } catch (e: Throwable) {
            runCatching { Log.e(TAG, "reading the mirror outbox failed", e) }
            return
        }
        for ((seq, c) in queued) {
            try {
                target.onLocal(c.kind, c.members, c.snoozedUntil)
            } catch (e: Throwable) {
                runCatching { Log.w(TAG, "replaying mirror call $c failed; retrying once", e) }
                try {
                    target.onLocal(c.kind, c.members, c.snoozedUntil)
                } catch (e: Throwable) {
                    runCatching { Log.e(TAG, "replaying mirror call $c failed twice; dropped", e) }
                }
            }
            try {
                outbox.removeThrough(seq)
            } catch (e: Throwable) {
                runCatching { Log.e(TAG, "removing replayed mirror calls through $seq failed", e) }
            }
        }
    }

    private companion object {
        const val TAG = "RingMirrorPort"
    }
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
    /** Update the foreground notification: [label] (the resolved one, which a linked ring takes from its override or owner) plus Stop and Snooze actions. */
    fun showRinging(alarm: Alarm?, ringId: String, label: String?)

    /**
     * Ringing notification for a fallback ring with no stored RingState: full-screen and content
     * intents only, since Stop/Snooze would be ignored by the controller.
     */
    fun showFallbackRinging(ringId: String) {}

    /**
     * Foreground notification for a Notify-mode ring: heads-up with Stop and Snooze, no full-screen
     * intent. A later [showRinging] for the same [ringId] replaces it with the full ringing notification.
     */
    fun showNotifyRinging(alarm: Alarm?, ringId: String, label: String?) {}

    /** Removes [ringId]'s ringing notification (a stale ring cleared without a session to do it). */
    fun cancelRinging(ringId: String) {}

    /**
     * Non-foreground notification with a Dismiss action, one per ring so parked snoozes coexist.
     * [label] names the alarm (a merged ring's members each get their own); null or blank shows none.
     */
    fun showSnoozed(ringId: String, until: Instant, label: String? = null)
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
