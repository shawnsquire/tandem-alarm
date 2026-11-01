package me.shawnsquire.tandem.sync

import android.util.Log
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.alarm.AlarmScheduler
import me.shawnsquire.tandem.alarm.RingingController
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.media.AlarmMedia
import me.shawnsquire.tandem.media.LinkedMediaCache
import me.shawnsquire.tandem.media.MediaRef
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.FIRE_GRACE
import me.shawnsquire.tandem.schedule.PendingBoth
import me.shawnsquire.tandem.schedule.RingContext
import me.shawnsquire.tandem.schedule.RingState

/**
 * Joiner side of linked alarms: keeps each linked row's copied fields in step with the owner's
 * published alarm (I2: only this class writes them), removes rows whose owner alarm is gone or whose
 * household is gone, and hands the owner's media refs to [LinkedMediaCache].
 *
 * Removal is conservative. The partner flow is null before the first publish and on any listener
 * error, so a null partner never removes anything. An unpair is a null *household* that outlasts
 * [unpairSettle] after a household has been seen in this process. An owner alarm counts as gone only
 * when the partner's doc is present and the current household's partner is the row's owner. A row that
 * is ringing or snoozed is removed only after that ends: the request waits in [removals] and is retried
 * on every pass and every [ringState] change.
 */
class LinkSyncImpl(
    private val partner: Flow<PartnerState?>,
    private val household: Flow<Household?>,
    private val alarms: AlarmRepository,
    private val ringing: RingingController,
    /** RingStateStore.state: re-checks pending removals when a ring starts or ends. */
    private val ringState: Flow<RingState?>,
    private val scheduler: AlarmScheduler,
    private val mediaCache: LinkedMediaCache,
    private val notifier: CommandNotifier,
    private val removals: LinkRemovals,
    private val clock: Clock,
    private val scope: CoroutineScope,
    /** Asks the server whether the household is really gone; see [householdOnServer]. */
    private val confirmHousehold: suspend () -> HouseholdCheck,
    private val unpairSettle: Duration = 30.seconds,
    private val confirmRetry: Duration = 5.minutes,
) : LinkSync {
    private val lock = Mutex()
    private var started = false
    private var seenHousehold = false

    /** The latest household the watcher saw; [apply] pairs a doc with it. */
    private val currentHousehold = MutableStateFlow<Household?>(null)

    // Only touched under [lock].
    private var lastName = FALLBACK_NAME
    private var lastMedia: MediaArgs? = null

    /** `updatedAt` of the latest partner doc a pass processed; the baseline [noteApplied] records. */
    private var lastDocSeen: Instant? = null

    /** Both-of-us commands the owner reported APPLIED, to the [lastDocSeen] at that moment. Memory only. */
    private val applied = HashMap<String, Instant?>()

    private data class MediaArgs(val ownerUid: String, val alarms: Map<String, AlarmMedia>, val wallpaper: MediaRef?)
    private data class Trigger(val partner: PartnerState?, val household: Household?)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start() {
        if (started) return
        started = true
        scope.launch {
            household.resilient("household").collectLatest { h ->
                currentHousehold.value = h
                if (h != null) {
                    seenHousehold = true
                } else if (seenHousehold) {
                    // A null also means "listener error", so wait, then ask the server; a household
                    // returning at any point cancels this.
                    delay(unpairSettle)
                    confirmGone()
                }
            }
        }
        scope.launch {
            val linkedKeys = alarms.linkedAlarms
                .map { rows -> rows.map { Triple(it.id, it.link?.ownerUid, it.link?.ownerAlarmId) }.toSet() }
                .distinctUntilChanged()
            // linkedKeys re-runs a pass when a row is added (a Join), so its media is requested too.
            // Each partner state is paired only with the household it was received under, so a stale
            // doc from the previous partner is never judged against a new household. The null first
            // emission lets a household change run its own checks (re-pair removal) before any doc.
            val pairs = household.flatMapLatest { h -> partner.onStart { emit(null) }.map { Trigger(it, h) } }
            combine(pairs, linkedKeys) { t, _ -> t }
                .resilient("partner")
                .collect { runPass(it.partner, it.household) }
        }
        scope.launch {
            ringState.resilient("ring state").collect {
                guarded("pending removals") {
                    lock.withLock { if (flushRemovals()) reschedule() }
                }
            }
        }
    }

    /** Settled null household: act only on what the server says, and keep asking while it can't say. */
    private suspend fun confirmGone() {
        while (true) {
            val check = try {
                confirmHousehold()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "household check failed", e)
                HouseholdCheck.Unknown
            }
            when (check) {
                HouseholdCheck.Unpaired -> {
                    Log.i(TAG, "server confirms no household, removing linked rows")
                    removeAll()
                    return
                }
                is HouseholdCheck.Paired -> {
                    removeRows { it.link?.ownerUid != check.household.partnerUid }
                    return
                }
                HouseholdCheck.Unknown -> delay(confirmRetry)
            }
        }
    }

    /** Runs in [scope] so a caller's timeout (the pre-fire path) never tears a half-applied pass. */
    override suspend fun apply(owner: PartnerState) {
        scope.async {
            val hh = currentHousehold.value ?: withTimeoutOrNull(1.seconds) { household.first() }
            if (hh != null) runPass(owner, hh)
        }.await()
    }

    override suspend fun removeAll() = removeRows { true }

    private suspend fun removeRows(which: (Alarm) -> Boolean) {
        scope.async {
            guarded("remove rows") {
                lock.withLock {
                    for (row in alarms.linkedAlarms.first()) {
                        if (which(row)) removals.add(row.id, RemovalReason.UNPAIRED)
                    }
                    if (flushRemovals()) reschedule()
                }
            }
        }.await()
    }

    /**
     * Removes linked row [rowId] now, or once it stops ringing; [RemovalReason.LEFT] is the joiner's
     * Leave. Durable: the request survives a process death. Other sync classes read
     * [LinkRemovals.pendingIds] to ignore the row meanwhile.
     */
    internal suspend fun requestRemoval(rowId: String, reason: RemovalReason) {
        // Unlike the internal passes, a failure reaches the caller (a failed Leave must show).
        val result = scope.async {
            try {
                lock.withLock {
                    removals.add(rowId, reason)
                    if (flushRemovals()) reschedule()
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "link sync remove $rowId failed", e)
                e
            }
        }.await()
        if (result != null) throw result
    }

    /**
     * Cancels a pending removal of [rowId] under the sync lock (a re-Join of a row that was left while
     * ringing). True when the row still exists, so it will not be removed; false when it is already gone.
     */
    internal suspend fun cancelRemoval(rowId: String): Boolean = scope.async {
        lock.withLock {
            if (alarms.get(rowId)?.link == null) {
                false
            } else {
                removals.remove(rowId)
                true
            }
        }
    }.await()

    private suspend fun runPass(partnerState: PartnerState?, hh: Household?) {
        guarded("pass") {
            lock.withLock {
                var changed = false
                if (hh != null) {
                    val rows = alarms.linkedAlarms.first()
                    for (row in rows) {
                        if (row.link?.ownerUid != hh.partnerUid) removals.add(row.id, RemovalReason.UNPAIRED)
                    }
                    if (partnerState != null) {
                        lastName = partnerState.displayName.ifBlank { FALLBACK_NAME }
                        changed = syncRows(partnerState, hh, rows.filter { it.link?.ownerUid == hh.partnerUid })
                        partnerState.lastSeen?.let { lastDocSeen = it }
                    }
                }
                if (flushRemovals()) changed = true
                clearOrphanedMedia(hh)
                if (changed) reschedule()
            }
        }
    }

    /**
     * The owner reported [commandId] (a both-of-us command on [rowId]) APPLIED. The next owner doc that
     * is newer than the last one processed now counts as reflecting it, whether or not it matches, so
     * the pass clears `pendingBoth` itself and a reversal by the owner is not left pending.
     */
    internal suspend fun noteApplied(rowId: String, commandId: String) {
        scope.async {
            lock.withLock { if (!applied.containsKey(commandId)) applied[commandId] = lastDocSeen }
        }.await()
    }

    /** Syncs [rows] (all owned by [hh]'s partner) with [owner]; true when any row changed. */
    private suspend fun syncRows(owner: PartnerState, hh: Household, rows: List<Alarm>): Boolean {
        var changed = false
        val pending = removals.all().keys
        val live = rows.filter { it.id !in pending }
        val held = rows.mapNotNull { it.link?.pendingBoth?.commandId }.toSet()
        applied.keys.retainAll(held)
        for (row in live) {
            try {
                val doc = owner.alarms.firstOrNull { it.id == row.link?.ownerAlarmId }
                if (doc == null) {
                    removals.add(row.id, RemovalReason.OWNER_DELETED)
                } else if (syncRow(row, doc, owner.displayName.ifBlank { FALLBACK_NAME }, owner.lastSeen)) {
                    changed = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "syncing ${row.id} failed", e)
            }
        }
        updateMedia(owner, hh, live)
        return changed
    }

    private suspend fun syncRow(row: Alarm, doc: PartnerAlarm, name: String, seen: Instant?): Boolean {
        val link = checkNotNull(row.link)
        val oneShot = doc.days.isEmpty()
        val pb = link.pendingBoth
        val oneShotSkip = oneShot && pb?.type == CommandType.SKIP_NEXT

        // Everything below is decided from the row as it was, before pendingBoth is cleared.
        val turnedOff = row.enabled && !doc.enabled
        val ownPending = pb?.type == CommandType.DISABLE || oneShotSkip
        val notify = turnedOff && link.joinerEnabled && !ownPending && (!oneShot || oneShotIsFuture(row))
        val resetJoiner = oneShot && !row.enabled && doc.enabled
        val reflectedByNewDoc = pb != null && applied.containsKey(pb.commandId) &&
            seen != null && applied[pb.commandId].let { it == null || seen > it }
        val clear = pb != null && (matches(pb, doc, oneShot) || reflectedByNewDoc)

        val copied = row.copy(
            label = doc.label, hour = doc.hour, minute = doc.minute, days = doc.days, enabled = doc.enabled,
            link = link.copy(ownerSkippedOccurrenceAt = doc.skippedOccurrenceAt),
        )
        var changed = false
        // Ordered so a crash anywhere re-derives the same decisions on the next pass (the copied
        // `enabled` is the marker): O9 reset first, then the notice (a crash re-posts rather than loses
        // it), then the copy, then the clear (still derivable from the doc, and a pending both-off
        // must outlive the copy so a retry stays silent).
        if (resetJoiner) {
            alarms.updateLink(row.id) { it.copy(joinerEnabled = true) }
            changed = true
        }
        if (notify) {
            try {
                notifier.notifyOwnerTurnedOff(copied, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "turn-off notification for ${row.id} failed", e)
            }
        }
        if (copied != row) {
            alarms.upsertLinked(copied)
            changed = true
        }
        if (clear) {
            // A newer "both" may have replaced the one evaluated.
            alarms.updateLink(row.id) { l ->
                if (l.pendingBoth?.commandId == pb.commandId) l.copy(pendingBoth = null) else l
            }
            applied.remove(pb.commandId)
            changed = true
        }
        return changed
    }

    /** Whether the owner's doc now reflects the "both" command [pb] (decision: matched means cleared). */
    private fun matches(pb: PendingBoth, doc: PartnerAlarm, oneShot: Boolean): Boolean = when (pb.type) {
        CommandType.SKIP_NEXT ->
            (pb.occurrenceAt != null && doc.skippedOccurrenceAt == pb.occurrenceAt) || (oneShot && !doc.enabled)
        CommandType.UNSKIP -> doc.skippedOccurrenceAt == null || doc.skippedOccurrenceAt != pb.occurrenceAt
        CommandType.DISABLE -> !doc.enabled
        CommandType.ENABLE -> doc.enabled
        else -> false
    }

    /**
     * Whether a one-shot's occurrence has not happened yet, so its turning off is news. X is the most
     * recent instant at the row's time; "next time after now" would call a one-shot that just rang
     * tomorrow's. It counts as happened when the row is live, rang at or after X, or X is within the
     * fire grace.
     */
    private suspend fun oneShotIsFuture(row: Alarm): Boolean {
        val ctx = ringing.ringContext(row.id)
        if (isLive(row.id, ctx)) return false
        val now = clock.instant()
        val x = lastScheduled(row, now)
        if (ctx.lastRang?.let { it >= x } == true) return false
        return now >= x.plus(FIRE_GRACE.toJavaDuration())
    }

    private fun lastScheduled(row: Alarm, now: Instant): Instant {
        val zone = clock.zone
        val time = LocalTime.of(row.hour, row.minute)
        val today = now.atZone(zone).toLocalDate()
        val candidate = ZonedDateTime.of(today, time, zone).toInstant()
        return if (candidate <= now) candidate else ZonedDateTime.of(today.minusDays(1), time, zone).toInstant()
    }

    private fun isLive(rowId: String, ctx: RingContext): Boolean =
        ctx.ring?.let { it.alarmId == rowId || it.merged.any { m -> m.alarmId == rowId } } == true ||
            ctx.merged.any { it.alarmId == rowId } ||
            ctx.snoozes.any { it.alarmId == rowId }

    /** Removes every pending row that is no longer ringing; true when any row was deleted. Call under [lock]. */
    private suspend fun flushRemovals(): Boolean {
        var changed = false
        for ((id, reason) in removals.all()) {
            try {
                val row = alarms.get(id)
                if (row?.link == null) {
                    removals.remove(id)
                    continue
                }
                if (isLive(id, ringing.ringContext(id))) continue
                alarms.delete(id)
                changed = true
                if (reason == RemovalReason.OWNER_DELETED) {
                    try {
                        notifier.notifyOwnerDeleted(row, lastName)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "owner-deleted notification for $id failed", e)
                    }
                }
                removals.remove(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "removing $id failed", e)
            }
        }
        return changed
    }

    /**
     * Once no linked row is left for the partner whose refs were last passed, tells the cache to drop
     * them so [LinkedMediaCache.inUse] stops holding their hashes. Empty refs only; never another
     * partner's.
     */
    private suspend fun clearOrphanedMedia(hh: Household?) {
        val last = lastMedia ?: run {
            // A fresh process: refs persisted by an earlier one may outlive their last row.
            val uid = hh?.partnerUid ?: currentHousehold.value?.partnerUid ?: return
            if (mediaCache.inUse().isEmpty() || alarms.linkedAlarms.first().isNotEmpty()) return
            try {
                mediaCache.update(uid, emptyMap(), null)
                lastMedia = MediaArgs(uid, emptyMap(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "media cache clear failed", e)
            }
            return
        }
        if (last.alarms.isEmpty() && last.wallpaper == null) return
        if (alarms.linkedAlarms.first().any { it.link?.ownerUid == last.ownerUid }) return
        try {
            mediaCache.update(last.ownerUid, emptyMap(), null)
            lastMedia = MediaArgs(last.ownerUid, emptyMap(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "media cache clear failed", e)
        }
    }

    private fun updateMedia(owner: PartnerState, hh: Household, rows: List<Alarm>) {
        if (rows.isEmpty()) return
        val byOwnerId = rows.mapNotNull { it.link?.ownerAlarmId }.toSet()
        val media = owner.alarms.filter { it.id in byOwnerId }
            .mapNotNull { a -> a.media?.let { a.id to it } }.toMap()
        val args = MediaArgs(hh.partnerUid, media, owner.defaultWallpaper)
        if (args == lastMedia) return
        try {
            mediaCache.update(args.ownerUid, args.alarms, args.wallpaper)
            lastMedia = args
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "media cache update failed", e)
        }
    }

    private suspend fun reschedule() {
        try {
            scheduler.rescheduleAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "reschedule after link sync failed", e)
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "link sync $what failed", e)
        }
    }

    private fun <T> Flow<T>.resilient(what: String): Flow<T> = retryWhen { cause, _ ->
        if (cause is CancellationException) {
            false
        } else {
            Log.w(TAG, "link sync $what input failed, retrying", cause)
            delay(1.seconds)
            true
        }
    }

    private companion object {
        const val TAG = "LinkSync"
        const val FALLBACK_NAME = "Your partner"
    }
}
