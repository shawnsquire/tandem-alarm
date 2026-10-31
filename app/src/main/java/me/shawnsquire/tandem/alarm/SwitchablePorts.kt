package me.shawnsquire.tandem.alarm

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.FIRE_GRACE
import me.shawnsquire.tandem.sync.PreFireCheck
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.toJavaDuration

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
    @Volatile private var attachment: Attachment? = null
    private val mirrored = MutableStateFlow(mirror.read().alarms.map { it.alarm })
    private val forwarding = MutableStateFlow<AlarmRepository?>(null)

    /** True once the drain has finished and calls go to the real repository. */
    val isAttached: Boolean get() = forwarding.value != null

    @OptIn(ExperimentalCoroutinesApi::class)
    override val alarms: Flow<List<Alarm>> = forwarding.flatMapLatest { real ->
        real?.alarms ?: mirrored.map { list ->
            val now = clock()
            list.map { it.withoutExpiredSkip(now) }
        }
    }

    override suspend fun get(id: String): Alarm? {
        val real = target() ?: return mirror.read().alarms.firstOrNull { it.alarm.id == id }?.alarm?.withoutExpiredSkip(clock())
        return real.get(id)
    }

    override suspend fun upsert(alarm: Alarm) = (target() ?: throw lockedEdit()).upsert(alarm)

    override suspend fun delete(id: String) = (target() ?: throw lockedEdit()).delete(id)

    override suspend fun setEnabled(id: String, enabled: Boolean) =
        write(DeferredWrite.SetEnabled(id, enabled), { it.copy(enabled = enabled) }) { it.setEnabled(id, enabled) }

    override suspend fun setSkip(id: String, occurrence: Instant?) =
        write(DeferredWrite.SetSkip(id, occurrence), { it.copy(skippedOccurrenceAt = occurrence) }) { it.setSkip(id, occurrence) }

    /**
     * Non-suspending and idempotent. Calls made from now on wait for the drain; a failing op stops
     * it and stays queued (with the rest) for the next process's attach. Forwarding starts
     * regardless, even if [scope] is already dead, so no caller waits forever.
     */
    fun attach(real: AlarmRepository) {
        val a = synchronized(lock) {
            if (attachment != null) return
            Attachment(real).also { attachment = it }
        }
        scope.launch { drain(real) }.invokeOnCompletion {
            forwarding.value = real
            a.drained.complete(Unit)
        }
    }

    private suspend fun drain(real: AlarmRepository) {
        var applied = 0
        try {
            for (w in deferred.all()) {
                when (w) {
                    is DeferredWrite.SetEnabled -> real.setEnabled(w.id, w.enabled)
                    is DeferredWrite.SetSkip -> real.setSkip(w.id, w.occurrence)
                }
                applied++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("replaying deferred alarm write failed; the rest stay queued", e)
        } finally {
            if (applied > 0) {
                try {
                    deferred.removeFirst(applied)
                } catch (e: Throwable) {
                    log("dropping $applied replayed alarm writes failed", e)
                }
            }
        }
    }

    private suspend fun write(w: DeferredWrite, change: (Alarm) -> Alarm, forward: suspend (AlarmRepository) -> Unit) {
        val attached = synchronized(lock) {
            attachment ?: run {
                // Queue first: if that fails nothing changed; the mirror only reflects queued writes.
                deferred.append(w)
                mirror.update { s ->
                    s.copy(alarms = s.alarms.map { if (it.alarm.id == w.id) it.copy(alarm = change(it.alarm)) else it })
                }
                mirrored.value = mirror.read().alarms.map { it.alarm }
                return
            }
        }
        attached.drained.await()
        forward(attached.real)
    }

    private suspend fun target(): AlarmRepository? {
        val a = attachment ?: return null
        a.drained.await()
        return a.real
    }

    private fun lockedEdit() = IllegalStateException("alarm edits need the user to unlock")

    /** Same expiry as AlarmRepositoryImpl: a skip at or before `now - FIRE_GRACE` reads as null. */
    private fun Alarm.withoutExpiredSkip(now: Instant): Alarm =
        if (skippedOccurrenceAt != null && !skippedOccurrenceAt.isAfter(now.minus(FIRE_GRACE.toJavaDuration()))) copy(skippedOccurrenceAt = null) else this
}

/** Locked, settings come from the mirror (wallpapers are CE files, so none); [attach] forwards. */
class SwitchableSettings(private val mirror: AlarmMirror) : SettingsRepository {
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

    override suspend fun setSnoozeMinutes(m: Int) = attached().setSnoozeMinutes(m)

    override suspend fun setDefaultWallpaperPath(p: String?) = attached().setDefaultWallpaperPath(p)

    override suspend fun setDefaultRingtoneUri(u: String?) = attached().setDefaultRingtoneUri(u)

    private fun attached(): SettingsRepository =
        real.value ?: throw IllegalStateException("settings edits need the user to unlock")

    private companion object {
        // Same as SettingsRepositoryImpl.
        const val DEFAULT_SNOOZE_MINUTES = 10
        val SNOOZE_RANGE = 1..60
    }
}

/** Locked, the pre-fire pull is skipped (no network, no CE access); [attach] forwards. */
class SwitchablePreFireCheck : PreFireCheck {
    private val provider = AtomicReference<(() -> PreFireCheck)?>(null)

    /** Idempotent: the first provider wins. It is evaluated on each call, not here. */
    fun attach(provider: () -> PreFireCheck) {
        this.provider.compareAndSet(null, provider)
    }

    override suspend fun pullAndApply(timeout: Duration): Boolean {
        val provide = provider.get() ?: return false
        val check = try {
            provide()
        } catch (e: Exception) {
            log("pre-fire check unavailable", e)
            return false
        }
        return check.pullAndApply(timeout)
    }
}

// runCatching: Log is unavailable in plain JVM tests.
private fun log(message: String, e: Throwable) {
    runCatching { Log.w(TAG, message, e) }
}

private const val TAG = "SwitchablePorts"
