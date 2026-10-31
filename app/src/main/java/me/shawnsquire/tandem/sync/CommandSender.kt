package me.shawnsquire.tandem.sync

import kotlinx.coroutines.flow.Flow
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import java.time.Instant

/**
 * Partner-side: sends a command (I2) pinned to what was seen (I3): [occurrenceAt] for
 * skip/unskip/disable, [ringId] for stop-ringing. Emits status updates paired with the
 * reject reason (non-null only for REJECTED).
 */
interface CommandSender {
    fun send(
        type: CommandType,
        alarmId: String,
        occurrenceAt: Instant?,
        ringId: String?,
    ): Flow<Pair<CommandStatus, String?>>
}
