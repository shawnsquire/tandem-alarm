package me.shawnsquire.tandem.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Joiner and owner operations on linked alarms (see [LinkRepository]). Rows are created here and
 * changed by [LinkSync]; this class only writes through [AlarmRepository]'s linked-row methods.
 *
 * Open invites are also kept in [InviteLedger] and re-watched by [start], so a process death
 * doesn't forget one before the partner answers it or its command expires.
 */
class LinkRepositoryImpl(
    private val alarms: AlarmRepository,
    private val partner: Flow<PartnerState?>,
    private val household: Flow<Household?>,
    private val sender: CommandSender,
    private val remote: CommandRemote,
    private val processed: ProcessedCommands,
    private val inviteLedger: InviteLedger,
    private val removals: LinkRemovals,
    private val requestRemoval: suspend (String, RemovalReason) -> Unit,
    /** Cancels a pending removal under LinkSync's lock; false if the row is already gone. */
    private val cancelRemoval: suspend (String) -> Boolean,
    /** LinkSync.noteApplied(rowId, commandId): LinkSync clears the pending command from the owner's next doc. */
    private val noteApplied: suspend (String, String) -> Unit,
    /** LinkSync.appliedSettled(commandId): true once LinkSync has processed an owner doc newer than the application. */
    private val appliedSettled: suspend (String) -> Boolean,
    private val scheduler: AlarmScheduler,
    private val notifier: CommandNotifier,
    private val clock: Clock,
    private val scope: CoroutineScope,
    /** How long an APPLIED both-of-us command may wait for LinkSync to clear it before it is cleared here, silently. */
    private val appliedFallback: kotlin.time.Duration = 30.minutes,
    /** The most an APPLIED command waits for a newer owner doc (the command's own TTL), counted from when the watcher began. */
    private val appliedTtl: kotlin.time.Duration = 24.hours,
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

    /** Re-observes every both-of-us command and open invite still pending after a restart. Idempotent. */
    internal fun start() {
        synchronized(watchLock) {
            if (started) return
            started = true
        }
        scope.launch {
            val startedAt = clock.instant()
            for (row in alarms.linkedAlarms.first()) {
                val pending = row.link?.pendingBoth ?: continue
                watch(row.id, pending, expiryOf(pending, startedAt))
            }
            try {
                for (open in inviteLedger.open()) restoreInvite(open)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "restoring open invites failed", e)
            }
        }
    }

    override suspend fun join(ownerAlarmId: String): LinkResult {
        val hh = knownHousehold() ?: return LinkResult.Failed(NOT_PAIRED)
        return joinInternal(hh, ownerAlarmId, acceptOpenInvites = true)
    }

    override suspend fun acceptInvite(commandId: String, ownerAlarmId: String): LinkResult {
        val hh = knownHousehold() ?: return LinkResult.Failed(NOT_PAIRED) // leaves the command pending
        val result = joinInternal(hh, ownerAlarmId, acceptOpenInvites = false)
        val expiresAt = clock.instant().plus(COMMAND_TTL)
        when {
            result is LinkResult.Joined -> resolveCommand(hh, commandId, Outcome.Applied, expiresAt)
            result is LinkResult.Failed && result.reason.endsWith(DELETED_SUFFIX) ->
                resolveCommand(hh, commandId, Outcome.Rejected(result.reason), expiresAt)
            else -> Unit // other failures: leave the command pending
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
        // Stored before the command exists: if the process dies or the create throws in between, a
        // "both off" must already be recognised as the joiner's own, and a rejection must be reported.
        val id = remote.newId(hh.id)
        val pending = PendingBoth(type, occurrenceAt, id, now)
        var wasJoinerEnabled = link.joinerEnabled
        alarms.updateLink(alarmId) {
            wasJoinerEnabled = it.joinerEnabled
            it.copy(
                pendingBoth = pending,
                joinerEnabled = if (type == CommandType.ENABLE) true else it.joinerEnabled,
            )
        }
        scheduler.rescheduleAll()
        try {
            remote.create(hh.id, hh.myUid, hh.partnerUid, type, link.ownerAlarmId, occurrenceAt, null, now, commandId = id)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                alarms.updateLink(alarmId) {
                    if (it.pendingBoth?.commandId != id) it
                    else it.copy(
                        pendingBoth = null,
                        joinerEnabled = if (type == CommandType.ENABLE) wasJoinerEnabled else it.joinerEnabled,
                    )
                }
                scheduler.rescheduleAll()
            }
            throw e
        }
        watch(alarmId, pending, expiryOf(pending, now))
    }

    override fun invite(alarmId: String): Flow<Pair<CommandStatus, String?>> {
        var commandId: String? = null
        val status = sender.send(CommandType.INVITE_LINK, alarmId, null, null, onCreated = { commandId = it })
        invites.update { it + (alarmId to (it[alarmId] ?: 0) + 1) }
        val id = commandId
        val expiresAt = clock.instant().plus(COMMAND_TTL)
        scope.launch {
            // Persisted before waiting, so a process death from here on still counts it as open.
            if (id != null) persistOpen { inviteLedger.addOpen(id, alarmId, expiresAt) }
            settleInvite(alarmId, id, expiresAt, status)
        }
        return status
    }

    /** Counts a persisted open invite again and watches its command until it is answered or expires. */
    private fun restoreInvite(open: InviteLedger.OpenInvite) {
        synchronized(watchLock) { if (!watching.add(open.commandId)) return }
        invites.update { it + (open.alarmId to (it[open.alarmId] ?: 0) + 1) }
        scope.launch {
            try {
                val status = flow {
                    val hh = household.filterNotNull().first()
                    while (true) {
                        emitAll(remote.observe(hh.id, open.commandId))
                        delay(RESUBSCRIBE_DELAY)
                    }
                }
                settleInvite(open.alarmId, open.commandId, open.expiresAt, status)
            } finally {
                synchronized(watchLock) { watching.remove(open.commandId) }
            }
        }
    }

    /**
     * Holds the invite open until [status] reports an answer or [expiresAt] passes, then drops it from the
     * count and the ledger. A cancelled wait (the process going away) leaves the ledger entry for the next start.
     */
    private suspend fun settleInvite(
        alarmId: String, commandId: String?, expiresAt: Instant, status: Flow<Pair<CommandStatus, String?>>,
    ) {
        var settled = false
        try {
            val remaining = Duration.between(clock.instant(), expiresAt).toMillis().coerceAtLeast(0)
            withTimeoutOrNull(remaining) { status.firstOrNull { it.first != CommandStatus.PENDING } }
            settled = true
        } finally {
            invites.update { m ->
                val n = (m[alarmId] ?: 0) - 1
                if (n <= 0) m - alarmId else m + (alarmId to n)
            }
        }
        if (settled && commandId != null) persistOpen { inviteLedger.removeOpen(commandId) }
    }

    private suspend fun persistOpen(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "saving open invites failed", e)
        }
    }

    private suspend fun joinInternal(hh: Household, ownerAlarmId: String, acceptOpenInvites: Boolean): LinkResult =
        joinLock.withLock {
            val state = withTimeoutOrNull(PARTNER_WAIT) { partner.first { it != null } }
                ?: return LinkResult.Failed(NOT_PAIRED)

            val existing = alarms.linkedAlarms.first().firstOrNull {
                it.link?.ownerAlarmId == ownerAlarmId && it.link?.ownerUid == hh.partnerUid
            }
            // A pending LEFT removal is cancelled under LinkSync's lock; false means the row is already gone.
            if (existing != null && (removals.all()[existing.id] != RemovalReason.LEFT || cancelRemoval(existing.id))) {
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
                val applied = done?.first == CommandStatus.APPLIED
                if (applied) {
                    // APPLIED is terminal. LinkSync clears pendingBoth from the owner's next doc (inside its
                    // pass, so the turn-off notice still sees it). Only after LinkSync has processed a newer doc
                    // (or the TTL) is it cleared here: with a dead listener or no network the stale copy must
                    // not re-arm a "both off".
                    noteApplied(rowId, pending.commandId)
                    var waited = appliedFallback
                    delay(waited)
                    while (!appliedSettled(pending.commandId) && waited < appliedTtl) {
                        delay(appliedFallback)
                        waited += appliedFallback
                    }
                }
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
                if (applied) return@launch
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

    /** [PendingBoth.createdAt] plus the TTL; [fallback] starts the window for a command stored without one. */
    private fun expiryOf(pending: PendingBoth, fallback: Instant): Instant =
        (pending.createdAt ?: fallback).plus(COMMAND_TTL)

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
