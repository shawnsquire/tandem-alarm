package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.sync.PreFireCheck
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One ring flow per service start. One-shot: create a new session for each start.
 *
 * I1: firing never waits on the network beyond a hard 3 s pre-fire timeout. The check result
 * is informational; the ring decision is made only from local state read after the check.
 */
class RingingSession(
    private val alarmRepository: AlarmRepository,
    private val ringStateStore: RingStateStore,
    private val settingsRepository: SettingsRepository,
    private val preFireCheck: PreFireCheck,
    private val alarmScheduler: AlarmScheduler,
    private val controller: RingingController,
    private val sound: AlarmSound,
    private val vibration: AlarmVibration,
    private val notifier: RingingNotifier,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Instant,
    private val newRingId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    private val onFinished: () -> Unit,
) {
    private val jobs = mutableListOf<Job>()
    private var finished = false
    private var ringStarted = false
    private var autoSilence: Job? = null

    /** Runs a best-effort step; any Throwable (Errors included) is logged, only cancellation propagates. */
    private inline fun <T> guarded(what: String, default: T, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("$what failed", e)
            default
        }

    fun start(alarmId: String, occurrence: Instant, isSnooze: Boolean, snoozeRingId: String?) {
        jobs += scope.launch {
            try {
                execute(alarmId, occurrence, isSnooze, snoozeRingId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // I1: an unexpected error never silences a ring, started or not.
                log("ringing session failed", e)
                if (!ringStarted) fallbackRing() else ensureAutoSilence(null)
            }
        }
    }

    /** Best-effort ring when something unexpected failed before sound started. */
    private fun fallbackRing() {
        ringStarted = true
        guarded("fallback sound", Unit) { sound.start(emptyList()) }
        guarded("fallback vibration", Unit) { vibration.start() }
        ensureAutoSilence(null)
    }

    /** Auto-silence counts as a dismiss; with no known ringId it just ends the session. */
    private fun ensureAutoSilence(ringId: String?) {
        if (autoSilence != null) return
        autoSilence = scope.launch {
            delay(10.minutes)
            if (ringId != null) guarded("auto-silence dismiss", Unit) { controller.dismiss(ringId) }
            finish()
        }.also { jobs += it }
    }

    private suspend fun execute(alarmId: String, occurrence: Instant, isSnooze: Boolean, snoozeRingId: String?) {
        preFire()
        if (isSnooze) executeSnooze(alarmId, snoozeRingId) else executeFire(alarmId, occurrence)
    }

    private suspend fun executeSnooze(alarmId: String, snoozeRingId: String?) {
        var readFailed = false
        val state = try {
            ringStateStore.state.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("state read failed; ringing with the snooze ringId", e)
            readFailed = true
            null
        }
        if (readFailed) {
            val alarm = guarded("alarm read", null as Alarm?) { alarmRepository.get(alarmId) }
            if (snoozeRingId == null) fallbackRing() else ring(alarm, snoozeRingId, observe = false)
            return
        }
        if (state == null || state.ringId != snoozeRingId || state.snoozedUntil == null) {
            finish() // dismissed while snoozed
            return
        }
        val alarm = guarded("alarm read", null as Alarm?) { alarmRepository.get(alarmId) }
        val ringing = state.copy(snoozedUntil = null)
        val written = guarded("state write", false) { ringStateStore.set(ringing); true }
        ring(alarm, ringing.ringId, observe = written)
    }

    private suspend fun executeFire(alarmId: String, occurrence: Instant) {
        var readFailed = false
        val alarm: Alarm? = try {
            alarmRepository.get(alarmId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("alarm read failed; ringing with defaults", e)
            readFailed = true
            null
        }
        if (!readFailed && alarm == null) { // deleted locally
            finish()
            return
        }
        if (alarm != null) {
            val skipped = alarm.skippedOccurrenceAt == occurrence
            if (!alarm.enabled || skipped) {
                if (skipped) guarded("clear skip", Unit) { alarmRepository.setSkip(alarm.id, null) }
                guarded("reschedule", Unit) { alarmScheduler.rescheduleAll() }
                finish()
                return
            }
        }

        val ringId = guarded("newRingId", UUID.randomUUID().toString()) { newRingId() }
        guarded("superseding old ring", Unit) {
            val old = ringStateStore.state.first()
            if (old != null) controller.dismiss(old.ringId) // newest alarm supersedes
        }
        val written = guarded("state write", false) {
            ringStateStore.set(RingState(alarmId, ringId, now(), null)); true
        }
        ring(alarm, ringId, observe = written)
    }

    /** I1: hard 3 s bound that also holds when the check blocks a thread without cooperating. */
    private suspend fun preFire() {
        try {
            val d = scope.async(ioDispatcher) {
                try {
                    preFireCheck.pullAndApply(3.seconds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    false
                }
            }
            withTimeoutOrNull(3.seconds) { d.await() } ?: d.cancel()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("pre-fire check failed", e)
        }
    }

    private suspend fun ring(alarm: Alarm?, ringId: String, observe: Boolean) {
        val default = guarded("settings read", null as String?) { settingsRepository.defaultRingtoneUri.first() }
        ringStarted = true
        guarded("sound", Unit) { sound.start(listOfNotNull(alarm?.ringtoneUri, default)) }
        guarded("vibration", Unit) { vibration.start() }
        guarded("notifier", Unit) { notifier.showRinging(alarm, ringId) }
        guarded("reschedule", Unit) { alarmScheduler.rescheduleAll() }

        ensureAutoSilence(ringId)
        if (observe) {
            jobs += scope.launch {
                var ok = true
                try {
                    ringStateStore.state.first { it == null || it.ringId != ringId || it.snoozedUntil != null }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log("ring state observation failed", e)
                    ok = false
                }
                if (ok) finish()
            }
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        guarded("sound stop", Unit) { sound.stop() }
        guarded("vibration stop", Unit) { vibration.stop() }
        jobs.forEach { it.cancel() }
        onFinished()
    }
}
