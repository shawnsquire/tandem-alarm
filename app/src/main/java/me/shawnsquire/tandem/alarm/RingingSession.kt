package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var vibrationStarted = false
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
        startVibration("fallback vibration")
        guarded("fallback sound", Unit) { sound.start(emptyList()) }
        // The ring decision is made, so the full-screen intent is fine; no actions without a RingState.
        val notifyId = snoozeRingId ?: "fallback-" + UUID.randomUUID()
        fallbackNotifiedId = notifyId
        guarded("fallback notifier", Unit) { notifier.showFallbackRinging(notifyId) }
        if (fire != null) guarded("fired ledger write", Unit) { firedLedger.markRang(fire.first, fire.second) }
        guarded("fallback reschedule", Unit) { alarmScheduler.rescheduleAll() }
        ensureAutoSilence(null)
    }

    /** Idempotent: the fire path vibrates before claiming the slot, and ring() must not start it again. */
    private suspend fun startVibration(what: String) {
        if (vibrationStarted) return
        guarded(what, Unit) { vibration.start(); vibrationStarted = true }
    }

    /**
     * Auto-silence counts as a dismiss; with no known ringId it just ends the session. Sound stops
     * first: the dismiss waits on the ring-slot lock, and a stuck lock must never keep a ring going.
     * The dismiss runs NonCancellable, so finish() cancelling the session's jobs cannot abort it.
     */
    private fun ensureAutoSilence(ringId: String?) {
        if (autoSilence != null) return
        autoSilence = scope.launch {
            delay(10.minutes)
            finish() // cancels this job too; the dismiss below still runs to completion
            if (ringId != null) {
                withContext(NonCancellable) {
                    try { controller.dismiss(ringId) } catch (e: Throwable) { log("auto-silence dismiss failed", e) }
                }
            }
        }.also { jobs += it }
    }

    private sealed interface Claim<out T> {
        data class Done<T>(val value: T) : Claim<T>
        data object Failed : Claim<Nothing>
        data object TimedOut : Claim<Nothing>
    }

    /**
     * I1: a ring-slot claim waits on the controller's lock, so it gets the same hard bound as the
     * pre-fire check. On timeout the claim keeps running (the controller makes it NonCancellable);
     * if it completes later while this session is alive, [onLate] gets its result.
     */
    private suspend fun <T> boundedClaim(what: String, onLate: suspend (T) -> Unit, block: suspend () -> T): Claim<T> {
        val d = scope.async {
            try { Result.success(block()) } catch (e: Throwable) { Result.failure(e) }
        }
        val result = try {
            withTimeoutOrNull(CLAIM_TIMEOUT) { d.await() }
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            log("$what failed", e)
            return Claim.Failed
        }
        if (result == null) {
            log("$what timed out; ringing without the ring slot", null)
            // In jobs: once the session finishes, a late result is ignored.
            jobs += scope.launch {
                val late = try { d.await() } catch (e: Throwable) { currentCoroutineContext().ensureActive(); return@launch }
                late.onSuccess { guarded("late $what", Unit) { onLate(it) } }
                late.onFailure { log("late $what failed", it) }
            }
            return Claim.TimedOut
        }
        return result.fold({ Claim.Done(it) }, { log("$what failed", it); Claim.Failed })
    }

    /**
     * A claim that timed out has now written this ring's state. The ring is already sounding with
     * an inert notification; from here it behaves like a normal ring: observe the state (so Stop and
     * Snooze from the ringing screen end it) and show the actions. It never stops the ring itself.
     */
    private suspend fun adoptLateClaim(alarm: Alarm?, ringId: String) {
        if (finished || observing) return
        if (!ringPosted) {
            lateClaimed = true // ring() has not posted its notification yet; it will adopt the claim itself
            return
        }
        observe(ringId)
        guarded("notifier", Unit) { notifier.showRinging(alarm, ringId) }
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
        var alarm: Alarm? = null
        // Liveness check, ledger cleanup, supersede and the state write happen in one critical section.
        val claim = boundedClaim(
            "snooze claim",
            onLate = { live: Boolean ->
                // A late "not live" (dismissed while snoozed) does not stop a ring that has started.
                if (live) adoptLateClaim(alarm, snoozeRingId) else log("late snooze claim: snooze was dismissed", null)
            },
        ) { ringSlot.claimSnoozeResume(snoozeRingId, alarmId) }
        if (claim is Claim.Done && !claim.value) {
            finish() // dismissed while snoozed
            return
        }
        // Failed or timed out: cannot tell, so it counts as live (I1).
        val claimed = claim is Claim.Done
        alarm = guarded("alarm read", null as Alarm?) { alarmRepository.get(alarmId) }
        if (!claimed) guarded("cancel snoozed notification", Unit) { notifier.cancelSnoozed(snoozeRingId) }
        ring(alarm, snoozeRingId, observe = claimed)
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
                if (skipped) {
                    // Recorded as handled, so a replay of this skipped occurrence cannot ring once the skip is cleared.
                    guarded("fired ledger write", Unit) { firedLedger.markRang(alarmId, occurrence) }
                    guarded("clear skip", Unit) { alarmRepository.setSkip(alarm.id, null) }
                }
                guarded("reschedule", Unit) { alarmScheduler.rescheduleAll() }
                finish()
                return
            }
        }

        // Re-checked after the pre-fire check: the original fire may have rung while a replay waited on it.
        if (guarded("fired ledger read", null as Instant?) { firedLedger.lastRang(alarmId) } == occurrence) {
            finish()
            return
        }

        // The ring is decided. Vibrate now: nothing below (lock, disk, audio) may delay it.
        currentCoroutineContext().ensureActive()
        startVibration("vibration")

        val ringId = guarded("newRingId", UUID.randomUUID().toString()) { newRingId() }
        register(ringId)
        // Newest alarm supersedes a sounding ring; a snoozed one is parked and keeps its snooze.
        val claim = boundedClaim("ring slot claim", onLate = { _: Unit -> adoptLateClaim(alarm, ringId) }) {
            ringSlot.claimFire(RingState(alarmId, ringId, now(), null))
        }
        ring(alarm, ringId, observe = claim is Claim.Done, fire = alarmId to occurrence)
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
        startVibration("vibration")
        guarded("sound", Unit) { sound.start(listOfNotNull(alarm?.ringtoneUri, default)) }
        // Marked after the ring has begun, so a process death before this point still gets a boot replay.
        if (fire != null) guarded("fired ledger write", Unit) { firedLedger.markRang(fire.first, fire.second) }
        // Without a stored RingState, Stop and Snooze would be silently ignored: post no actions.
        val claimed = observe || lateClaimed
        if (claimed) {
            guarded("notifier", Unit) { notifier.showRinging(alarm, ringId) }
        } else {
            guarded("notifier", Unit) { notifier.showFallbackRinging(ringId) }
        }
        ringPosted = true
        // Before the reschedule: a slow scheduler must never leave a started ring without its end.
        ensureAutoSilence(ringId)
        if (claimed || lateClaimed) observe(ringId)
        // NonCancellable: the ring may end (and cancel this job) while the reschedule is mid-write.
        guarded("reschedule", Unit) { withContext(NonCancellable) { alarmScheduler.rescheduleAll() } }
    }

    private var observing = false
    private var ringPosted = false
    private var lateClaimed = false

    /** Ends the session when the stored ring ends, is superseded or is snoozed. */
    private fun observe(ringId: String) {
        if (observing) return
        observing = true
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

    private fun finish() {
        if (finished) return
        finished = true
        guardedNow("sound stop") { sound.stop() }
        guardedNow("vibration stop") { vibration.stop() }
        jobs.forEach { it.cancel() }
        registeredRingId?.let { id -> guardedNow("live ring unregister") { liveRings.unregister(id) } }
        onFinished()
    }

    private companion object {
        val CLAIM_TIMEOUT = 2.seconds
    }
}
