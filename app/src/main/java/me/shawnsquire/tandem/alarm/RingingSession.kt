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
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.RingOrigin
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.isArmed
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
    private val isArmed: (Alarm) -> Boolean = ::isArmed,
    private val isSkipped: (Alarm, Instant) -> Boolean = ::isSkippedOccurrence,
    private val onFinished: () -> Unit,
) {
    private val jobs = mutableListOf<Job>()
    private var finished = false
    private var ringStarted = false
    private var autoSilence: Job? = null
    private var vibrationStarted = false
    private var silencing = false
    private var registeredRingId: String? = null
    @Volatile private var ownLedgerWrite = false

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
        if (fire != null) markRang(fire.first, fire.second)
        guarded("fallback reschedule", Unit) { alarmScheduler.rescheduleAll() }
        ensureAutoSilence(null)
    }

    /** This session's own ledger write; a claim that completes late must not mistake it for an early resolution. */
    private fun markRang(alarmId: String, occurrence: Instant) {
        ownLedgerWrite = true
        guardedNow("fired ledger write") { firedLedger.markRang(alarmId, occurrence) }
    }

    /** Idempotent: the fire path vibrates before claiming the slot, and ring() must not start it again. */
    private suspend fun startVibration(what: String) {
        if (vibrationStarted) return
        guarded(what, Unit) { vibration.start(); vibrationStarted = true }
    }

    /**
     * Auto-silence counts as a dismiss; with no known ringId it just ends the session.
     * 1. Sound and vibration stop at once: the dismiss waits on the ring-slot lock, and a stuck
     *    lock must never keep a ring going.
     * 2. The dismiss runs while the session (so the foreground service and its wake lock) is still
     *    alive, bounded like a claim: the controller makes it NonCancellable, so only waiting on it
     *    from outside can time out. It keeps running past the bound.
     * 3. Only then does the session finish.
     */
    private fun ensureAutoSilence(ringId: String?) {
        if (autoSilence != null) return
        autoSilence = scope.launch {
            delay(10.minutes)
            silencing = true
            guardedNow("sound stop") { sound.stop() }
            guardedNow("vibration stop") { vibration.stop() }
            if (ringId != null) {
                observeJob?.cancel() // the dismiss's clear must not end the session before the dismiss is done
                val d = scope.async {
                    try { controller.dismiss(ringId, RingOrigin.LOCAL) } catch (e: Throwable) { log("auto-silence dismiss failed", e) }
                }
                try {
                    withTimeoutOrNull(DISMISS_TIMEOUT) { d.await() } ?: log("auto-silence dismiss timed out", null)
                } catch (e: Throwable) {
                    currentCoroutineContext().ensureActive()
                    log("auto-silence dismiss failed", e)
                }
            }
            finish()
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
        if (finished || observing || silencing) return
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
        // Read before the check so a linked row's owner doc can be fetched inside it; the ring decision
        // still uses the read made after the check. A failed read just means no owner id.
        val linkedOwnerAlarmId = if (isSnooze) null else guarded("alarm read", null as Alarm?) { alarmRepository.get(alarmId) }?.link?.ownerAlarmId
        preFire(linkedOwnerAlarmId)
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
        // Liveness check, ledger cleanup, merge or supersede and the state write happen in one critical section.
        val claim = boundedClaim(
            "snooze claim",
            onLate = { result: SlotClaim ->
                // A late "not live" (dismissed while snoozed) or "merged" does not stop a ring that has started.
                when (result) {
                    SlotClaim.Claimed -> adoptLateClaim(alarm, snoozeRingId)
                    SlotClaim.Merged -> log("late snooze claim: merged into the sounding ring", null)
                    SlotClaim.NotDue -> log("late snooze claim: snooze was dismissed", null)
                }
            },
        ) { ringSlot.claimSnoozeResume(snoozeRingId, alarmId) }
        if (claim is Claim.Done && claim.value == SlotClaim.NotDue) {
            finish() // dismissed while snoozed
            return
        }
        if (claim is Claim.Done && claim.value == SlotClaim.Merged) {
            // The sounding ring's session plays for both. Snooze fires never consult the fired ledger.
            guarded("reschedule", Unit) { alarmScheduler.rescheduleAll() }
            finish()
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
        if (alarm != null && (!isArmed(alarm) || isSkipped(alarm, occurrence))) {
            suppressFire(alarmId, occurrence, alarm)
            return
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
        // A sounding ring takes this alarm into its merged list; a snoozed one is parked and keeps its snooze.
        // The decision is re-checked inside the claim's critical section, so a skip, disable or delete
        // stored since the read above still suppresses; a failed re-check counts as due (I1).
        var suppressedAlarm: Alarm? = null
        val claim = boundedClaim(
            "ring slot claim",
            onLate = { result: SlotClaim ->
                // A late "no longer due" or "merged" does not stop a ring that has started.
                when (result) {
                    SlotClaim.Claimed -> adoptLateClaim(alarm, ringId)
                    SlotClaim.Merged -> log("late ring slot claim: merged into the sounding ring", null)
                    SlotClaim.NotDue -> log("late ring slot claim: no longer due", null)
                }
            },
        ) {
            ringSlot.claimFire(RingState(alarmId, ringId, now(), null, occurrenceAt = occurrence)) {
                val current = alarmRepository.get(alarmId)
                // Resolved in advance (ledger written under this lock): quiet, no skip to clear.
                if (!ownLedgerWrite && firedLedger.lastRang(alarmId) == occurrence) return@claimFire false
                suppressedAlarm = current
                current != null && isArmed(current) && !isSkipped(current, occurrence)
            }
        }
        if (claim is Claim.Done && claim.value == SlotClaim.NotDue) {
            suppressFire(alarmId, occurrence, suppressedAlarm) // finish() stops vibration and unregisters the ring
            return
        }
        if (claim is Claim.Done && claim.value == SlotClaim.Merged) {
            // The sounding ring's session plays for both, and its auto-silence covers this one too.
            // Recorded so a replay stays quiet. Not markRang: the claim is done, so no late re-check can see it.
            guarded("fired ledger write", Unit) { firedLedger.markRang(alarmId, occurrence) }
            guarded("reschedule", Unit) { alarmScheduler.rescheduleAll() }
            finish() // plays nothing; stops only this session's share of the vibration
            return
        }
        ring(alarm, ringId, observe = claim is Claim.Done, fire = alarmId to occurrence)
    }

    /** Quiet end for a deleted, disabled or skipped occurrence, all of them commands already stored locally. */
    private suspend fun suppressFire(alarmId: String, occurrence: Instant, alarm: Alarm?) {
        if (alarm != null && isSkipped(alarm, occurrence)) {
            // Recorded as handled, so a replay of this skipped occurrence cannot ring once the skip is cleared.
            guarded("fired ledger write", Unit) { firedLedger.markRang(alarmId, occurrence) }
            // Only the shared skip is cleared; the solo skip and link fields belong to skip expiry and LinkSync.
            if (alarm.skippedOccurrenceAt == occurrence) guarded("clear skip", Unit) { alarmRepository.setSkip(alarmId, null) }
            // The solo skip stays: skip expiry owns its lifecycle (and turns a solo-skipped one-shot off).
        }
        guarded("reschedule", Unit) { alarmScheduler.rescheduleAll() }
        finish()
    }

    /** I1: hard 3 s bound that also holds when the check blocks a thread without cooperating. */
    private suspend fun preFire(linkedOwnerAlarmId: String?) {
        try {
            val d = scope.async(ioDispatcher) {
                try {
                    preFireCheck.pullAndApply(3.seconds, linkedOwnerAlarmId)
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
        if (fire != null) markRang(fire.first, fire.second)
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
    private var observeJob: Job? = null
    private var ringPosted = false
    private var lateClaimed = false

    /** Ends the session when the stored ring ends, is superseded or is snoozed. */
    private fun observe(ringId: String) {
        if (observing) return
        observing = true
        observeJob = scope.launch {
            var ok = true
            try {
                ringStateStore.state.first { it == null || it.ringId != ringId || it.snoozedUntil != null }
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                log("ring state observation failed", e)
                ok = false
            }
            if (ok) finish()
        }.also { jobs += it }
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
        val DISMISS_TIMEOUT = 5.seconds
    }
}

/**
 * Whether [occurrence] is one of the skips stored on [alarm]: the shared skip, the owner's solo
 * skip, the owner's skip copied onto a linked row, or a pending "both" skip. Lists the same skips
 * `nextOccurrence` honors, but by exact stored instant, never by recomputing the next occurrence:
 * a zone change, DST or a time edit must not suppress a ring no stored command asked for (I1).
 */
internal fun isSkippedOccurrence(alarm: Alarm, occurrence: Instant): Boolean =
    alarm.skippedOccurrenceAt == occurrence ||
        alarm.soloSkippedOccurrenceAt == occurrence ||
        alarm.link?.ownerSkippedOccurrenceAt == occurrence ||
        alarm.link?.pendingBoth?.let { it.type == CommandType.SKIP_NEXT && it.occurrenceAt == occurrence } == true
