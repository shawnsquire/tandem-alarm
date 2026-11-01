package me.shawnsquire.tandem.ui.alarms.linked

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.isOneShot
import me.shawnsquire.tandem.ui.partner.CommandUiStatus
import java.time.Instant

/**
 * The "both of us" command this sheet sent. [returned] is set once `sendBoth` came back, [failed] if it threw.
 * [outcome] latches Applied or Rejected the first time the command settles, so later row changes (the owner
 * turning the alarm back on, SkipExpiry clearing the skip) do not rewrite it.
 */
data class SentBoth(
    val type: CommandType,
    val occurrenceAt: Instant?,
    val returned: Boolean = false,
    val failed: Boolean = false,
    /** A row emission showed the command pending, so later emissions postdate the write. */
    val seenPending: Boolean = false,
    val outcome: CommandUiStatus? = null,
)

/** True while `link.pendingBoth` still holds [sent]. */
internal fun isStillPending(sent: SentBoth, alarm: Alarm): Boolean {
    val pending = alarm.link?.pendingBoth ?: return false
    return pending.type == sent.type && pending.occurrenceAt == sent.occurrenceAt
}

/**
 * Applied or Rejected once [sent] has settled, null while it is still on its way. `sendBoth` returns Unit, so
 * the outcome is read off the row once `link.pendingBoth` no longer holds the command. A snapshot that never
 * showed the command pending may predate the write, so it settles nothing ([seenPending]).
 */
internal fun settleBoth(sent: SentBoth, alarm: Alarm, rejectedReason: String): CommandUiStatus? {
    val rejected = CommandUiStatus.Rejected(rejectedReason)
    if (sent.failed) return rejected
    val link = alarm.link ?: return rejected
    if (isStillPending(sent, alarm) || !sent.returned || !sent.seenPending) return null
    val reflected = when (sent.type) {
        // A one-shot has no skip to set: the owner turns it off instead.
        CommandType.SKIP_NEXT ->
            (alarm.isOneShot() && !alarm.enabled) || link.ownerSkippedOccurrenceAt == sent.occurrenceAt
        CommandType.UNSKIP -> link.ownerSkippedOccurrenceAt != sent.occurrenceAt
        CommandType.DISABLE -> !alarm.enabled
        CommandType.ENABLE -> alarm.enabled
        else -> false
    }
    return if (reflected) CommandUiStatus.Applied else rejected
}

/**
 * What the sheet shows: the latched outcome once the ViewModel settled the command, Rejected for a failed send,
 * else Pending. It never settles from the row itself, which could be a snapshot that predates the write.
 */
internal fun displayBoth(sent: SentBoth, rejectedReason: String): CommandUiStatus =
    sent.outcome ?: if (sent.failed) CommandUiStatus.Rejected(rejectedReason) else CommandUiStatus.Pending
