package me.shawnsquire.tandem.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.alarm.AlarmScheduler
import me.shawnsquire.tandem.alarm.RingingController
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandDecision
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.isOneShot
import me.shawnsquire.tandem.schedule.validate
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Owner-side [CommandApplier] (I2, I3): validates a partner command against local state, applies it
 * through the repositories, records the outcome, tells the sender, and notifies the owner.
 * Republishing is left to the StatePublisher, which observes the alarm and ring flows.
 */
class CommandApplierImpl(
    private val alarms: AlarmRepository,
    private val ringState: RingStateStore,
    private val scheduler: AlarmScheduler,
    private val ringing: RingingController,
    private val remote: CommandRemote,
    private val household: Flow<Household?>,
    private val partnerName: Flow<String?>,
    private val processed: ProcessedCommands,
    private val notifier: CommandNotifier,
    private val clock: Clock,
) : CommandApplier {
    private val lock = Mutex()

    override suspend fun apply(cmd: Command) {
        val hh = withTimeoutOrNull(2.seconds) { household.first() }
        process(cmd, hh, checkDocStatus = true)
    }

    /** For callers that just read [cmd]'s doc as pending for [household]: skips the status read and the household wait. */
    internal suspend fun applyPending(household: Household, cmd: Command) =
        process(cmd, household, checkDocStatus = false)

    private suspend fun process(cmd: Command, hh: Household?, checkDocStatus: Boolean) =
        lock.withLock {
            val hid = hh?.id
            if (hh != null && cmd.to != hh.myUid) {
                Log.w(TAG, "ignoring ${cmd.id}: addressed to someone else")
                return@withLock
            }
            processed.outcome(cmd.id)?.let { prior ->
                if (hid != null) resolve(hid, cmd.id, prior)
                return@withLock
            }
            if (checkDocStatus && hid != null) {
                val status = remote.status(hid, cmd.id)
                if (status != null && status != CommandStatus.PENDING) return@withLock
            }

            val alarm = alarms.get(cmd.alarmId)
            val decision = validate(cmd, alarm, ringState.state.first(), clock.instant(), clock.zone)
            var outcome: Outcome = when (decision) {
                CommandDecision.Apply -> Outcome.Applied
                is CommandDecision.Reject -> Outcome.Rejected(decision.reason)
            }
            var turnedOff = false
            if (outcome == Outcome.Applied) {
                try {
                    turnedOff = cmd.type == CommandType.SKIP_NEXT && alarm?.isOneShot() == true
                    applyLocally(cmd, turnedOff)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "applying ${cmd.id} failed", e)
                    outcome = Outcome.Rejected("couldn't apply")
                }
            }

            processed.record(cmd.id, outcome, cmd.expiresAt)

            if (outcome == Outcome.Applied) {
                try {
                    scheduler.rescheduleAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "reschedule after ${cmd.id} failed", e)
                }
            }
            if (hid != null) resolve(hid, cmd.id, outcome)
            if (outcome == Outcome.Applied) {
                try {
                    val name = withTimeoutOrNull(1.seconds) { partnerName.first() }
                    notifier.notifyApplied(cmd, alarm, name?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME, turnedOff)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "notifying ${cmd.id} failed", e)
                }
            }
        }

    private suspend fun applyLocally(cmd: Command, turnedOff: Boolean) {
        when (cmd.type) {
            CommandType.SKIP_NEXT ->
                if (turnedOff) alarms.setEnabled(cmd.alarmId, false) else alarms.setSkip(cmd.alarmId, cmd.occurrenceAt)
            CommandType.UNSKIP -> alarms.setSkip(cmd.alarmId, null)
            CommandType.DISABLE -> alarms.setEnabled(cmd.alarmId, false)
            CommandType.ENABLE -> alarms.setEnabled(cmd.alarmId, true)
            CommandType.STOP_RINGING -> ringing.dismiss(requireNotNull(cmd.ringId))
        }
    }

    private fun resolve(hid: String, id: String, outcome: Outcome) = when (outcome) {
        Outcome.Applied -> remote.resolve(hid, id, CommandStatus.APPLIED, null)
        is Outcome.Rejected -> remote.resolve(hid, id, CommandStatus.REJECTED, outcome.reason)
    }

    private companion object {
        const val TAG = "CommandApplier"
        const val FALLBACK_NAME = "Your partner"
    }
}
