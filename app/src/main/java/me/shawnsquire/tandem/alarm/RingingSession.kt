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
import me.shawnsquire.tandem.media.DefaultLinkedMediaCache
import me.shawnsquire.tandem.media.LinkedMediaCache
import me.shawnsquire.tandem.media.RingMedia
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingOrigin
import me.shawnsquire.tandem.schedule.RingOutputs
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.effectiveOutputs
import me.shawnsquire.tandem.schedule.earlierOccurrenceThatDay
import me.shawnsquire.tandem.schedule.fallsOnSchedule
import me.shawnsquire.tandem.schedule.isArmed
import me.shawnsquire.tandem.schedule.ringOutputs
import me.shawnsquire.tandem.schedule.skippedOccurrences
import me.shawnsquire.tandem.sync.PreFireCheck
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One ring flow per service start. One-shot: create a new session for each start.
 *
 * I1: firing never waits on the network beyond a hard 3 s pre-fire timeout. The check result
 * is informational; the ring decision is made only from local state read after the check.
 *
 * Not thread-safe: [scope] must be single-threaded and [start] called on its thread, as
 * RingingService does with Dispatchers.Main.immediate. Only [ioDispatcher] work runs elsewhere,
 * and it touches no session state.
 */
class RingingSession(
    private val alarmRepository: AlarmRepository,
    private val ringStateStore: RingStateStore,
    private val settingsRepository: SettingsRepository,
    private val preFireCheck: PreFireCheck,
    private val alarmScheduler: AlarmScheduler,
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
    private val linkedMediaCache: LinkedMediaCache = DefaultLinkedMediaCache,
    private val liveRings: RingRegistry = SyncRingRegistry(),
    private val isArmed: (Alarm) -> Boolean = ::isArmed,
    private val isSkipped: (Alarm, Instant) -> Boolean = ::isSkippedOccurrence,
    /** The system alarm sound's Uri as the media cache places it; see [startRingOutputs]. */
    private val systemAlarmUri: () -> String? = ::systemDefaultAlarmUri,
    private val onFinished: () -> Unit,
) {
    private val jobs = mutableListOf<Job>()
    private var finished = false
    private var ringStarted = false
    private var autoSilence: Job? = null
    /** The pattern this session's vibration is running with; null while it isn't (stopped, failed, no vibrator). */
    private var vibratingWith: VibrationPattern? = null
    private var soundStarted = false
    /** Counts sound starts, so an exhaustion reported for one the session has since stopped is ignored. */
    private var soundRun = 0
    /**
     * The sound failed (its start threw, or the adapter exhausted the chain), so [soundStarted] doesn't
     * mean anything plays: no vibration may then stop. Kept until [silence] stops both outputs; the
     * outputs start afresh after it. Never cleared by a later start (nothing reports that it plays) or
     * by a vibration start (that vibration may still turn off with a member).
     */
    private var soundFailed = false
    private var silencing = false

    /**
     * Every member of this ring the session last saw: its own once known, then each observation of
     * the stored ring. What auto-silence compares against; null until the session knows any.
     */
    private var seenMembers: Set<RingMember>? = null

    /** The surface this ring sounds on (Ring or Notify) and what restarts it after an un-silence. */
    private var fullSurface = false
    private var surfaceAlarm: Alarm? = null
    /** What the surface plays and shows (sound candidates, notification label); set before its first output. */
    private var surfaceMedia: RingMedia? = null

    /**
     * What the full surface drives: the ring's [ringOutputs],
     * first the own alarm's [effectiveOutputs] (unknown rings in full, I1), then each member change's.
     * Kept while on the Notify surface or silent, for the full surface or an un-silence to start.
     */
    private var outputs: RingOutputs = RingOutputs.UNKNOWN
    /**
     * The member whose sound plays (alarm id, and its row, null when unreadable) once a member change
     * named one; null means the surface's own media. A vibrate-only ring joined by a member with sound
     * starts that member's.
     */
    private var soundFrom: Pair<String, Alarm?>? = null
    /** The alarm ids (primary first) [outputs] were combined from; see [reconcileOutputs]. */
    private var outputIds: List<String>? = null
    private var outputsJob: Job? = null

    /** The alarm ids (primary first) the posted label names; null until known. See [relabelIfChanged]. */
    private var labelledIds: List<String>? = null
    /**
     * Every member's label once a merge or promotion changed who rings; posted in place of
     * [surfaceMedia]'s. Empty when the members have none: relabelled to nothing is not "never relabelled".
     */
    private var relabelled: List<String>? = null
    private var relabelJob: Job? = null

    /** Whether the posted notification has Stop and Snooze (false while only the inert fallback is up). */
    private var actionsPosted = false
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

    /** [armedZone] is the zone the scheduler computed [occurrence] in, if the fire carried one; see [movedOff]. */
    fun start(alarmId: String, occurrence: Instant, isSnooze: Boolean, snoozeRingId: String?, armedZone: ZoneId? = null) {
        jobs += scope.launch {
            try {
                execute(alarmId, occurrence, isSnooze, snoozeRingId, armedZone)
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
        // Vibration first: sound setup must never delay or prevent it. The alarm is unknown here, so
        // Pulse and sound whatever its settings (I1); a vibration already running keeps its pattern.
        if (vibratingWith == null) startVibration("fallback vibration", VibrationPattern.PULSE)
        guarded("fallback sound", Unit) { sound.start(emptyList(), onExhausted = onExhausted()); soundStarted = true }
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

    /**
     * Whether the vibration now runs with [pattern]. Idempotent per pattern: the fire path vibrates
     * before claiming the slot, and ring() must not start it again; another pattern switches it. False
     * (no vibrator, or the start threw) makes the caller ring with sound (I1). A start that threw
     * tries Pulse, the one waveform every ring used before patterns, but still answers false.
     */
    private suspend fun startVibration(what: String, pattern: VibrationPattern): Boolean {
        if (vibratingWith == pattern) return true
        val started = try {
            vibration.start(pattern)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            log("$what failed; ringing with sound", e)
            vibratingWith = null
            if (pattern != VibrationPattern.PULSE && guarded("$what (Pulse)", false) { vibration.start(VibrationPattern.PULSE) }) {
                vibratingWith = VibrationPattern.PULSE
            }
            return false
        }
        vibratingWith = pattern.takeIf { started }
        if (!started) log("$what: no vibrator; ringing with sound", null)
        return started
    }

    /** Arms auto-silence once per ring; see [silence]. */
    private fun ensureAutoSilence(ringId: String?) {
        if (autoSilence != null) return
        armAutoSilence(ringId)
    }

    /**
     * (Re)starts the [AUTO_SILENCE] countdown to [silence]: at the ring's start, whenever a member
     * joins (each member gets its full span, so the ring ends that long after the last join) and
     * after an un-silence. Not while silencing: the compare-and-dismiss in flight sees a new member.
     */
    private fun armAutoSilence(ringId: String?) {
        if (finished || silencing) return
        autoSilence?.let { it.cancel(); jobs -= it }
        autoSilence = scope.launch {
            delay(AUTO_SILENCE)
            silence(ringId)
        }.also { jobs += it }
    }

    /**
     * Auto-silence counts as a LOCAL dismiss; with no known ringId it just ends the session.
     * 1. Sound and vibration stop at once: the dismiss waits on the ring-slot lock, and a stuck
     *    lock must never keep a ring going.
     * 2. The dismiss runs while the session (so the foreground service and its wake lock) is still
     *    alive: the slot makes it NonCancellable, so only waiting on it from outside can time out.
     *    Past [DISMISS_TIMEOUT] (a stuck lock) the session waits on, silent, up to [DISMISS_GIVE_UP]:
     *    a member the dismiss merges once the lock frees must still be heard, and its fired-ledger
     *    entry would block any replay. It keeps running past that bound too.
     * 3. It is compare-and-dismiss ([RingSlot.autoSilence]): a member that joined after this
     *    session last looked keeps the ring going ([unsilence]) instead of being resolved unheard.
     * 4. Otherwise (dismissed, failed or given up on) the session finishes.
     */
    private suspend fun silence(ringId: String?) {
        silencing = true
        val wasFull = fullSurface
        guardedNow("sound stop") { sound.stop() }
        guardedNow("vibration stop") { vibration.stop() }
        vibratingWith = null
        soundStarted = false
        soundFailed = false
        if (ringId != null) {
            // The dismiss's clear must not end the session before the dismiss is done.
            observeJob?.cancel()
            observing = false
            val expected = seenMembers.orEmpty().toList()
            val d = scope.async {
                try { ringSlot.autoSilence(ringId, expected) } catch (e: Throwable) { log("auto-silence dismiss failed", e); true }
            }
            val ended = try {
                withTimeoutOrNull(DISMISS_TIMEOUT) { d.await() }
                    ?: run {
                        log("auto-silence dismiss is waiting on the ring slot; staying up silently", null)
                        withTimeoutOrNull(DISMISS_GIVE_UP - DISMISS_TIMEOUT) { d.await() }
                    }
                    ?: true.also { log("auto-silence dismiss timed out", null) }
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                log("auto-silence dismiss failed", e)
                true
            }
            if (!ended) {
                unsilence(ringId, wasFull)
                return
            }
        }
        finish()
    }

    /**
     * A member joined while auto-silence waited: the ring sounds on for it, on its surface (Ring:
     * sound and vibration; Notify: the notification posted so it alerts again, or in full if it
     * can't; a Notify ring upgraded while silent comes back in full), observed again, with a fresh
     * [AUTO_SILENCE] from now.
     */
    private suspend fun unsilence(ringId: String, wasFull: Boolean) {
        if (finished) return
        silencing = false
        currentCoroutineContext().ensureActive()
        if (fullSurface) {
            startRingOutputs() // the same outputs as before: a vibrate-only ring stays silent
            // The refusal proves the slot holds this ring, so a claim that landed while silent
            // (never adopted) gets its actions now.
            if (!wasFull || !actionsPosted) showRinging(surfaceAlarm, ringId)
        } else {
            val shown = guarded("notifier", false) { notifier.showNotifyRinging(surfaceAlarm, ringId, surfaceLabels(surfaceAlarm), alertAgain = true) }
            if (!shown) ringInFull(surfaceAlarm, ringId) // I1, as at the first post
        }
        // Before observing: on an immediate dispatcher the first look runs inside observe(), and a
        // re-arm there must replace this timer, not one this line would then orphan.
        autoSilence = null // this job: arming anew must not cancel it
        armAutoSilence(ringId)
        // The first look only learns the members: the post above already alerted for the one that
        // joined while silent, and a Notify join seen there would alert for it a second time.
        seenMembers = null
        observe(ringId)
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
        showRinging(alarm, ringId)
    }

    /**
     * A claim that timed out merged [member] into another sounding ring after all, while this
     * session rings it as a fallback with no actions. That ring's Stop or Snooze resolves [member]
     * too, so this ring ends with it: once [member] is no longer one of the slot ring's members, or
     * the slot is empty or snoozed (a snooze splits every member out into its own). Never stops the
     * ring on its own; null [member] (unknown) just logs.
     */
    private fun followMergedRing(member: RingMember?) {
        if (member == null) {
            log("late claim merged an unknown member; ringing until auto-silence", null)
            return
        }
        if (finished) return
        jobs += scope.launch {
            try {
                ringStateStore.state.first { it == null || it.snoozedUntil != null || member !in it.members() }
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                log("merged ring observation failed", e)
                return@launch
            }
            finish()
        }
    }

    private suspend fun execute(alarmId: String, occurrence: Instant, isSnooze: Boolean, snoozeRingId: String?, armedZone: ZoneId?) {
        // Same occurrence already rang (a replay racing AlarmManager's own fire): quiet, before the network check.
        if (!isSnooze && guarded("fired ledger read", null as Instant?) { firedLedger.lastRang(alarmId) } == occurrence) {
            finish()
            return
        }
        // Read before the check so a linked row's owner doc can be fetched inside it; the ring decision
        // still uses the read made after the check. A failed read just means no owner id.
        val linkedOwnerAlarmId = if (isSnooze) null else guarded("alarm read", null as Alarm?) { alarmRepository.get(alarmId) }?.link?.ownerAlarmId
        preFire(linkedOwnerAlarmId)
        if (isSnooze) executeSnooze(alarmId, snoozeRingId) else executeFire(alarmId, occurrence, armedZone)
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
        var mergedAs: RingMember? = null
        // Liveness check, ledger cleanup, merge or supersede and the state write happen in one critical section.
        val claim = boundedClaim(
            "snooze claim",
            onLate = { result: SlotClaim ->
                // A late "not live" (dismissed while snoozed) does not stop a ring that has started.
                when (result) {
                    SlotClaim.Claimed -> adoptLateClaim(alarm, snoozeRingId)
                    SlotClaim.Merged -> followMergedRing(mergedAs)
                    SlotClaim.NotDue -> log("late snooze claim: snooze was dismissed", null)
                }
            },
        ) { ringSlot.claimSnoozeResume(snoozeRingId, alarmId) { mergedAs = it } }
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

    private suspend fun executeFire(alarmId: String, occurrence: Instant, armedZone: ZoneId?) {
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
        if (alarm != null && (!isArmed(alarm) || isSkipped(alarm, occurrence) || movedOff(alarm, occurrence, armedZone))) {
            suppressFire(alarmId, occurrence, alarm)
            return
        }

        // Re-checked after the pre-fire check: the original fire may have rung while a replay waited on it.
        if (guarded("fired ledger read", null as Instant?) { firedLedger.lastRang(alarmId) } == occurrence) {
            finish()
            return
        }

        // The ring is decided. Vibrate now: nothing below (lock, disk, audio) may delay it. A Notify
        // link read successfully doesn't vibrate; ring() still starts it if the ring turns out full.
        // With the alarm's own pattern; none when its vibration is off (ring() then plays sound). A
        // failed read is unknown: Pulse, and sound (I1).
        currentCoroutineContext().ensureActive()
        val early = ringOutputs(listOf(alarm)).outputs
        if (!isNotifyLink(alarm) && early.vibrationOn) startVibration("vibration", early.vibration)
        // After the vibration: a disk write mustn't delay it. A failed write only uses up a one-shot.
        if (alarm != null && ringsLate(alarm, occurrence, armedZone)) {
            guarded("late ring mark", Unit) { firedLedger.markLate(alarmId, occurrence) }
        }

        val ringId = guarded("newRingId", UUID.randomUUID().toString()) { newRingId() }
        register(ringId)
        // Its own member, known even if it never sees the stored ring (a claim that completes late).
        seenMembers = setOf(RingMember(alarmId, occurrence))
        // A sounding ring takes this alarm into its merged list; a snoozed one is parked and keeps its snooze.
        // The decision is re-checked inside the claim's critical section, so a skip, disable, delete or moved
        // linked time stored since the read above still suppresses; a failed re-check counts as due (I1).
        var suppressedAlarm: Alarm? = null
        val claim = boundedClaim(
            "ring slot claim",
            onLate = { result: SlotClaim ->
                // A late "no longer due" does not stop a ring that has started.
                when (result) {
                    SlotClaim.Claimed -> adoptLateClaim(alarm, ringId)
                    SlotClaim.Merged -> followMergedRing(RingMember(alarmId, occurrence))
                    SlotClaim.NotDue -> log("late ring slot claim: no longer due", null)
                }
            },
        ) {
            ringSlot.claimFire(RingState(alarmId, ringId, now(), null, occurrenceAt = occurrence)) {
                val current = alarmRepository.get(alarmId)
                // Resolved in advance (ledger written under this lock): quiet, no skip to clear.
                if (!ownLedgerWrite && firedLedger.lastRang(alarmId) == occurrence) return@claimFire false
                suppressedAlarm = current
                val due = current != null && isArmed(current) && !isSkipped(current, occurrence) && !movedOff(current, occurrence, armedZone)
                // Under the lock, so the controller's dismiss of X (claimed or merged) sees it.
                if (due && current != null && ringsLate(current, occurrence, armedZone)) {
                    guardedNow("late ring mark") { firedLedger.markLate(alarmId, occurrence) }
                }
                due
            }
        }
        if (claim is Claim.Done && claim.value == SlotClaim.NotDue) {
            suppressFire(alarmId, occurrence, suppressedAlarm) // finish() stops vibration and unregisters the ring
            return
        }
        // No claim, so ring() rings in full: a Notify fire skipped the early vibration, start it now
        // rather than after the default-ringtone read and the media resolve.
        if (claim !is Claim.Done && early.vibrationOn) startVibration("vibration", early.vibration)
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

    /**
     * A linked row whose copied hour, minute or days no longer put [occurrence] on its schedule: the
     * owner moved the alarm and LinkSync (often the pre-fire read) copied it, so X is not an
     * occurrence any more. By the schedule alone, never `nextOccurrence`, so the owner's solo skip
     * can't suppress. Own rows are never checked: an edit there re-arms the alarm itself, and no
     * stored command asked to drop X (I1).
     *
     * A time moved earlier the same day is not moved off unless that time is skipped here or the
     * owner declined the late ring for X ([ringsLate]). Moved later, or off that day, suppresses: a later
     * time rings at its own fire.
     *
     * Checked in [armedZone], the zone X was computed in, so a zone change since arming never
     * suppresses (I1). A fire that carries no zone (a replay, a re-arm from the records, an intent
     * from an older build) is never treated as moved.
     */
    private fun movedOff(alarm: Alarm, occurrence: Instant, armedZone: ZoneId?): Boolean =
        alarm.link != null && armedZone != null && !fallsOnSchedule(alarm, occurrence, armedZone) &&
            !ringsLate(alarm, occurrence, armedZone)

    /**
     * A linked X off the row's schedule because the owner moved the time earlier the same day, to a
     * time already passed ([earlierOccurrenceThatDay]): the joiner learned of it too late, so X rings
     * late rather than not at all that day (Shawn's call, 2026-11-01). Not when that moved-to time is
     * in [skippedOccurrences] (the joiner's own skip, the owner's shared one, a pending "both" skip):
     * those are stored commands. The owner's solo skip never counts. Nor when the owner answered
     * "don't ring" for exactly this X ([Alarm.lateRing] with `ring = false`, copied by LinkSync): a
     * stored command-like value from the owner, so it may suppress (I1). Anything else rings: no
     * answer, `ring = true`, an answer for another X, or an owner doc that never arrived. A late ring is
     * marked in [firedLedger] so its dismiss leaves a linked one-shot on. Its mirrored STOP or SNOOZE is stale
     * on the owner and rejected there; RingMirror only logs that.
     */
    private fun ringsLate(alarm: Alarm, occurrence: Instant, armedZone: ZoneId?): Boolean {
        if (alarm.link == null || armedZone == null || fallsOnSchedule(alarm, occurrence, armedZone)) return false
        val moved = earlierOccurrenceThatDay(alarm, occurrence, armedZone) ?: return false
        // The owner's answer for exactly this X: a stored value their doc already copied here (I1).
        if (alarm.lateRing?.let { it.occurrenceAt == occurrence && !it.ring } == true) return false
        return moved !in alarm.skippedOccurrences()
    }

    /**
     * Quiet end for a deleted, disabled or skipped occurrence, all of them commands already stored
     * locally, or a linked occurrence the owner moved away ([movedOff]).
     */
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

    /**
     * [fire] is set on the fire path: the occurrence is marked as rung only once the ring has begun.
     *
     * Two surfaces for the same session. Ring: full-screen notification, looping sound and
     * vibration. Notify (a claimed ring whose only member is a Notify link): a heads-up notification
     * whose channel alerts once, no looping sound or vibration. A ring without a claim is always
     * full (I1), and so is a Notify ring whose notification couldn't be posted or couldn't alert
     * (notifications off, the channel blocked or silent: notify() itself never says).
     */
    private suspend fun ring(alarm: Alarm?, ringId: String, observe: Boolean, fire: Pair<String, Instant>? = null) {
        val notify = observe && isNotifyLink(alarm)
        val default = if (notify) null else readDefaultRingtone()
        val media = resolveMedia(alarm, default)
        currentCoroutineContext().ensureActive() // never start sound after the session was cancelled
        ringStarted = true
        surfaceAlarm = alarm
        surfaceMedia = media
        fullSurface = !notify
        labelledIds = (fire?.first ?: alarm?.id)?.let { listOf(it) }
        // Read again on every ring, a snooze resume included: the alarm's settings now, overrides first.
        // A Notify link's own don't count: alone, it goes full as unknown (sound and Pulse, I1).
        outputs = ringOutputs(listOf(alarm)).outputs
        outputIds = labelledIds
        if (!notify) startRingOutputs()
        // Marked after the ring has begun, so a process death before this point still gets a boot replay.
        if (fire != null) markRang(fire.first, fire.second)
        // Without a stored RingState, Stop and Snooze would be silently ignored: post no actions.
        val claimed = observe || lateClaimed
        when {
            notify -> {
                val shown = guarded("notifier", false) { notifier.showNotifyRinging(alarm, ringId, surfaceLabels(alarm)) }
                actionsPosted = shown
                // I1: a Notify ring nobody can see must still be heard.
                if (shown) watchMembers(alarm, ringId) else ringInFull(alarm, ringId)
            }
            claimed -> showRinging(alarm, ringId)
            else -> guarded("notifier", Unit) { notifier.showFallbackRinging(ringId) }
        }
        ringPosted = true
        // Before the reschedule: a slow scheduler must never leave a started ring without its end.
        ensureAutoSilence(ringId)
        if (claimed || lateClaimed) observe(ringId)
        // NonCancellable: the ring may end (and cancel this job) while the reschedule is mid-write.
        guarded("reschedule", Unit) { withContext(NonCancellable) { alarmScheduler.rescheduleAll() } }
    }

    private suspend fun readDefaultRingtone(): String? =
        guarded("settings read", null as String?) { settingsRepository.defaultRingtoneUri.first() }

    /**
     * A linked ring's media comes from the cache (an unreadable override falls to the next candidate
     * in the sound adapter); bounded and off this (main) thread, so a slow cache never delays sound.
     * The wallpaper is only for the screen, so it isn't resolved here.
     */
    private suspend fun resolveMedia(alarm: Alarm?, default: String?): RingMedia =
        resolveRingMedia(alarm, linkedMediaCache, default, null, dispatcher = ioDispatcher, systemAlarmUri = systemAlarmUri)

    /**
     * The Ring surface's [outputs], started where not yet running; a running vibration switches only
     * when the pattern differs. Vibration first: sound setup must never delay or prevent it. A
     * vibrate-only ring plays no sound at all (no candidates, no system defaults, no bundled tone)
     * only while its vibration actually runs: with no vibrator, or a start that threw, it rings with
     * sound (I1). Returns whether the vibration runs with [outputs]' pattern.
     */
    private suspend fun startRingOutputs(): Boolean {
        val out = outputs
        val vibrating = out.vibrationOn && startVibration("vibration", out.vibration)
        if (out.soundOn || !vibrating) startSound()
        return vibrating
    }

    /**
     * Sound from [soundFrom]'s media, else the surface's. A member other than the surface's primary
     * is resolved here the way [ring] resolves its own. Candidates from a linked alarm (the real
     * cache, the default cache or the fallback) already carry the system alarm Uri in the design's
     * order, so the adapter adds none of its own; an own alarm's get the adapter's defaults.
     *
     * The reverse of "vibration fails, so sound": a start that throws, or a chain the adapter
     * exhausts (no candidate and not even the bundled tone played), vibrates with Pulse when nothing
     * vibrates yet, so a sound-only ring is never silent and still (I1). See [noSound].
     */
    private suspend fun startSound() {
        if (soundStarted) return
        val from = soundFrom
        val (alarm, candidates) = if (from == null || from.first == surfaceAlarm?.id) {
            surfaceAlarm to surfaceMedia?.soundCandidates.orEmpty()
        } else {
            from.second to resolveMedia(from.second, readDefaultRingtone()).soundCandidates
        }
        // Rechecked across the resolve: another path may have started it, or the ring ended or went silent.
        if (soundStarted || finished || silencing) return
        currentCoroutineContext().ensureActive()
        val started = guarded("sound", false) {
            val ordered = alarm?.link != null && runCatching(systemAlarmUri).getOrNull() in candidates
            sound.start(candidates, appendSystemDefaults = !ordered, onExhausted = onExhausted())
            soundStarted = true
            true
        }
        if (!started) noSound("sound start failed")
    }

    /**
     * The adapter's exhaustion callback for the sound start about to be made. It may come from the
     * audio thread, so it hops onto [scope]; one for a start since stopped (silenced, restarted,
     * finished) is ignored.
     */
    private fun onExhausted(): () -> Unit {
        val run = ++soundRun
        return {
            scope.launch { if (run == soundRun && soundStarted) noSound("no sound could be played") }
        }
    }

    /**
     * Sound failed: recorded in [soundFailed], so no later member change stops the vibration, and Pulse
     * unless something already vibrates. Nothing once the ring ended or went silent (I1).
     */
    private suspend fun noSound(what: String) {
        if (finished || silencing) return
        soundFailed = true
        if (vibratingWith != null) return
        log("$what; vibrating with Pulse", null)
        startVibration("vibration", VibrationPattern.PULSE)
    }

    /**
     * Switches a sounding Notify ring to the full surface: sound, vibration and the full ringing
     * notification, whose fresh post launches the full-screen intent and replaces the Notify one.
     * [members] (ids and rows in ring order, as [watchMembers] read them) set the outputs first, so
     * the upgrade never plays what a Notify link's own settings say while [reconcileOutputs] still reads.
     */
    private suspend fun ringInFull(primary: Alarm?, ringId: String, members: List<Pair<String, Alarm?>>? = null) {
        if (members != null && !finished && !fullSurface) adoptOutputs(members.map { it.first }, members.map { it.second })
        // Ringing in full is decided: vibrate before the media resolve, which can take a while (I1).
        if (!finished && !silencing && !fullSurface && outputs.vibrationOn) startVibration("vibration", outputs.vibration)
        val media = resolveMedia(primary, readDefaultRingtone())
        // Once only: the member watch and a relabel that can't alert may both get here, each across the resolve.
        if (finished || fullSurface) return
        // Recorded even while silencing: an un-silence then restarts the full surface.
        fullSurface = true
        surfaceAlarm = primary
        surfaceMedia = media
        if (silencing) return
        currentCoroutineContext().ensureActive()
        startRingOutputs() // the merged outputs as last combined
        showRinging(primary, ringId)
    }

    /** The full ringing notification, with Stop and Snooze: only for a ring whose state is stored. */
    private suspend fun showRinging(primary: Alarm?, ringId: String) {
        guarded("notifier", Unit) { notifier.showRinging(primary, ringId, surfaceLabels(primary)) }
        actionsPosted = true
    }

    /** What the notification shows: every member's label once members changed, else the surface's own. */
    private fun surfaceLabels(primary: Alarm?): List<String> =
        relabelled ?: listOfNotNull((surfaceMedia?.label ?: primary?.label)?.takeIf { it.isNotBlank() })

    /**
     * A merge or a promotion changed who rings ([s]'s primary or merged alarms differ from what the
     * label names): the notification is updated in place with every member's label, resolved as the
     * screen does, each guarded and all of it bounded by [RELABEL_TIMEOUT]. Latest wins. The label is
     * kept for a later post (an un-silence, a Notify ring going full) even when not posted now. A
     * Notify ring that can't alert any more goes full (I1).
     */
    private fun relabelIfChanged(ringId: String, s: RingState) {
        val ids = listOf(s.alarmId) + s.merged.map { it.alarmId }
        if (ids == labelledIds) return
        labelledIds = ids
        relabelJob?.let { it.cancel(); jobs -= it }
        relabelJob = scope.launch {
            val content = guarded("member labels", null as RingScreenContent?) {
                withTimeoutOrNull(RELABEL_TIMEOUT) { ringScreenContent(s, { alarmRepository.get(it) }, linkedMediaCache, null, null, ioDispatcher) }
            } ?: return@launch
            relabelled = content.labels
            if (finished || silencing || !actionsPosted) return@launch
            val notify = !fullSurface
            val shown = guarded("notifier", !notify) { notifier.updateRingingLabel(surfaceAlarm, ringId, content.labels, notifySurface = notify) }
            if (notify && !shown) ringInFull(surfaceAlarm, ringId)
        }.also { jobs += it }
    }

    /**
     * A Notify ring stays Notify only while every member is a Notify link. Reads each member that
     * joins [ringId] (its `merged`, or a promoted primary) once; the first that is not a Notify
     * link, or can't be read (I1), switches the ring to the full surface for good. Its own job, not
     * part of [observe]: auto-silence cancels that one before its dismiss.
     */
    private fun watchMembers(primary: Alarm?, ringId: String) {
        jobs += scope.launch {
            val members = mutableMapOf<String, Alarm?>()
            primary?.let { members[it.id] = it }
            var full: RingState? = null
            try {
                ringStateStore.state.first { s ->
                    if (s == null || s.ringId != ringId || s.snoozedUntil != null) return@first true // observe() ends it
                    for (id in listOf(s.alarmId) + s.merged.map { it.alarmId }) {
                        if (id in members) continue
                        val member = guarded("member alarm read", null as Alarm?) { alarmRepository.get(id) }
                        members[id] = member
                        if (!isNotifyLink(member)) full = s
                    }
                    full != null
                }
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                log("ring member observation failed", e)
                // I1: an unreadable ring state is unknown, and unknown counts as Ring.
                // ringInFull only records the surface while silencing, for an un-silence to restart.
                if (!finished) ringInFull(primary, ringId)
                return@launch
            }
            full?.let { s -> ringInFull(members[s.alarmId], ringId, (listOf(s.alarmId) + s.merged.map { it.alarmId }).map { it to members[it] }) }
        }
    }

    private var observing = false
    private var observeJob: Job? = null
    private var ringPosted = false
    private var lateClaimed = false

    /**
     * Ends the session when the stored ring ends, is superseded or is snoozed. Meanwhile notes its
     * members: one not seen before (merged, or back from a snooze) re-arms auto-silence and, on the
     * Notify surface, alerts again ([alertJoined]). The first look on the snooze path, which starts
     * without its own member, only learns them. A change of who rings relabels the notification
     * ([relabelIfChanged]).
     */
    private fun observe(ringId: String) {
        if (observing) return
        observing = true
        observeJob = scope.launch {
            var ok = true
            try {
                ringStateStore.state.first { s ->
                    val ended = s == null || s.ringId != ringId || s.snoozedUntil != null
                    if (s != null && !ended) {
                        noteMembers(ringId, s.members().toSet())
                        relabelIfChanged(ringId, s)
                        reconcileOutputs(s)
                    }
                    ended
                }
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                log("ring state observation failed", e)
                ok = false
            }
            if (ok) finish()
        }.also { jobs += it }
    }

    /**
     * A merge, promotion or split-out changed who rings ([s]'s alarm ids differ from those [outputs]
     * came from): reads each member (bounded by [RELABEL_TIMEOUT]; one unread or unreadable counts as
     * [RingOutputs.UNKNOWN], I1, as in [watchMembers]), combines them with [ringOutputs] (Notify links
     * left out while any member rings) and starts
     * the result on the full surface: sound from the first member with it on, vibration with the
     * result's pattern, and an output no member has on any more (a split-out or promotion) stops. Each
     * stop only while the other output really runs, so the ring never goes silent and still (I1). An
     * unchanged result changes nothing, so no re-vibrate. On the Notify surface or while silent the
     * result is only kept, for [ringInFull] or [unsilence]. Latest wins.
     */
    private fun reconcileOutputs(s: RingState) {
        val ids = listOf(s.alarmId) + s.merged.map { it.alarmId }
        if (ids == outputIds) return
        outputIds = ids
        outputsJob?.let { it.cancel(); jobs -= it }
        outputsJob = scope.launch {
            val members = arrayOfNulls<Alarm>(ids.size)
            guarded("member outputs", Unit) {
                withTimeoutOrNull(RELABEL_TIMEOUT) {
                    ids.forEachIndexed { i, id ->
                        // The surface's own row as already read: a failed re-read must not turn its sound on.
                        members[i] = surfaceAlarm?.takeIf { it.id == id }
                            ?: guarded("member alarm read", null as Alarm?) { alarmRepository.get(id) }
                    }
                } ?: log("member reads timed out; unread members ring in full", null)
            }
            // A newer look (or the Notify upgrade's own read) has taken over: this result is stale.
            if (outputIds != ids) return@launch
            adoptOutputs(ids, members.toList())
            if (finished || silencing || !fullSurface) return@launch
            val vibrating = startRingOutputs()
            if (!outputs.soundOn && vibrating && soundStarted) {
                guardedNow("sound stop") { sound.stop() }
                soundStarted = false
            }
            // Not while the sound has failed: that vibration is all the ring has left (I1).
            if (!outputs.vibrationOn && soundStarted && !soundFailed && vibratingWith != null) {
                guardedNow("vibration stop") { vibration.stop() }
                vibratingWith = null
            }
        }.also { jobs += it }
    }

    /**
     * [ringOutputs] for [ids]' rows ([members], same order) become what the full surface drives.
     * Leaves [outputIds] to [reconcileOutputs]: the ids it last launched for stay the ones a running
     * job may adopt, so the Notify upgrade's read never makes a newer job's result look stale.
     */
    private fun adoptOutputs(ids: List<String>, members: List<Alarm?>) {
        val merged = ringOutputs(members)
        outputs = merged.outputs
        soundFrom = merged.soundFrom?.let { ids[it] to members[it] }
    }

    private fun noteMembers(ringId: String, current: Set<RingMember>) {
        val joined = seenMembers?.let { seen -> current.filter { it !in seen } }.orEmpty()
        seenMembers = current
        if (joined.isEmpty()) return
        armAutoSilence(ringId)
        // Its own job: the member reads have no bound, and the observation must still see the ring end.
        jobs += scope.launch { alertJoined(ringId, joined) }
    }

    /**
     * The Notify channel alerts once per post and a relabel is an update that never alerts, so a
     * Notify member joining a sounding Notify ring would go unheard, then be auto-silenced and
     * mirrored as dismissed. It gets the alerting post an un-silence makes: a fresh post that takes
     * over as the one up, or the full surface when that can't alert (I1). Not tied to the relabel,
     * which can time out; whichever of the two posts last, the notifier updates the Notify id up.
     * A member read as not a Notify link (or deleted) is [watchMembers]' to upgrade, with no chime
     * first. One whose read fails still gets it: the watch reads on its own and may find a Notify
     * link, and a chime before an upgrade is harmless where silence isn't (I1). Only a join: a
     * promotion or a split-out brings nobody new to hear.
     */
    private suspend fun alertJoined(ringId: String, joined: List<RingMember>) {
        if (finished || silencing || fullSurface || !actionsPosted) return
        for (id in joined.map { it.alarmId }.distinct()) {
            val member = try {
                alarmRepository.get(id)
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                log("member alarm read failed; alerting for it", e)
                continue
            }
            if (!isNotifyLink(member)) return
        }
        if (finished || silencing || fullSurface) return // changed across the reads
        val shown = guarded("notifier", false) { notifier.showNotifyRinging(surfaceAlarm, ringId, surfaceLabels(surfaceAlarm), alertAgain = true) }
        if (!shown) ringInFull(surfaceAlarm, ringId)
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

    private fun isNotifyLink(alarm: Alarm?) = alarm?.link?.mode == LinkMode.NOTIFY

    private companion object {
        val CLAIM_TIMEOUT = 2.seconds
        /** How long a ring sounds unanswered, counted from its start or its last member's join. */
        val AUTO_SILENCE = 10.minutes
        /** How long auto-silence's dismiss may take before it counts as stuck behind the ring-slot lock. */
        val DISMISS_TIMEOUT = 5.seconds
        /** How long a silenced session waits on a stuck dismiss in all before it finishes anyway. */
        val DISMISS_GIVE_UP = 60.seconds
        /** All of a relabel's reads and resolves; past it the notification keeps its old label. */
        val RELABEL_TIMEOUT = 2.seconds
    }
}

/**
 * Whether [occurrence] is one of the skips stored on [alarm], by the scheduler's own rule
 * ([skippedOccurrences]): what `nextOccurrence` skips is exactly what a fire or replay suppresses,
 * so a pending "unskip for both" re-arms and rings X, and a linked row's solo skip is ignored. By
 * exact stored instant, never by recomputing the next occurrence: a zone change, DST or a time
 * edit must not suppress a ring no stored command asked for (I1). The one exception is a linked
 * row whose owner moved the time or days, which [RingingSession] checks separately (`movedOff`).
 */
internal fun isSkippedOccurrence(alarm: Alarm, occurrence: Instant): Boolean =
    occurrence in alarm.skippedOccurrences()
