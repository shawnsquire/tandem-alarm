package me.shawnsquire.tandem.alarm

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

    fun start(alarmId: String, occurrence: Instant, isSnooze: Boolean, snoozeRingId: String?) {
        jobs += scope.launch {
            try {
                execute(alarmId, occurrence, isSnooze, snoozeRingId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // I1: an unexpected error is never a reason to stay silent.
                log("ringing session failed", e)
                if (!ringStarted) fallbackRing() else finish()
            }
        }
    }

    /** Best-effort ring when something unexpected failed before sound started. */
    private fun fallbackRing() {
        ringStarted = true
        try { sound.start(emptyList()) } catch (e: Exception) { log("fallback sound failed", e) }
        try { vibration.start() } catch (e: Exception) { log("fallback vibration failed", e) }
        jobs += scope.launch {
            delay(10.minutes)
            finish()
        }
    }

    private suspend fun execute(alarmId: String, occurrence: Instant, isSnooze: Boolean, snoozeRingId: String?) {
        preFire()

        if (isSnooze) {
            val state = try {
                ringStateStore.state.first()
            } catch (e: Exception) {
                log("state read failed; ringing with the snooze ringId", e)
                val alarm = try { alarmRepository.get(alarmId) } catch (e2: Exception) { log("alarm read failed", e2); null }
                if (snoozeRingId == null) fallbackRing() else ring(alarm, snoozeRingId, observe = false)
                return
            }
            if (state == null || state.ringId != snoozeRingId || state.snoozedUntil == null) {
                finish() // dismissed while snoozed
                return
            }
            val alarm = try { alarmRepository.get(alarmId) } catch (e: Exception) { log("alarm read failed", e); null }
            val ringing = state.copy(snoozedUntil = null)
            val written = try { ringStateStore.set(ringing); true } catch (e: Exception) { log("state write failed", e); false }
            ring(alarm, ringing.ringId, observe = written)
            return
        }

        val alarm: Alarm? = try {
            alarmRepository.get(alarmId) ?: run { finish(); return } // deleted locally
        } catch (e: Exception) {
            log("alarm read failed; ringing with defaults", e)
            null
        }
        if (alarm != null) {
            val skipped = alarm.skippedOccurrenceAt == occurrence
            if (!alarm.enabled || skipped) {
                if (skipped) {
                    try { alarmRepository.setSkip(alarm.id, null) } catch (e: Exception) { log("clear skip failed", e) }
                }
                try { alarmScheduler.rescheduleAll() } catch (e: Exception) { log("reschedule failed", e) }
                finish()
                return
            }
        }

        val ringId = try {
            newRingId()
        } catch (e: Exception) {
            log("newRingId failed", e)
            UUID.randomUUID().toString()
        }
        try {
            val old = ringStateStore.state.first()
            if (old != null) controller.dismiss(old.ringId) // newest alarm supersedes
        } catch (e: Exception) {
            log("superseding old ring failed", e)
        }
        val written = try {
            ringStateStore.set(RingState(alarmId, ringId, now(), null)); true
        } catch (e: Exception) {
            log("state write failed", e); false
        }
        ring(alarm, ringId, observe = written)
    }

    /** I1: hard 3 s bound that also holds when the check blocks a thread without cooperating. */
    private suspend fun preFire() {
        try {
            val d = scope.async(ioDispatcher) {
                try {
                    preFireCheck.pullAndApply(3.seconds)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    false
                }
            }
            withTimeoutOrNull(3.seconds) { d.await() } ?: d.cancel()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("pre-fire check failed", e)
        }
    }

    private suspend fun ring(alarm: Alarm?, ringId: String, observe: Boolean) {
        val default = try { settingsRepository.defaultRingtoneUri.first() } catch (e: Exception) { log("settings read failed", e); null }
        ringStarted = true
        try { sound.start(listOfNotNull(alarm?.ringtoneUri, default)) } catch (e: Exception) { log("sound failed", e) }
        try { vibration.start() } catch (e: Exception) { log("vibration failed", e) }
        try { notifier.showRinging(alarm, ringId) } catch (e: Exception) { log("notifier failed", e) }
        try { alarmScheduler.rescheduleAll() } catch (e: Exception) { log("reschedule failed", e) }

        jobs += scope.launch {
            delay(10.minutes)
            try { controller.dismiss(ringId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log("auto-silence dismiss failed", e) }
            finish()
        }
        if (observe) {
            jobs += scope.launch {
                try {
                    ringStateStore.state.first { it == null || it.ringId != ringId || it.snoozedUntil != null }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("ring state observation failed", e)
                    return@launch
                }
                finish()
            }
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        try { sound.stop() } catch (e: Exception) { log("sound stop failed", e) }
        try { vibration.stop() } catch (e: Exception) { log("vibration stop failed", e) }
        jobs.forEach { it.cancel() }
        onFinished()
    }
}
