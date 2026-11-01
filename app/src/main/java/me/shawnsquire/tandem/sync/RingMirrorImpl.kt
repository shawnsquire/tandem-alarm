package me.shawnsquire.tandem.sync

import android.util.Log
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.alarm.MirrorKind
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.RingMember

/**
 * Mirrors a LOCAL snooze or dismiss to the linked phone. [onLocal] only enqueues; one consumer in
 * [scope] handles the calls strictly in order and never touches the RingingController.
 *
 * Each call first waits (up to [householdWait]) for the household, because the locked-boot outbox is
 * drained before it is known and the sender would answer "not paired"; on timeout the call is
 * dropped (a stale command would be rejected by I3 anyway). Per member:
 *  - linked row (joiner): always sent, to `link.ownerAlarmId`, unless the row is pending removal or
 *    its owner is not the current partner;
 *  - own row (owner): sent only if the partner's published links include it, reading the partner
 *    state for at most [partnerWait].
 */
class RingMirrorImpl(
    private val alarms: AlarmRepository,
    private val partner: Flow<PartnerState?>,
    private val household: Flow<Household?>,
    private val sender: CommandSender,
    private val removals: LinkRemovals,
    private val scope: CoroutineScope,
    private val householdWait: Duration = 2.minutes,
    private val partnerWait: Duration = 10.seconds,
) : RingMirror {
    private class Call(val kind: MirrorKind, val members: List<RingMember>, val snoozedUntil: Instant?)

    private val queue = Channel<Call>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (call in queue) {
                try {
                    process(call)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "mirroring ${call.kind} failed", e)
                }
            }
        }
    }

    override fun onLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?) {
        queue.trySend(Call(kind, members, snoozedUntil))
    }

    /** No-op: [me.shawnsquire.tandem.alarm.SwitchableRingMirrorPort] owns the device-protected outbox and drains it into this mirror when it is assigned. */
    override suspend fun flushOutbox() = Unit

    private suspend fun process(call: Call) {
        val h = withTimeoutOrNull(householdWait) { household.filterNotNull().first() }
        if (h == null) {
            Log.w(TAG, "household unknown after $householdWait; dropping ${call.kind}")
            return
        }
        for (member in call.members) {
            try {
                mirror(call, member, h)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "mirroring ${call.kind} for ${member.alarmId} failed", e)
            }
        }
    }

    private suspend fun mirror(call: Call, member: RingMember, h: Household) {
        if (call.kind == MirrorKind.SNOOZE && call.snoozedUntil == null) {
            Log.w(TAG, "SNOOZE without a resume time for ${member.alarmId}; skipped")
            return
        }
        val row = alarms.get(member.alarmId)
        if (row == null) {
            Log.w(TAG, "no row ${member.alarmId}; skipped")
            return
        }
        val link = row.link
        val targetId = if (link != null) {
            if (row.id in removals.all().keys) {
                Log.i(TAG, "${row.id} is pending removal; not mirrored")
                return
            }
            if (link.ownerUid != h.partnerUid) {
                Log.i(TAG, "${row.id} belongs to another partner; not mirrored")
                return
            }
            link.ownerAlarmId
        } else {
            val state = withTimeoutOrNull(partnerWait) { partner.filterNotNull().first() }
            if (state == null) {
                Log.w(TAG, "partner state unknown; ${row.id} not mirrored")
                return
            }
            if (state.links.none { it.ownerAlarmId == row.id }) return
            row.id
        }
        val status = when (call.kind) {
            MirrorKind.SNOOZE -> sender.send(CommandType.SNOOZE, targetId, member.occurrenceAt, null, call.snoozedUntil)
            MirrorKind.DISMISS -> sender.send(CommandType.STOP_RINGING, targetId, member.occurrenceAt, null)
        }
        scope.launch {
            try {
                status.collect { (s, reason) ->
                    if (s == CommandStatus.REJECTED) Log.i(TAG, "${call.kind} for $targetId rejected: $reason")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "watching ${call.kind} for $targetId failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "RingMirror"
    }
}
