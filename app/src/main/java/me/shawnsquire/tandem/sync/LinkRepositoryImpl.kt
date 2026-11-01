package me.shawnsquire.tandem.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.alarm.AlarmScheduler
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Joiner and owner operations on linked alarms (see [LinkRepository]). Rows are created here and
 * changed by [LinkSync]; this class only writes through [AlarmRepository]'s linked-row methods.
 *
 * Open invites are kept in memory only, so a process death forgets them until the partner joins.
 */
class LinkRepositoryImpl(
    private val alarms: AlarmRepository,
    private val partner: Flow<PartnerState?>,
    private val household: Flow<Household?>,
    private val sender: CommandSender,
    private val remote: CommandRemote,
    private val processed: ProcessedCommands,
    private val removals: LinkRemovals,
    private val requestRemoval: suspend (String, RemovalReason) -> Unit,
    private val scheduler: AlarmScheduler,
    private val notifier: CommandNotifier,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : LinkRepository {
    private val joinLock = Mutex()
    private val watchLock = Any()
    private val watching = mutableSetOf<String>()
    private var started = false

    /** Own alarm id to the number of open invites for it. */
    private val invites = MutableStateFlow<Map<String, Int>>(emptyMap())

    override val openInvites: Flow<Set<String>> = invites.map { it.keys }.distinctUntilChanged()

    override val links: Flow<List<PublishedLink>> =
        combine(alarms.linkedAlarms, removals.pendingIds) { rows, pendingRemoval ->
            rows.filter { it.id !in pendingRemoval }
                .mapNotNull { r -> r.link?.let { PublishedLink(it.ownerAlarmId, it.mode) } }
                .distinct()
                .sortedBy { it.ownerAlarmId }
        }.distinctUntilChanged()

    /** Re-observes every both-of-us command still pending after a restart. Idempotent. */
    internal fun start() {
        synchronized(watchLock) {
            if (started) return
            started = true
        }
        scope.launch {
            val upperBound = clock.instant().plus(COMMAND_TTL)
            for (row in alarms.linkedAlarms.first()) {
                val pending = row.link?.pendingBoth ?: continue
                watch(row.id, pending, upperBound)
            }
        }
    }

    override suspend fun join(ownerAlarmId: String): LinkResult = joinInternal(ownerAlarmId, acceptOpenInvites = true)

    override suspend fun acceptInvite(commandId: String, ownerAlarmId: String): LinkResult {
        val result = joinInternal(ownerAlarmId, acceptOpenInvites = false)
        val hh = knownHousehold()
        when {
            result is LinkResult.Joined -> if (hh != null) {
                resolveCommand(hh, commandId, Outcome.Applied, clock.instant().plus(COMMAND_TTL))
            }
            result is LinkResult.Failed && result.reason.endsWith(DELETED_SUFFIX) -> if (hh != null) {
                resolveCommand(hh, commandId, Outcome.Rejected(result.reason), clock.instant().plus(COMMAND_TTL))
            }
            else -> Unit // not paired etc.: leave the command pending
        }
        return result
    }

    override suspend fun declineInvite(commandId: String) {
        val hh = knownHousehold()
        if (hh == null) {
            Log.w(TAG, "declineInvite $commandId: household unknown")
            return
        }
        resolveCommand(hh, commandId, Outcome.Rejected(DECLINED), clock.instant().plus(COMMAND_TTL))
    }

    override suspend fun leave(alarmId: String) {
        if (alarms.get(alarmId)?.link == null) return
        requestRemoval(alarmId, RemovalReason.LEFT)
        scheduler.rescheduleAll()
    }

    override suspend fun setMode(alarmId: String, mode: LinkMode) {
        alarms.updateLink(alarmId) { it.copy(mode = mode) }
        scheduler.rescheduleAll()
    }

    override suspend fun setOverrides(alarmId: String, label: String?, ringtoneUri: String?, wallpaperPath: String?) {
        alarms.updateLink(alarmId) {
            it.copy(overrideLabel = label, overrideRingtoneUri = ringtoneUri, overrideWallpaperPath = wallpaperPath)
        }
        scheduler.rescheduleAll()
    }

    override suspend fun setJoinerEnabled(alarmId: String, enabled: Boolean) {
        alarms.setJoinerEnabled(alarmId, enabled)
        scheduler.rescheduleAll()
    }

    override suspend fun sendBoth(alarmId: String, type: CommandType, occurrenceAt: Instant?) {
        val link = requireNotNull(alarms.get(alarmId)?.link) { "alarm $alarmId is not linked" }
        val hh = checkNotNull(knownHousehold()) { NOT_PAIRED }
        val now = clock.instant()
        val id = remote.create(hh.id, hh.myUid, hh.partnerUid, type, link.ownerAlarmId, occurrenceAt, null, now)
        val pending = PendingBoth(type, occurrenceAt, id)
        alarms.updateLink(alarmId) {
            it.copy(
                pendingBoth = pending,
                joinerEnabled = if (type == CommandType.ENABLE) true else it.joinerEnabled,
            )
        }
        scheduler.rescheduleAll()
        watch(alarmId, pending, now.plus(COMMAND_TTL))
    }

    override fun invite(alarmId: String): Flow<Pair<CommandStatus, String?>> {
        val status = sender.send(CommandType.INVITE_LINK, alarmId, null, null)
        invites.update { it + (alarmId to (it[alarmId] ?: 0) + 1) }
        scope.launch {
            try {
                withTimeoutOrNull(COMMAND_TTL.toMillis()) { status.firstOrNull { it.first != CommandStatus.PENDING } }
            } finally {
                invites.update { m ->
                    val n = (m[alarmId] ?: 0) - 1
                    if (n <= 0) m - alarmId else m + (alarmId to n)
                }
            }
        }
        return status
    }

    private suspend fun joinInternal(ownerAlarmId: String, acceptOpenInvites: Boolean): LinkResult =
        joinLock.withLock {
            val hh = knownHousehold() ?: return LinkResult.Failed(NOT_PAIRED)
            val state = withTimeoutOrNull(PARTNER_WAIT) { partner.first { it != null } }
                ?: return LinkResult.Failed(NOT_PAIRED)

            val existing = alarms.linkedAlarms.first().firstOrNull {
                it.link?.ownerAlarmId == ownerAlarmId && it.link?.ownerUid == hh.partnerUid
            }
            if (existing != null) {
                if (removals.all()[existing.id] == RemovalReason.LEFT) removals.remove(existing.id)
                if (acceptOpenInvites) acceptOpenInvites(hh, ownerAlarmId)
                return LinkResult.Joined(existing.id)
            }

            val owner = state.alarms.firstOrNull { it.id == ownerAlarmId }
                ?: return LinkResult.Failed("${nameOf(state)}$DELETED_SUFFIX")

            val id = UUID.randomUUID().toString()
            alarms.upsertLinked(
                Alarm(
                    id = id,
                    label = owner.label,
                    hour = owner.hour,
                    minute = owner.minute,
                    days = owner.days,
                    enabled = owner.enabled,
                    skippedOccurrenceAt = null,
                    ringtoneUri = null,
                    wallpaperPath = null,
                    link = AlarmLink(
                        ownerUid = hh.partnerUid,
                        ownerAlarmId = ownerAlarmId,
                        mode = LinkMode.RING,
                        ownerSkippedOccurrenceAt = owner.skippedOccurrenceAt,
                        joinerEnabled = true,
                        pendingBoth = null,
                        overrideLabel = null,
                        overrideRingtoneUri = null,
                        overrideWallpaperPath = null,
                    ),
                ),
            )
            scheduler.rescheduleAll()
            if (acceptOpenInvites) acceptOpenInvites(hh, ownerAlarmId)
            LinkResult.Joined(id)
        }

    /** Best effort, never blocks the caller. */
    private fun acceptOpenInvites(hh: Household, ownerAlarmId: String) {
        scope.launch {
            try {
                remote.fetchPendingFromServer(hh.id, hh.myUid)
                    .filter { it.type == CommandType.INVITE_LINK && it.alarmId == ownerAlarmId && it.from == hh.partnerUid }
                    .forEach { resolveCommand(hh, it.id, Outcome.Applied, it.expiresAt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "accepting open invites for $ownerAlarmId failed", e)
            }
        }
    }

    private suspend fun resolveCommand(hh: Household, id: String, outcome: Outcome, expiresAt: Instant) {
        processed.record(id, outcome, expiresAt)
        when (outcome) {
            Outcome.Applied -> remote.resolve(hh.id, id, CommandStatus.APPLIED, null)
            is Outcome.Rejected -> remote.resolve(hh.id, id, CommandStatus.REJECTED, outcome.reason)
        }
    }

    /** Watches one both-of-us command in [scope] (not the caller's) until the owner answers or [expiresAt] passes. */
    private fun watch(rowId: String, pending: PendingBoth, expiresAt: Instant) {
        synchronized(watchLock) { if (!watching.add(pending.commandId)) return }
        scope.launch {
            try {
                val remaining = Duration.between(clock.instant(), expiresAt).toMillis().coerceAtLeast(0)
                // Waiting for a household and re-subscribing after a completed observe both count against the deadline.
                val done = withTimeoutOrNull(remaining) {
                    val hh = household.filterNotNull().first()
                    var update: Pair<CommandStatus, String?>? = null
                    while (update == null) {
                        update = remote.observe(hh.id, pending.commandId).firstOrNull { it.first != CommandStatus.PENDING }
                        if (update == null) delay(RESUBSCRIBE_DELAY)
                    }
                    update
                }
                if (done?.first == CommandStatus.APPLIED) return@launch
                val reason = done?.second ?: "expired"
                var cleared = false
                alarms.updateLink(rowId) {
                    if (it.pendingBoth?.commandId == pending.commandId) {
                        cleared = true
                        it.copy(pendingBoth = null)
                    } else {
                        it
                    }
                }
                if (!cleared) return@launch
                scheduler.rescheduleAll()
                alarms.get(rowId)?.let { notifier.notifyRejected(it, pending.type, reason, currentPartnerName()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "watching ${pending.commandId} failed", e)
            } finally {
                synchronized(watchLock) { watching.remove(pending.commandId) }
            }
        }
    }

    /** The household, or null if none arrives within [HOUSEHOLD_WAIT]. */
    private suspend fun knownHousehold(): Household? = withTimeoutOrNull(HOUSEHOLD_WAIT) { household.first() }

    private suspend fun currentPartnerName(): String =
        nameOf(withTimeoutOrNull(PARTNER_WAIT) { partner.first { it != null } })

    private fun nameOf(state: PartnerState?): String = state?.displayName?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME

    private companion object {
        const val TAG = "LinkRepository"
        const val NOT_PAIRED = "not paired"
        const val DECLINED = "declined"
        const val DELETED_SUFFIX = " deleted this alarm"
        const val FALLBACK_NAME = "Your partner"
        val PARTNER_WAIT = 2.seconds
        val HOUSEHOLD_WAIT = 2.seconds
        val RESUBSCRIBE_DELAY = 5.seconds
        val COMMAND_TTL: Duration = Duration.ofHours(24)
    }
}
