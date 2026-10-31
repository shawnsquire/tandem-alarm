package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val ringSlot: RingSlot,
    private val sound: AlarmSound,
    private val vibration: AlarmVibration,
    private val notifier: RingingNotifier,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Instant,
    private val newRingId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    private val firedLedger: FiredLedger = InMemoryFiredLedger(),
    private val liveRings: RingRegistry = SyncRingRegistry(),
    private val onFinished: () -> Unit,
) {
    private val jobs = mutableListOf<Job>()
    private var finished = false
    private var ringStarted = false
    private var autoSilence: Job? = null
    private var registeredRingId: String? = null

    /** The ring this session registered, once it has one. */
    val ringId: String? get() = registeredRingId ?: fallbackNotifiedId
    private var fallbackNotifiedId: String? = null

    /** Registered before any claim writes state, so a cleaner never sees this ring as stale. */
    private fun register(ringId: String) {
        guardedNow("live ring register") { liveRings.register(ringId); registeredRingId = ringId }
    }

    /**
     * Runs a best-effort step; any Throwable is logged and swallowed. A CancellationException is
     * propagated only when the session itself is cancelled: a foreign one (a check's own timeout,
     * a cancelled Task) is an ordinary failure.
     */
    private suspend inline fun <T> guarded(what: String, default: T, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            log("$what failed", e)
            default
        }

    /** Same, for non-suspending cleanup that must run even while cancelled. */
    private inline fun guardedNow(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { log("$what failed", e) }
    }

    fun start(alarmId: String, occurrence: Instant, isSnooze: Boolean, snoozeRingId: String?) {
        jobs += scope.launch {
            try {
                execute(alarmId, occurrence, isSnooze, snoozeRingId)
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive() // only real session cancellation propagates
                // I1: an unexpected error never silences a ring, started or not.
                log("ringing session failed", e)
                if (!ringStarted) fallbackRing(if (isSnooze) null else alarmId to occurrence, snoozeRingId) else ensureAutoSilence(null)
            }
        }
    }

    /** Best-effort ring when something unexpected failed before sound started. */
    private suspend fun fallbackRing(fire: Pair<String, Instant>? = null, snoozeRingId: String? = null) {
        currentCoroutineContext().ensureActive()
        ringStarted = true
        // Vibration first: sound setup must never delay or prevent it.
        guarded("fallback vibration", Unit) { vibration.start() }
        guarded("fallback sound", Unit) { sound.start(emptyList()) }
        // The ring decision is made, so the full-screen intent is fine; no actions without a RingState.
        val notifyId = snoozeRingId ?: "fallback-" + UUID.randomUUID()
        fallbackNotifiedId = notifyId
        guarded("fallback notifier", Unit) { notifier.showFallbackRinging(notifyId) }
        if (fire != null) guarded("fired ledger write", Unit) { firedLedger.markRang(fire.first, fire.second) }
        guarded("fallback reschedule", Unit) { alarmScheduler.rescheduleAll() }
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
        // Same occurrence already rang (a replay racing AlarmManager's own fire): quiet, before the network check.
        if (!isSnooze && guarded("fired ledger read", null as Instant?) { firedLedger.lastRang(alarmId) } == occurrence) {
            finish()
            return
        }
        preFire()
        if (isSnooze) executeSnooze(alarmId, snoozeRingId) else executeFire(alarmId, occurrence)
    }

    private suspend fun executeSnooze(alarmId: String, snoozeRingId: String?) {
        if (snoozeRingId == null) {
            // Cannot be matched to any snooze. As before: quiet if local state is readable, else ring.
            val readOk = guarded("state read", false) { ringStateStore.state.first(); true }
            if (readOk) finish() else fallbackRing()
            return
        }
        register(snoozeRingId)
        // Liveness check, ledger cleanup, supersede and the state write happen in one critical section.
        var claimFailed = false
        val live = try {
            ringSlot.claimSnoozeResume(snoozeRingId, alarmId)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            log("snooze claim failed; ringing with the snooze ringId", e)
            claimFailed = true
            true
        }
        if (!live) {
            finish() // dismissed while snoozed
            return
        }
        val alarm = guarded("alarm read", null as Alarm?) { alarmRepository.get(alarmId) }
        if (claimFailed) guarded("cancel snoozed notification", Unit) { notifier.cancelSnoozed(snoozeRingId) }
        ring(alarm, snoozeRingId, observe = !claimFailed)
    }

    private suspend fun executeFire(alarmId: String, occurrence: Instant) {
        var readFailed = false
        val alarm: Alarm? = try {
            alarmRepository.get(alarmId)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
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
        register(ringId)
        // Newest alarm supersedes a sounding ring; a snoozed one is parked and keeps its snooze.
        val written = guarded("ring slot claim", false) {
            ringSlot.claimFire(RingState(alarmId, ringId, now(), null)); true
        }
        ring(alarm, ringId, observe = written, fire = alarmId to occurrence)
    }

    /** I1: hard 3 s bound that also holds when the check blocks a thread without cooperating. */
    private suspend fun preFire() {
        try {
            val d = scope.async(ioDispatcher) {
                try {
                    preFireCheck.pullAndApply(3.seconds)
                } catch (e: Throwable) {
                    // Includes CancellationException: a check's own timeout or a cancelled Task is
                    // just "no new info". Only our own d.cancel() cancels this, and the value is ignored.
                    false
                }
            }
            withTimeoutOrNull(3.seconds) { d.await() } ?: d.cancel()
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            log("pre-fire check failed", e)
        }
    }

    /** [fire] is set on the fire path: the occurrence is marked as rung only once sound has started. */
    private suspend fun ring(alarm: Alarm?, ringId: String, observe: Boolean, fire: Pair<String, Instant>? = null) {
        val default = guarded("settings read", null as String?) { settingsRepository.defaultRingtoneUri.first() }
        currentCoroutineContext().ensureActive() // never start sound after the session was cancelled
        ringStarted = true
        // Vibration first: sound setup must never delay or prevent it.
        guarded("vibration", Unit) { vibration.start() }
        guarded("sound", Unit) { sound.start(listOfNotNull(alarm?.ringtoneUri, default)) }
        // Marked after the ring has begun, so a process death before this point still gets a boot replay.
        if (fire != null) guarded("fired ledger write", Unit) { firedLedger.markRang(fire.first, fire.second) }
        guarded("notifier", Unit) { notifier.showRinging(alarm, ringId) }
        guarded("reschedule", Unit) { alarmScheduler.rescheduleAll() }

        ensureAutoSilence(ringId)
        if (observe) {
            jobs += scope.launch {
                var ok = true
                try {
                    ringStateStore.state.first { it == null || it.ringId != ringId || it.snoozedUntil != null }
                } catch (e: Throwable) {
                    currentCoroutineContext().ensureActive()
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
        guardedNow("sound stop") { sound.stop() }
        guardedNow("vibration stop") { vibration.stop() }
        jobs.forEach { it.cancel() }
        registeredRingId?.let { id -> guardedNow("live ring unregister") { liveRings.unregister(id) } }
        onFinished()
    }
}
