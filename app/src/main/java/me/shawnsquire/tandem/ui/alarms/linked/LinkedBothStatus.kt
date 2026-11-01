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
    val outcome: CommandUiStatus? = null,
)

/** True while `link.pendingBoth` still holds [sent]. */
internal fun isStillPending(sent: SentBoth, alarm: Alarm): Boolean {
    val pending = alarm.link?.pendingBoth ?: return false
    return pending.type == sent.type && pending.occurrenceAt == sent.occurrenceAt
}

/**
 * Applied or Rejected once [sent] has settled, null while it is still on its way. `sendBoth` returns Unit, so
 * the outcome is read off the row once `link.pendingBoth` no longer holds the command.
 */
internal fun settleBoth(sent: SentBoth, alarm: Alarm, rejectedReason: String): CommandUiStatus? {
    val rejected = CommandUiStatus.Rejected(rejectedReason)
    if (sent.failed) return rejected
    val link = alarm.link ?: return rejected
    if (isStillPending(sent, alarm) || !sent.returned) return null
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

/** Pending while the command is in flight or still held on the row, else its latched or freshly settled outcome. */
internal fun deriveBothStatus(sent: SentBoth, alarm: Alarm, rejectedReason: String): CommandUiStatus =
    sent.outcome ?: settleBoth(sent, alarm, rejectedReason) ?: CommandUiStatus.Pending
