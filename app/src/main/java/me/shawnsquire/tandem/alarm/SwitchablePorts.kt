package me.shawnsquire.tandem.alarm

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.DEFAULT_SNOOZE_MINUTES
import me.shawnsquire.tandem.data.SNOOZE_RANGE
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.data.withoutExpiredSkip
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LateRing
import me.shawnsquire.tandem.sync.PreFireCheck
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

// Ports the process-wide alarm objects use for non-alarm data. Before attach() they read the DE
// mirror, so a ring works before the first unlock; after it they forward to the real CE-backed
// implementation, so a ring that started locked keeps its objects across the unlock.

/**
 * Locked, reads come from [mirror] and enable/skip writes are queued in [deferred] (and applied to
 * the mirror so a locked reschedule sees them). [attach] replays the queue into the real
 * repository, then forwards everything.
 */
class SwitchableAlarmRepository(
    private val mirror: AlarmMirror,
    private val deferred: DeferredAlarmWrites,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
) : AlarmRepository {

    private class Attachment(val real: AlarmRepository) {
        val drained = CompletableDeferred<Unit>()
    }

    // Guards the attached check together with a locked write, so every locked write is either
    // queued before the drain reads the queue or forwarded after it.
    private val lock = Any()
    private val attachment = MutableStateFlow<Attachment?>(null)
    // Null while the mirror is unreadable.
    private val mirrored = MutableStateFlow(mirror.readOrNull()?.alarms?.map { it.alarm })

    /** True once the drain has finished and calls go to the real repository. */
    val isAttached: Boolean get() = attachment.value?.drained?.isCompleted == true

    /**
     * Collected after [attach], it waits for the drain like every other call (a reschedule reads it).
     * Locked, it throws while the mirror is unreadable: an empty list would make a reschedule cancel
     * every armed alarm, while a throw leaves them and their records untouched.
     */
    override val alarms: Flow<List<Alarm>> = view({ it.alarms }) { true }

    /**
     * Locked, the mirrored list filtered to link == null. The mirror encodes [Alarm.link], so
     * locked linked rows are excluded here like unlocked.
     */
    override val ownAlarms: Flow<List<Alarm>> = view({ it.ownAlarms }) { it.link == null }

    /** Locked, the mirrored list filtered to link != null. */
    override val linkedAlarms: Flow<List<Alarm>> = view({ it.linkedAlarms }) { it.link != null }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun view(real: (AlarmRepository) -> Flow<List<Alarm>>, lockedFilter: (Alarm) -> Boolean): Flow<List<Alarm>> =
        attachment.flatMapLatest { a ->
            if (a == null) {
                mirrored.map { list ->
                    val now = clock()
                    (list ?: throw mirrorUnreadable()).filter(lockedFilter).map { it.withoutExpiredSkip(now) }
                }
            } else {
                flow {
                    a.drained.await()
                    emitAll(real(a.real))
                }
            }
        }

    /**
     * Locked, it never returns null. Delete needs the user to unlock, so an id missing from the
     * mirror is a read problem, not a local delete: it throws, and the ring session then rings with
     * defaults (I1).
     */
    override suspend fun get(id: String): Alarm? {
        val real = target() ?: return lockedGet(id)
        return real.get(id)
    }

    private fun lockedGet(id: String): Alarm {
        val snapshot = mirror.readOrNull() ?: throw mirrorUnreadable()
        val alarm = snapshot.alarms.firstOrNull { it.alarm.id == id }?.alarm
            ?: throw IllegalStateException("alarm $id is not in the alarm mirror")
        return alarm.withoutExpiredSkip(clock())
    }

    override suspend fun upsert(alarm: Alarm) = (target() ?: throw lockedEdit()).upsert(alarm)

    override suspend fun delete(id: String) = (target() ?: throw lockedEdit()).delete(id)

    override suspend fun setEnabled(id: String, enabled: Boolean) =
        write(DeferredWrite.SetEnabled(id, enabled), { it.copy(enabled = enabled) }) { it.setEnabled(id, enabled) }

    override suspend fun setSkip(id: String, occurrence: Instant?) =
        write(DeferredWrite.SetSkip(id, occurrence), { it.copy(skippedOccurrenceAt = occurrence) }) { it.setSkip(id, occurrence) }

    /**
     * Deferrable like [setSkip]: queued and replayed on attach. The mirror doesn't encode
     * soloSkippedOccurrenceAt, so a locked read doesn't show it until unlock (it matters only for
     * the owner's own ring, which the real repository serves).
     */
    override suspend fun setSoloSkip(id: String, occurrence: Instant?) =
        write(DeferredWrite.SetSoloSkip(id, occurrence), { it.copy(soloSkippedOccurrenceAt = occurrence) }) { it.setSoloSkip(id, occurrence) }

    /**
     * Deferrable like [setSkip]: queued and replayed on attach. The mirror doesn't encode lateRing
     * (before the first unlock a late ring rings, I1), so a locked read doesn't show it.
     */
    override suspend fun setLateRing(id: String, lateRing: LateRing?) =
        write(DeferredWrite.SetLateRing(id, lateRing), { it.copy(lateRing = lateRing) }) { it.setLateRing(id, lateRing) }

    /**
     * Deferrable like [setEnabled]: queued and replayed on attach. The mirror carries the link,
     * so the change below applies to a linked mirrored row while locked.
     */
    override suspend fun setJoinerEnabled(id: String, enabled: Boolean) =
        write(
            DeferredWrite.SetJoinerEnabled(id, enabled),
            { a -> a.link?.let { a.copy(link = it.copy(joinerEnabled = enabled)) } ?: a },
        ) { it.setJoinerEnabled(id, enabled) }

    /** Not deferrable (link sync needs the CE store and the network): throws locked, like [upsert]. */
    override suspend fun upsertLinked(alarm: Alarm) = (target() ?: throw lockedEdit()).upsertLinked(alarm)

    /** Not deferrable (a lambda can't be queued): throws locked, like [upsert]. */
    override suspend fun updateLink(id: String, transform: (AlarmLink) -> AlarmLink): Boolean =
        (target() ?: throw lockedEdit()).updateLink(id, transform)

    /**
     * Non-suspending and idempotent. Calls made from now on wait for the drain. A queued op that
     * fails is retried once, then logged and dropped, and the drain goes on with the rest, so the
     * queue ends empty; each op leaves the queue as soon as it is handled. Kept, a failed op would replay at the next process's attach over writes made
     * since; once unlocked, Room is the single source of truth (MirrorUpdater rewrites the mirror
     * from it, so the dropped op leaves the mirror too). Forwarding starts regardless, even if
     * [scope] is already dead, so no caller waits forever.
     */
    fun attach(real: AlarmRepository) {
        val a = synchronized(lock) {
            if (attachment.value != null) return
            Attachment(real).also { attachment.value = it }
        }
        scope.launch { drain(real) }.invokeOnCompletion { a.drained.complete(Unit) }
    }

    private suspend fun drain(real: AlarmRepository) {
        // Complete: once attachment is set, no locked write can append.
        val writes = deferred.entries()
        for ((seq, w) in writes) {
            try {
                replay(real, w)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("replaying deferred alarm write $w failed; retrying once", e)
                try {
                    replay(real, w)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logError("replaying deferred alarm write $w failed twice; dropped", e)
                }
            }
            // Trimmed as soon as it is applied or dropped (no suspension in between), so a drain
            // cut short leaves only untried ops for the next process, never ones already in Room.
            // By seq, so a trim that fails here is covered by the next one.
            try {
                deferred.removeThrough(seq)
            } catch (e: Throwable) {
                logError("removing replayed alarm writes through $seq failed", e)
            }
        }
    }

    private suspend fun replay(real: AlarmRepository, w: DeferredWrite) = when (w) {
        is DeferredWrite.SetEnabled -> real.setEnabled(w.id, w.enabled)
        is DeferredWrite.SetSkip -> real.setSkip(w.id, w.occurrence)
        is DeferredWrite.SetSoloSkip -> real.setSoloSkip(w.id, w.occurrence)
        is DeferredWrite.SetLateRing -> real.setLateRing(w.id, w.lateRing)
        is DeferredWrite.SetJoinerEnabled -> real.setJoinerEnabled(w.id, w.enabled)
    }

    private suspend fun write(w: DeferredWrite, change: (Alarm) -> Alarm, forward: suspend (AlarmRepository) -> Unit) {
        val attached = synchronized(lock) {
            attachment.value ?: run {
                // An unreadable mirror refuses the write before anything is queued, so a failed
                // write changes nothing: the caller logs it, and a ring is never affected.
                if (mirror.readOrNull() == null) throw mirrorUnreadable()
                // Queue first: if that fails nothing changed; the mirror only reflects queued writes.
                deferred.append(w)
                mirror.update { s ->
                    s.copy(alarms = s.alarms.map { if (it.alarm.id == w.id) it.copy(alarm = change(it.alarm)) else it })
                }
                mirrored.value = mirror.readOrNull()?.alarms?.map { it.alarm }
                return
            }
        }
        attached.drained.await()
        forward(attached.real)
    }

    private suspend fun target(): AlarmRepository? {
        val a = attachment.value ?: return null
        a.drained.await()
        return a.real
    }

    private fun lockedEdit() = IllegalStateException("alarm edits need the user to unlock")

    private fun mirrorUnreadable() = IllegalStateException("alarm mirror unreadable")
}

/**
 * Locked, settings come from the mirror (wallpapers are CE files, so none); [attach] forwards.
 * Attached, a snooze or default-ringtone edit then calls [onChanged], so the mirror is refreshed
 * with it; wallpapers aren't mirrored, so their edits don't.
 */
class SwitchableSettings(
    private val mirror: AlarmMirror,
    private val onChanged: () -> Unit = {},
) : SettingsRepository {
    private val real = MutableStateFlow<SettingsRepository?>(null)

    /** Idempotent: the first repository wins. */
    fun attach(real: SettingsRepository) {
        this.real.compareAndSet(null, real)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val snoozeMinutes: Flow<Int> = real.flatMapLatest {
        it?.snoozeMinutes ?: flow { emit((mirror.read().snoozeMinutes ?: DEFAULT_SNOOZE_MINUTES).coerceIn(SNOOZE_RANGE)) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val defaultWallpaperPath: Flow<String?> = real.flatMapLatest { it?.defaultWallpaperPath ?: flowOf(null) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val defaultRingtoneUri: Flow<String?> = real.flatMapLatest {
        it?.defaultRingtoneUri ?: flow { emit(mirror.read().defaultRingtoneUri) }
    }

    override suspend fun setSnoozeMinutes(m: Int) {
        attached().setSnoozeMinutes(m)
        onChanged()
    }

    override suspend fun setDefaultWallpaperPath(p: String?) = attached().setDefaultWallpaperPath(p)

    override suspend fun setDefaultRingtoneUri(u: String?) {
        attached().setDefaultRingtoneUri(u)
        onChanged()
    }

    private fun attached(): SettingsRepository =
        real.value ?: throw IllegalStateException("settings edits need the user to unlock")
}

/** Locked, the pre-fire pull is skipped (no network, no CE access); [attach] forwards. */
class SwitchablePreFireCheck : PreFireCheck {
    private val provider = AtomicReference<(() -> PreFireCheck)?>(null)

    /** Idempotent: the first provider wins. It is evaluated on each call, not here. */
    fun attach(provider: () -> PreFireCheck) {
        this.provider.compareAndSet(null, provider)
    }

    /** Tests only: the check calls forward to now, or null while locked. */
    @VisibleForTesting
    internal fun target(): PreFireCheck? = provider.get()?.invoke()

    override suspend fun pullAndApply(timeout: Duration, linkedOwnerAlarmId: String?): Boolean {
        val provide = provider.get() ?: return false
        val check = try {
            provide()
        } catch (e: Exception) {
            log("pre-fire check unavailable", e)
            return false
        }
        return check.pullAndApply(timeout, linkedOwnerAlarmId)
    }
}

// runCatching: Log is unavailable in plain JVM tests.
private fun log(message: String, e: Throwable) {
    runCatching { Log.w(TAG, message, e) }
}

private fun logError(message: String, e: Throwable) {
    runCatching { Log.e(TAG, message, e) }
}

private const val TAG = "SwitchablePorts"
