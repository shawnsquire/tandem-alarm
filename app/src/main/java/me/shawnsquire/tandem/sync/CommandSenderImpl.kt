package me.shawnsquire.tandem.sync

import android.util.Log
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType

/**
 * Creates the command doc when [send] is called (not on collect, so collecting twice never sends
 * twice) and reports its status until the owner resolves it or the listener fails (the flow then ends
 * at PENDING; collecting it again listens again). The caller supplies the pins
 * (`occurrenceAt`, `ringId`) from the partner state it is looking at (I3); nothing is computed here.
 */
class CommandSenderImpl(
    private val remote: CommandRemote,
    private val household: StateFlow<Household?>,
    private val clock: Clock,
) : CommandSender {
    override fun send(
        type: CommandType,
        alarmId: String,
        occurrenceAt: Instant?,
        ringId: String?,
        snoozedUntil: Instant?,
        onCreated: (String) -> Unit,
    ): Flow<Pair<CommandStatus, String?>> {
        val h = household.value ?: return flowOf(CommandStatus.REJECTED to "not paired")
        val id = try {
            remote.create(h.id, h.myUid, h.partnerUid, type, alarmId, occurrenceAt, ringId, clock.instant(), snoozedUntil)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "create command failed", e)
            return flowOf(CommandStatus.REJECTED to "couldn't send")
        }
        onCreated(id)
        return remote.observe(h.id, id)
            .onStart { emit(CommandStatus.PENDING to null) }
            .distinctUntilChanged()
            .transformWhile { update ->
                emit(update)
                update.first == CommandStatus.PENDING
            }
    }

    private companion object {
        const val TAG = "CommandSender"
    }
}
