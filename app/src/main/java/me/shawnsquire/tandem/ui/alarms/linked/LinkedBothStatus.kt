package me.shawnsquire.tandem.ui.alarms.linked

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.ui.partner.CommandUiStatus
import java.time.Instant

/** The "both of us" command this sheet sent. [returned] is set once `sendBoth` came back, [failed] if it threw. */
data class SentBoth(
    val type: CommandType,
    val occurrenceAt: Instant?,
    val returned: Boolean = false,
    val failed: Boolean = false,
)

/**
 * `sendBoth` returns Unit, so the outcome is read off the row: Pending while `link.pendingBoth` still holds
 * [sent] (or the send is in flight), then Applied if the owner's state now reflects it, else Rejected.
 */
internal fun deriveBothStatus(sent: SentBoth, alarm: Alarm, rejectedReason: String): CommandUiStatus {
    val rejected = CommandUiStatus.Rejected(rejectedReason)
    if (sent.failed) return rejected
    val link = alarm.link ?: return rejected
    val pending = link.pendingBoth
    if (pending != null && pending.type == sent.type && pending.occurrenceAt == sent.occurrenceAt) {
        return CommandUiStatus.Pending
    }
    if (!sent.returned) return CommandUiStatus.Pending
    val reflected = when (sent.type) {
        CommandType.SKIP_NEXT -> link.ownerSkippedOccurrenceAt == sent.occurrenceAt
        CommandType.UNSKIP -> link.ownerSkippedOccurrenceAt != sent.occurrenceAt
        CommandType.DISABLE -> !alarm.enabled
        CommandType.ENABLE -> alarm.enabled
        else -> false
    }
    return if (reflected) CommandUiStatus.Applied else rejected
}
