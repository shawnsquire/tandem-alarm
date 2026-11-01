package me.shawnsquire.tandem.sync

import kotlinx.coroutines.flow.Flow
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import java.time.Instant

/**
 * Partner-side: sends a command (I2) pinned to what was seen (I3): [occurrenceAt] for
 * skip/unskip/disable, [ringId] for stop-ringing. Emits status updates paired with the
 * reject reason (non-null only for REJECTED); it ends at the answer, or still PENDING if its listener
 * fails, which is not an answer. [snoozedUntil] is the absolute resume time (SNOOZE only).
 * [onCreated] is called with the new command's id as soon as the doc is created (not at all if creating fails).
 */
interface CommandSender {
    fun send(
        type: CommandType,
        alarmId: String,
        occurrenceAt: Instant?,
        ringId: String?,
        snoozedUntil: Instant? = null,
        onCreated: (String) -> Unit = {},
    ): Flow<Pair<CommandStatus, String?>>
}
