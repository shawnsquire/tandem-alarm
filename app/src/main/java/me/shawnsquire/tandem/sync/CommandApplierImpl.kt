package me.shawnsquire.tandem.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.alarm.AlarmScheduler
import me.shawnsquire.tandem.alarm.RingingController
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.schedule.Alarm
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
    private val partnerName: StateFlow<String?>,
    private val processed: ProcessedCommands,
    private val notifier: CommandNotifier,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : CommandApplier {
    private val lock = Mutex()

    /**
     * The FCM path: applies only if the household is known and the server doc is still pending.
     * Otherwise throws [CommandNotVerifiedException] so the caller falls back to a household-bound pull.
     */
    override suspend fun apply(cmd: Command) {
        val hh = withTimeoutOrNull(2.seconds) { household.first() }
            ?: throw CommandNotVerifiedException("household unknown for ${cmd.id}")
        if (cmd.to != hh.myUid) {
            Log.w(TAG, "ignoring ${cmd.id}: addressed to someone else")
            return
        }
        // Network read stays outside the lock so a slow read never blocks other commands.
        val status = remote.status(hh.id, cmd.id)
            ?: throw CommandNotVerifiedException("status unknown for ${cmd.id}")
        if (status != CommandStatus.PENDING) return
        process(cmd, hh)
    }

    /** For callers that just read [cmd]'s doc as pending for [household]: skips the status read and the household wait. */
    internal suspend fun applyPending(household: Household, cmd: Command) = process(cmd, household)

    /** Runs in [scope] so cancelling the caller's wait (pre-fire/FCM timeouts) never tears a half-applied command. */
    private suspend fun process(cmd: Command, hh: Household) {
        scope.async {
            try {
                processLocked(cmd, hh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The caller may have timed out and stopped awaiting; don't lose the failure.
                Log.w(TAG, "processing ${cmd.id} failed", e)
                throw e
            }
        }.await()
    }

    private suspend fun processLocked(cmd: Command, hh: Household) {
        val hid = hh.id
        var notify: Triple<Command, Alarm?, Boolean>? = null
        lock.withLock {
            if (cmd.to != hh.myUid) {
                Log.w(TAG, "ignoring ${cmd.id}: addressed to someone else")
                return
            }
            processed.outcome(cmd.id)?.let { prior ->
                resolve(hid, cmd.id, prior)
                return
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
                    // A skip or turn-off is written under the ringing lock, so a ring starting now
                    // either sees it or makes this a rejection with nothing written (I1, I3).
                    val written = if (cmd.type == CommandType.SKIP_NEXT || cmd.type == CommandType.DISABLE) {
                        ringing.writeUnlessRinging(cmd.alarmId, cmd.occurrenceAt) { applyLocally(cmd, turnedOff) }
                    } else {
                        applyLocally(cmd, turnedOff)
                        true
                    }
                    if (!written) outcome = Outcome.Rejected("already ringing")
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
            resolve(hid, cmd.id, outcome)
            if (outcome == Outcome.Applied) notify = Triple(cmd, alarm, turnedOff)
        }
        notify?.let { (c, alarm, turnedOff) ->
            try {
                val name = partnerName.value
                notifier.notifyApplied(c, alarm, name?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME, turnedOff)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "notifying ${c.id} failed", e)
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
            CommandType.SNOOZE, CommandType.INVITE_LINK -> Unit // validate() rejects these until linked alarms land
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

/** The household or the command doc couldn't be verified, so the command was not applied. */
class CommandNotVerifiedException(message: String) : Exception(message)
