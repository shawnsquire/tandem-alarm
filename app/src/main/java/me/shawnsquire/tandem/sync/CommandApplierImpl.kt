package me.shawnsquire.tandem.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
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
import me.shawnsquire.tandem.schedule.RingOrigin
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
    /**
     * The partner's published state, including their alarms. INVITE_LINK checks the owner's alarm
     * is in it and calls CommandNotifier.notifyInvite(cmd, partnerAlarm, name).
     */
    private val partner: Flow<PartnerState?>,
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
        // The partner's alarm for INVITE_LINK; read before the lock so a slow flow never blocks other commands.
        val partnerState = if (cmd.type == CommandType.INVITE_LINK) {
            withTimeoutOrNull(2.seconds) { partner.firstOrNull { it != null } }
        } else {
            null
        }
        var notify: Triple<Command, Alarm?, Boolean>? = null
        var invite: Pair<Command, PartnerAlarm?>? = null
        lock.withLock {
            if (cmd.to != hh.myUid) {
                Log.w(TAG, "ignoring ${cmd.id}: addressed to someone else")
                return
            }
            processed.outcome(cmd.id)?.let { prior ->
                resolve(hid, cmd.id, prior)
                return
            }

            val isRing = cmd.type == CommandType.STOP_RINGING || cmd.type == CommandType.SNOOZE
            // A partner command never targets a linked row's own id: a hit on a linked row goes to
            // validate unmapped and is rejected. Only an unmatched id on a ring command is the
            // owner's alarm id, mapped to our linked row for that owner (I2). SKIP/UNSKIP/DISABLE/ENABLE
            // flow joiner-to-owner only and are never mapped.
            var alarm = if (cmd.type == CommandType.INVITE_LINK) null else alarms.get(cmd.alarmId)
            var local = cmd
            if (alarm == null && isRing) {
                val row = alarms.linkedAlarms.first()
                    .firstOrNull { it.link?.ownerAlarmId == cmd.alarmId && it.link.ownerUid == cmd.from }
                if (row != null) {
                    alarm = row
                    local = cmd.copy(alarmId = row.id)
                }
            }
            val ownIdOfLinkedRow = local === cmd && alarm?.link != null
            // Outside writeUnlessRinging: ringContext takes the controller's lock.
            val context = if (isRing && !ownIdOfLinkedRow) ringing.ringContext(local.alarmId) else null
            val decision = if (ownIdOfLinkedRow) {
                // validate accepts a linked row for ring commands (it expects the mapped id), so a
                // partner command naming the row's own id is refused here.
                CommandDecision.Reject("alarm not found")
            } else {
                validate(local, alarm, ringState.state.first(), clock.instant(), clock.zone, context)
            }
            var outcome: Outcome = when (decision) {
                CommandDecision.Apply -> Outcome.Applied
                is CommandDecision.Reject -> Outcome.Rejected(decision.reason)
            }

            if (outcome == Outcome.Applied && cmd.type == CommandType.INVITE_LINK) {
                // validate lets any invite through (there is no local row), so check it here.
                val name = partnerName.value?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME
                val ownerAlarm = partnerState?.alarms?.firstOrNull { it.id == cmd.alarmId }
                when {
                    partnerState == null -> return // unknown: stay pending, re-delivered later
                    ownerAlarm == null -> outcome = Outcome.Rejected("$name deleted this alarm")
                    alarms.linkedAlarms.first().any { it.link?.ownerAlarmId == cmd.alarmId } ->
                        outcome = Outcome.Rejected("already joined")
                    else -> {
                        // Left pending on purpose: the owner reads APPLIED as accepted, so only
                        // LinkRepository.acceptInvite/declineInvite record and resolve it.
                        invite = cmd to ownerAlarm
                        return@withLock
                    }
                }
            }

            var turnedOff = false
            if (outcome == Outcome.Applied) {
                try {
                    turnedOff = cmd.type == CommandType.SKIP_NEXT && alarm?.isOneShot() == true
                    // A skip or turn-off is written under the ringing lock, so a ring starting now
                    // either sees it or makes this a rejection with nothing written (I1, I3).
                    val written = if (cmd.type == CommandType.SKIP_NEXT || cmd.type == CommandType.DISABLE) {
                        ringing.writeUnlessRinging(local.alarmId, cmd.occurrenceAt) { applyLocally(local, alarm, turnedOff) }
                    } else {
                        applyLocally(local, alarm, turnedOff)
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
            // A snooze shows on the ringing screen; only the other commands get a notification.
            if (outcome == Outcome.Applied && cmd.type != CommandType.SNOOZE) notify = Triple(cmd, alarm, turnedOff)
        }
        val name = partnerName.value?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME
        invite?.let { (c, ownerAlarm) ->
            try {
                notifier.notifyInvite(c, ownerAlarm, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "notifying invite ${c.id} failed", e)
            }
        }
        notify?.let { (c, alarm, turnedOff) ->
            try {
                notifier.notifyApplied(c, alarm, name, turnedOff)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "notifying ${c.id} failed", e)
            }
        }
    }

    /** [cmd] carries the local alarm id (a joiner's linked row for a mapped ring command); [alarm] is that row as validated. */
    private suspend fun applyLocally(cmd: Command, alarm: Alarm?, turnedOff: Boolean) {
        when (cmd.type) {
            CommandType.SKIP_NEXT -> {
                if (turnedOff) alarms.setEnabled(cmd.alarmId, false) else alarms.setSkip(cmd.alarmId, cmd.occurrenceAt)
                // The shared skip now covers a solo skip on the same occurrence (a one-shot is off anyway).
                val solo = alarm?.soloSkippedOccurrenceAt
                if (solo != null && (turnedOff || solo == cmd.occurrenceAt)) alarms.setSoloSkip(cmd.alarmId, null)
            }
            CommandType.UNSKIP -> alarms.setSkip(cmd.alarmId, null) // never the solo skip
            CommandType.DISABLE -> alarms.setEnabled(cmd.alarmId, false)
            CommandType.ENABLE -> alarms.setEnabled(cmd.alarmId, true)
            // Validated by occurrence when present (matching the rule validate used), else by ringId.
            CommandType.STOP_RINGING -> cmd.occurrenceAt?.let { ringing.dismissOccurrence(cmd.alarmId, it, RingOrigin.REMOTE) }
                ?: ringing.dismiss(requireNotNull(cmd.ringId), RingOrigin.REMOTE)
            CommandType.SNOOZE -> ringing.snoozeOccurrence(
                cmd.alarmId, requireNotNull(cmd.occurrenceAt), requireNotNull(cmd.snoozedUntil), RingOrigin.REMOTE,
            )
            CommandType.INVITE_LINK -> Unit // handled before this; the answer comes from LinkRepository
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
