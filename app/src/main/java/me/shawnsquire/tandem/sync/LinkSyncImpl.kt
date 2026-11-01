package me.shawnsquire.tandem.sync

import android.util.Log
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    private val unpairSettle: Duration = 30.seconds,
) : LinkSync {
    private val lock = Mutex()
    private var started = false
    private var seenHousehold = false

    /** The latest household the watcher saw; [apply] pairs a doc with it. */
    private val currentHousehold = MutableStateFlow<Household?>(null)

    // Only touched under [lock].
    private var lastName = FALLBACK_NAME
    private var lastMedia: MediaArgs? = null

    private data class MediaArgs(val ownerUid: String, val alarms: Map<String, AlarmMedia>, val wallpaper: MediaRef?)
    private data class Trigger(val partner: PartnerState?, val household: Household?)

    override fun start() {
        if (started) return
        started = true
        scope.launch {
            household.resilient("household").collectLatest { h ->
                currentHousehold.value = h
                if (h != null) {
                    seenHousehold = true
                } else if (seenHousehold) {
                    // A null also means "listener error", so wait; a household within the window cancels this.
                    delay(unpairSettle)
                    Log.i(TAG, "household gone for $unpairSettle, removing linked rows")
                    removeAll()
                }
            }
        }
        scope.launch {
            val linkedKeys = alarms.linkedAlarms
                .map { rows -> rows.map { Triple(it.id, it.link?.ownerUid, it.link?.ownerAlarmId) }.toSet() }
                .distinctUntilChanged()
            // linkedKeys re-runs a pass when a row is added (a Join), so its media is requested too.
            combine(partner, household, linkedKeys) { p, h, _ -> Trigger(p, h) }
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

    /** Runs in [scope] so a caller's timeout (the pre-fire path) never tears a half-applied pass. */
    override suspend fun apply(owner: PartnerState) {
        scope.async {
            val hh = currentHousehold.value ?: withTimeoutOrNull(1.seconds) { household.first() }
            if (hh != null) runPass(owner, hh)
        }.await()
    }

    override suspend fun removeAll() {
        scope.async {
            guarded("remove all") {
                lock.withLock {
                    for (row in alarms.linkedAlarms.first()) removals.add(row.id, RemovalReason.UNPAIRED)
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
        scope.async {
            guarded("remove $rowId") {
                lock.withLock {
                    removals.add(rowId, reason)
                    if (flushRemovals()) reschedule()
                }
            }
        }.await()
    }

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
                    }
                }
                if (flushRemovals()) changed = true
                if (changed) reschedule()
            }
        }
    }

    /** Syncs [rows] (all owned by [hh]'s partner) with [owner]; true when any row changed. */
    private suspend fun syncRows(owner: PartnerState, hh: Household, rows: List<Alarm>): Boolean {
        var changed = false
        val pending = removals.all().keys
        val live = rows.filter { it.id !in pending }
        for (row in live) {
            try {
                val doc = owner.alarms.firstOrNull { it.id == row.link?.ownerAlarmId }
                if (doc == null) {
                    removals.add(row.id, RemovalReason.OWNER_DELETED)
                } else if (syncRow(row, doc, owner.displayName.ifBlank { FALLBACK_NAME })) {
                    changed = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "syncing ${row.id} failed", e)
            }
        }
        updateMedia(owner, hh, live.filter { it.id !in removals.all().keys })
        return changed
    }

    private suspend fun syncRow(row: Alarm, doc: PartnerAlarm, name: String): Boolean {
        val link = checkNotNull(row.link)
        val oneShot = doc.days.isEmpty()
        val pb = link.pendingBoth
        val oneShotSkip = oneShot && pb?.type == CommandType.SKIP_NEXT

        // Everything below is decided from the row as it was, before pendingBoth is cleared.
        val turnedOff = row.enabled && !doc.enabled
        val ownPending = pb?.type == CommandType.DISABLE || oneShotSkip
        val notify = turnedOff && link.joinerEnabled && !ownPending && (!oneShot || oneShotIsFuture(row))
        val resetJoiner = oneShot && !row.enabled && doc.enabled
        val clear = pb != null && matches(pb, doc, oneShot)

        val copied = row.copy(
            label = doc.label, hour = doc.hour, minute = doc.minute, days = doc.days, enabled = doc.enabled,
            link = link.copy(ownerSkippedOccurrenceAt = doc.skippedOccurrenceAt),
        )
        var changed = false
        if (copied != row) {
            alarms.upsertLinked(copied)
            changed = true
        }
        if (clear || resetJoiner) {
            alarms.updateLink(row.id) { l ->
                var n = l
                // A newer "both" may have replaced the one evaluated.
                if (clear && l.pendingBoth?.commandId == pb.commandId) n = n.copy(pendingBoth = null)
                if (resetJoiner) n = n.copy(joinerEnabled = true)
                n
            }
            changed = true
        }
        if (notify) {
            try {
                notifier.notifyOwnerTurnedOff(alarms.get(row.id) ?: copied, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "turn-off notification for ${row.id} failed", e)
            }
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

    private fun updateMedia(owner: PartnerState, hh: Household, rows: List<Alarm>) {
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
