package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeLinkedMediaCache
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.media.LinkedMediaCache
import me.shawnsquire.tandem.media.RingMedia
import me.shawnsquire.tandem.media.DefaultLinkedMediaCache
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LateRing
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingOrigin
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.isArmed
import me.shawnsquire.tandem.schedule.nextOccurrence
import me.shawnsquire.tandem.sync.PreFireCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the media cache places as the system alarm sound (Settings.System.DEFAULT_ALARM_ALERT_URI). */
private const val SYSTEM_ALARM_URI = "content://settings/system/alarm_alert"

/** The zone the fixtures are written in: 11:30Z on 2 March 2026 is 6:30 local. */
private val TEST_ZONE: java.time.ZoneId = java.time.ZoneId.of("America/New_York")

class RecordingSound(private val clock: () -> Long) : AlarmSound {
    val starts = mutableListOf<List<String>>()
    /** Each start's appendSystemDefaults: false for a linked ring, whose candidates already end on them. */
    val appendsDefaults = mutableListOf<Boolean>()
    val startTimes = mutableListOf<Long>()
    var stops = 0
    var events: MutableList<String>? = null
    override fun start(candidates: List<String>, appendSystemDefaults: Boolean) {
        starts += candidates; appendsDefaults += appendSystemDefaults; startTimes += clock(); events?.add("sound")
    }
    override fun stop() { stops++ }
}

class RecordingVibration : AlarmVibration {
    var starts = 0
    /** Each start's pattern, in order. */
    val patterns = mutableListOf<VibrationPattern>()
    var stops = 0
    var events: MutableList<String>? = null
    /** False: a phone without a vibrator. */
    var hasVibrator = true
    /** Thrown by every start: a vibrate that fails. */
    var failure: Throwable? = null
    override fun start(pattern: VibrationPattern): Boolean {
        starts++; patterns += pattern; events?.add("vibration")
        failure?.let { throw it }
        return hasVibrator
    }
    override fun stop() { stops++ }
}

private class LambdaPreFire(val block: suspend () -> Boolean) : PreFireCheck {
    override suspend fun pullAndApply(timeout: Duration, linkedOwnerAlarmId: String?): Boolean = block()
}

private class ThrowingSetStore(private val inner: FakeRingStateStore) : RingStateStore {
    override val state: Flow<RingState?> = inner.state
    override suspend fun set(state: RingState?) { throw IllegalStateException("disk full") }
}

private class ThrowingReadStore(inner: FakeRingStateStore) : RingStateStore by inner {
    override val state: Flow<RingState?> = flow { throw IllegalStateException("read") }
}

/** Emits the stored state once per collector, then throws when it changes (or on any later emission). */
private class ThrowingAfterFirstStore(private val inner: FakeRingStateStore) : RingStateStore by inner {
    override val state: Flow<RingState?> = flow {
        var n = 0
        inner.state.collect { if (n++ >= 1) throw IllegalStateException("read"); emit(it) }
    }
}

/** A linked-media cache that returns [media], or waits [hang]/throws [fail]. */
private class StubMediaCache(
    private val media: RingMedia? = null,
    private val hang: Boolean = false,
    private val fail: Boolean = false,
) : LinkedMediaCache by FakeLinkedMediaCache() {
    override suspend fun resolve(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: String?): RingMedia {
        if (hang) awaitCancellation()
        if (fail) throw IllegalStateException("cache")
        return media!!
    }
}

/** Reads and writes suspend first, so two sessions' claims interleave unless the slot is locked. */
private class YieldingRingStateStore(private val inner: FakeRingStateStore) : RingStateStore {
    override val state: Flow<RingState?> = inner.state.onStart { yield() }
    override suspend fun set(state: RingState?) { yield(); inner.set(state) }
}

/** Holds every claim at [gate] before handing it to [inner]; a gate never opened is a stuck lock. */
private class GatedSlot(private val inner: RingSlot) : RingSlot {
    val gate = CompletableDeferred<Unit>()
    var events: MutableList<String>? = null
    override suspend fun claimFire(newRing: RingState, stillDue: suspend () -> Boolean): SlotClaim {
        events?.add("claim"); gate.await(); return inner.claimFire(newRing, stillDue)
    }
    override suspend fun claimSnoozeResume(ringId: String, alarmId: String, onMerged: (RingMember) -> Unit): SlotClaim {
        gate.await()
        return inner.claimSnoozeResume(ringId, alarmId, onMerged)
    }
    override suspend fun clearStale(isLive: (String) -> Boolean, reschedule: Boolean) = inner.clearStale(isLive, reschedule)
    override suspend fun autoSilence(ringId: String, expected: List<RingMember>) = inner.autoSilence(ringId, expected)
}

/** Runs [block] in place of [inner]'s auto-silence. */
private class AutoSilenceSlot(
    private val inner: RingSlot,
    private val block: suspend (ringId: String, expected: List<RingMember>) -> Boolean,
) : RingSlot by inner {
    override suspend fun autoSilence(ringId: String, expected: List<RingMember>) = block(ringId, expected)
}

/** A scheduler whose reschedule waits at [gate]: a dismiss calling it holds the controller lock. */
private class GatedScheduler : AlarmScheduler {
    val gate = CompletableDeferred<Unit>()
    override suspend fun rescheduleAll() { gate.await() }
}

private class ThrowingSettings : SettingsRepository by FakeSettingsRepository() {
    override val defaultRingtoneUri: Flow<String?> = flow { throw IllegalStateException("datastore") }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RingingSessionTest {
    private val occurrence = Instant.parse("2026-03-02T11:30:00Z")
    private val clockNow = Instant.parse("2026-03-02T11:30:00Z")

    private fun alarm(
        id: String = "a",
        days: Set<java.time.DayOfWeek> = emptySet(),
        enabled: Boolean = true,
        skip: Instant? = null,
        ringtone: String? = "content://tone/alarm",
    ) = Alarm(id, id, 6, 30, days, enabled, skip, ringtone, null)

    private class Harness(
        scope: TestScope,
        alarms: List<Alarm>,
        state: RingState?,
        preFire: PreFireCheck,
        now: Instant,
        ioDispatcher: CoroutineDispatcher? = null,
        settings: SettingsRepository = FakeSettingsRepository(),
        storeWrap: ((FakeRingStateStore) -> RingStateStore)? = null,
        ids: Iterator<String> = listOf("ring-1", "ring-2", "ring-3").iterator(),
        val fired: FiredLedger = InMemoryFiredLedger(),
        val live: RingRegistry = SyncRingRegistry(),
        isArmed: (Alarm) -> Boolean = ::isArmed,
        isSkipped: (Alarm, Instant) -> Boolean = ::isSkippedOccurrence,
        cache: LinkedMediaCache = DefaultLinkedMediaCache,
        systemAlarmUri: () -> String? = { SYSTEM_ALARM_URI },
    ) {
        val repo = FakeAlarmRepository(alarms)
        val rawStore = FakeRingStateStore(state)
        val store: RingStateStore = storeWrap?.invoke(rawStore) ?: rawStore
        val scheduler = FakeAlarmScheduler()
        val notifier = RecordingNotifier()
        val sound = RecordingSound { scope.testScheduler.currentTime }
        val vibration = RecordingVibration()
        var finished = 0
        var clock: Instant = now
        val ledger = InMemorySnoozeLedger()
        val armer = RecordingArmer()
        val mirrored = mutableListOf<Pair<MirrorKind, List<RingMember>>>()
        private val mirror = object : RingMirrorPort {
            override fun onLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?) { mirrored += kind to members }
        }
        val controller = RingingControllerImpl(repo, store, settings, scheduler, armer, notifier, ledger, mirror, firedLedger = fired) { clock }
        private val dispatcher = ioDispatcher ?: StandardTestDispatcher(scope.testScheduler)
        val session = RingingSession(
            repo, store, settings, preFire, scheduler, controller, sound, vibration, notifier,
            scope.backgroundScope, dispatcher, { clock }, { ids.next() },
            firedLedger = fired, linkedMediaCache = cache, liveRings = live, isArmed = isArmed, isSkipped = isSkipped,
            systemAlarmUri = systemAlarmUri, onFinished = { finished++ },
        )
        /** Armed in [TEST_ZONE], as the scheduler's fire intent carries it. */
        fun fire(id: String = "a", occ: Instant, zone: java.time.ZoneId? = TEST_ZONE) = session.start(id, occ, false, null, zone)
    }

    private class RecordingRegistry(val events: MutableList<String>) : RingRegistry {
        private val inner = SyncRingRegistry()
        override fun register(ringId: String) { events += "register:$ringId"; inner.register(ringId) }
        override fun unregister(ringId: String) { events += "unregister:$ringId"; inner.unregister(ringId) }
        override fun isLive(ringId: String) = inner.isLive(ringId)
    }

    private class ThrowingFired : FiredLedger {
        override fun lastRang(alarmId: String): Instant? = throw IllegalStateException("prefs")
        override fun markRang(alarmId: String, occurrence: Instant) = throw IllegalStateException("prefs")
        override fun lateAt(alarmId: String): Instant? = throw IllegalStateException("prefs")
        override fun markLate(alarmId: String, occurrence: Instant) = throw IllegalStateException("prefs")
    }

    private fun TestScope.harness(
        alarms: List<Alarm> = listOf(alarm()),
        state: RingState? = null,
        preFire: PreFireCheck = LambdaPreFire { true },
        ioDispatcher: CoroutineDispatcher? = null,
        settings: SettingsRepository = FakeSettingsRepository(),
        storeWrap: ((FakeRingStateStore) -> RingStateStore)? = null,
        isArmed: (Alarm) -> Boolean = ::isArmed,
        cache: LinkedMediaCache = DefaultLinkedMediaCache,
        systemAlarmUri: () -> String? = { SYSTEM_ALARM_URI },
    ) = Harness(this, alarms, state, preFire, clockNow, ioDispatcher, settings, storeWrap, isArmed = isArmed, cache = cache, systemAlarmUri = systemAlarmUri)

    // ---- I1 ----

    @Test fun i1_preFireThrowsStillRings() = runTest {
        val h = harness(preFire = LambdaPreFire { throw RuntimeException("offline") })
        h.fire(occ = occurrence)
        advanceTimeBy(4.seconds); runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun i1_preFireNeverReturnsRingsAtThreeSeconds() = runTest {
        val h = harness(preFire = LambdaPreFire { awaitCancellation() })
        h.fire(occ = occurrence)
        advanceTimeBy(2_999); runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(3_000L, h.sound.startTimes[0])
    }

    @Test fun i1_preFireFalseStillRings() = runTest {
        val h = harness(preFire = LambdaPreFire { false })
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun i1_preFireBlockingThreadStillRingsAfterTimeout() = runTest {
        val latch = CountDownLatch(1)
        try {
            val h = harness(
                preFire = LambdaPreFire { latch.await(); true },
                ioDispatcher = Dispatchers.IO,
            )
            h.fire(occ = occurrence)
            advanceTimeBy(3_001); runCurrent()
            assertEquals(1, h.sound.starts.size)
            assertEquals(3_000L, h.sound.startTimes[0])
        } finally {
            latch.countDown()
        }
    }

    @Test fun i1_preFireThrowsErrorStillRings() = runTest {
        val h = harness(preFire = LambdaPreFire { throw NoClassDefFoundError("broken") })
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun snoozeStateReadFailureStillRingsWithGivenRingId() = runTest {
        val h = harness(storeWrap = { ThrowingReadStore(it) })
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        // The claim failed, so there is no RingState: inert notification, no Stop/Snooze.
        assertEquals(listOf("ring-X"), h.notifier.fallback)
        assertTrue(h.notifier.ringing.isEmpty())
        assertEquals(0, h.finished)
    }

    @Test fun newRingIdFailureStillRings() = runTest {
        val h = harness()
        val session = RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
            h.controller, h.sound, h.vibration, h.notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { clockNow }, { throw IllegalStateException("uuid") },
            onFinished = {},
        )
        session.start("a", occurrence, false, null)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertNotNull(h.rawStore.state.first())
    }

    private fun TestScope.errorHarness(
        scheduler: AlarmScheduler? = null,
        notifier: RingingNotifier? = null,
    ): Pair<Harness, RingingSession> {
        val h = harness()
        var finished = 0
        val session = RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true },
            scheduler ?: h.scheduler, h.controller, h.sound, h.vibration, notifier ?: h.notifier,
            backgroundScope, StandardTestDispatcher(testScheduler), { clockNow }, { "ring-1" },
            onFinished = { h.finished++ },
        )
        return h to session
    }

    private suspend fun assertKeepsRingingThenAutoSilences(h: Harness, session: RingingSession, testScope: TestScope) {
        session.start("a", occurrence, false, null)
        testScope.runCurrent()
        assertEquals(1, h.sound.starts.size)
        testScope.advanceTimeBy(9.minutes); testScope.runCurrent()
        assertEquals(0, h.sound.stops)
        assertEquals(0, h.finished)
        testScope.advanceTimeBy(1.minutes + 1.seconds); testScope.runCurrent()
        assertEquals(1, h.finished)
        assertTrue(h.sound.stops >= 1)
        assertNull(h.rawStore.state.first())
    }

    @Test fun schedulerErrorStillRingsAndKeepsRinging() = runTest {
        val bad = object : AlarmScheduler {
            override suspend fun rescheduleAll() { throw NotImplementedError("U1") }
        }
        val (h, session) = errorHarness(scheduler = bad)
        assertKeepsRingingThenAutoSilences(h, session, this)
    }

    @Test fun notifierErrorStillRingsAndKeepsRinging() = runTest {
        val bad = object : RingingNotifier by RecordingNotifier() {
            override fun showRinging(alarm: Alarm?, ringId: String, labels: List<String>) { throw NoClassDefFoundError("x") }
        }
        val (h, session) = errorHarness(notifier = bad)
        assertKeepsRingingThenAutoSilences(h, session, this)
    }

    @Test fun skipArrivingInPreFirePullAfterOccurrenceSuppresses() = runTest {
        // Doze: the alarm fires late, so now() is already past the occurrence extra.
        lateinit var h: Harness
        h = Harness(
            this, listOf(alarm()), null,
            LambdaPreFire { h.repo.setSkip("a", occurrence); true },
            occurrence.plusSeconds(1),
        )
        h.fire(occ = occurrence)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(1, h.finished)
    }

    @Test fun i1_preFireInternalTimeoutStillRings() = runTest {
        val h = harness(preFire = LambdaPreFire { withTimeout(2.seconds) { awaitCancellation() } })
        h.fire(occ = occurrence)
        advanceTimeBy(3.seconds); runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun i1_preFireThrowsCancellationStillRings() = runTest {
        val h = harness(preFire = LambdaPreFire { throw CancellationException("task cancelled") })
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun cancellingSessionScopeBeforeSoundNeverStartsSound() = runTest {
        val h = harness()
        val job = Job()
        val session = RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { awaitCancellation() },
            h.scheduler, h.controller, h.sound, h.vibration, h.notifier,
            CoroutineScope(StandardTestDispatcher(testScheduler) + job),
            StandardTestDispatcher(testScheduler), { clockNow }, { "r" }, onFinished = {},
        )
        session.start("a", occurrence, false, null)
        runCurrent()
        job.cancel() // service onDestroy
        advanceTimeBy(5.seconds); runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(0, h.vibration.starts)
    }

    @Test fun autoSilenceFinishesEvenWhenDismissThrowsCancellation() = runTest {
        val h = harness()
        val slot = AutoSilenceSlot(h.controller) { _, _ -> throw CancellationException("foreign") }
        val session = RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true },
            h.scheduler, slot, h.sound, h.vibration, h.notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { clockNow }, { "r" }, onFinished = { h.finished++ },
        )
        session.start("a", occurrence, false, null)
        runCurrent()
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertEquals(1, h.finished)
        assertTrue(h.sound.stops >= 1)
    }

    @Test fun fallbackRingAttemptsReschedule() = runTest {
        val h = harness(storeWrap = { ThrowingReadStore(it) })
        h.session.start("a", clockNow, true, null) // snoozeRingId null + read failure -> fallbackRing
        runCurrent()
        assertEquals(listOf(emptyList<String>()), h.sound.starts)
        assertEquals(1, h.scheduler.rescheduleCount)
    }

    @Test fun fallbackRingShowsFallbackNotification() = runTest {
        val h = harness(storeWrap = { ThrowingReadStore(it) })
        h.session.start("a", clockNow, true, null)
        runCurrent()
        assertEquals(1, h.notifier.fallback.size)
        assertTrue(h.notifier.fallback[0].startsWith("fallback-"))
        assertTrue(h.notifier.ringing.isEmpty())
    }

    @Test fun snoozeResumeCancelsSnoozedNotification() = runTest {
        val h = harness(state = RingState("a", "ring-X", clockNow.minusSeconds(600), clockNow))
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertEquals(listOf("ring-X"), h.notifier.cancelledSnoozed)
    }

    @Test fun skipAppliedBySuccessfulCheckSuppresses() = runTest {
        lateinit var h: Harness
        h = harness(preFire = LambdaPreFire { h.repo.setSkip("a", occurrence); true })
        h.fire(occ = occurrence)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(1, h.finished)
    }

    @Test fun fireWritesRingStateWithTheFiresOccurrence() = runTest {
        val h = harness()
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(occurrence, h.rawStore.state.first()!!.occurrenceAt)
    }

    // ---- every stored skip, isArmed, solo skip on a one-shot ----

    private fun linked(
        ownerSkipped: Instant? = null,
        pendingBoth: PendingBoth? = null,
        ownerAlarmId: String = "owner-a",
    ) = alarm().copy(
        link = AlarmLink("owner", ownerAlarmId, LinkMode.RING, ownerSkipped, true, pendingBoth, null, null, null),
    )

    private suspend fun TestScope.assertQuiet(h: Harness) {
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(1, h.finished)
        assertNull(h.rawStore.state.first())
    }

    @Test fun soloSkipSuppressesAndStaysForSkipExpiry() = runTest {
        val h = harness(alarms = listOf(alarm(days = setOf(java.time.DayOfWeek.MONDAY)).copy(soloSkippedOccurrenceAt = occurrence)))
        h.fire(occ = occurrence)
        assertQuiet(h)
        assertEquals(occurrence, h.repo.get("a")!!.soloSkippedOccurrenceAt)
        assertTrue(h.repo.get("a")!!.enabled) // a repeating alarm stays on
        assertEquals(occurrence, h.fired.lastRang("a"))
    }

    @Test fun soloSkippedOneShotStaysEnabledAtTheFireForSkipExpiry() = runTest {
        val h = harness(alarms = listOf(alarm().copy(soloSkippedOccurrenceAt = occurrence)))
        h.fire(occ = occurrence)
        assertQuiet(h)
        assertTrue(h.repo.get("a")!!.enabled)
        assertEquals(occurrence, h.repo.get("a")!!.soloSkippedOccurrenceAt)
        assertEquals(occurrence, h.fired.lastRang("a"))
    }

    @Test fun soloSkipForOtherOccurrenceRings() = runTest {
        val h = harness(alarms = listOf(alarm().copy(soloSkippedOccurrenceAt = occurrence.plusSeconds(86_400))))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertTrue(h.repo.get("a")!!.enabled)
    }

    @Test fun ownerSkipOnLinkedRowSuppressesAndLeavesTheLinkAlone() = runTest {
        val h = harness(alarms = listOf(linked(ownerSkipped = occurrence)))
        h.fire(occ = occurrence)
        assertQuiet(h)
        assertEquals(occurrence, h.repo.get("a")!!.link!!.ownerSkippedOccurrenceAt)
        assertEquals(occurrence, h.fired.lastRang("a"))
    }

    @Test fun pendingBothSkipSuppresses() = runTest {
        val h = harness(alarms = listOf(linked(pendingBoth = PendingBoth(CommandType.SKIP_NEXT, occurrence, "c1"))))
        h.fire(occ = occurrence)
        assertQuiet(h)
    }

    @Test fun pendingBothSkipForOtherOccurrenceRings() = runTest {
        val other = occurrence.plusSeconds(86_400)
        val h = harness(alarms = listOf(linked(pendingBoth = PendingBoth(CommandType.SKIP_NEXT, other, "c1"))))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun pendingBothNonSkipDoesNotSuppress() = runTest {
        val h = harness(alarms = listOf(linked(pendingBoth = PendingBoth(CommandType.UNSKIP, occurrence, "c1"))))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun pendingUnskipOfTheOwnersSkipRings() = runTest {
        // The joiner tapped "unskip for both" while the owner was offline: nextOccurrence armed X.
        val h = harness(alarms = listOf(linked(ownerSkipped = occurrence, pendingBoth = PendingBoth(CommandType.UNSKIP, occurrence, "c1"))))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(0, h.finished)
    }

    @Test fun soloSkipOnALinkedRowDoesNotSuppress() = runTest {
        // The owner's solo skip is copied onto no linked row; a stray value there is never honored.
        val h = harness(alarms = listOf(linked().copy(soloSkippedOccurrenceAt = occurrence)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun pendingBothDisableSuppressesThroughIsArmed() = runTest {
        val h = harness(alarms = listOf(linked(pendingBoth = PendingBoth(CommandType.DISABLE, occurrence, "c1"))))
        h.fire(occ = occurrence)
        assertQuiet(h)
    }

    @Test fun ownerSkipForOtherOccurrenceRings() = runTest {
        val h = harness(alarms = listOf(linked(ownerSkipped = occurrence.plusSeconds(86_400))))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    // ---- a linked row whose owner moved the time ----

    /** The pre-fire read copied the owner's move of 6:30 to 7:15 onto the row (LinkSync's write). */
    private suspend fun Harness.moveTo715(id: String = "a") {
        repo.upsertLinked(repo.get(id)!!.copy(hour = 7, minute = 15))
    }

    @Test fun linkedFireAtTheOldTimeIsSuppressedOnceThePreFireReadMovedIt() = runTest {
        lateinit var h: Harness
        h = harness(alarms = listOf(linked()), preFire = LambdaPreFire { h.moveTo715(); true })
        h.fire(occ = occurrence)
        assertQuiet(h)
        assertEquals(0, h.vibration.starts)
        assertNull(h.fired.lastRang("a")) // not recorded as rung: a replay of X carries no zone, so movedOff is false and it rings
        assertEquals(1, h.scheduler.rescheduleCount)
        // 7:15 the same morning is what the scheduler now arms.
        assertEquals(occurrence.plusSeconds(45 * 60), nextOccurrence(h.repo.get("a")!!, clockNow, TEST_ZONE))
    }

    @Test fun linkedTimeMovedBetweenReadAndClaimSuppressesTheRing() = runTest {
        val h = harness(alarms = listOf(linked()))
        val slot = h.controller.before { h.moveTo715() }
        slotSession(h, slot).start("a", occurrence, false, null, TEST_ZONE)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertTrue(h.vibration.stops >= 1)
        assertEquals(1, h.scheduler.rescheduleCount)
        assertEquals(1, h.finished)
        assertNull(h.rawStore.state.first())
    }

    @Test fun linkedDaysMovedOffXSuppressesTheRing() = runTest {
        // 2 March 2026 is a Monday; the owner now rings only on Tuesdays.
        lateinit var h: Harness
        val monday = linked().copy(days = setOf(java.time.DayOfWeek.MONDAY))
        h = harness(alarms = listOf(monday), preFire = LambdaPreFire {
            h.repo.upsertLinked(monday.copy(days = setOf(java.time.DayOfWeek.TUESDAY))); true
        })
        h.fire(occ = occurrence)
        assertQuiet(h)
    }

    @Test fun linkedFireCheckedInItsArmedZoneRingsWhateverTheCurrentZone() = runTest {
        // Armed at 6:30 New York (11:30Z); the phone has since moved to Chicago, where 11:30Z is 5:30.
        // A zone change is no stored command, so the fire still rings (I1).
        val saved = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Chicago"))
        try {
            val h = harness(alarms = listOf(linked()))
            h.fire(occ = occurrence)
            runCurrent()
            assertEquals(1, h.sound.starts.size)
            assertEquals(0, h.finished)
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test fun linkedFireIsCheckedInTheZoneItWasArmedIn() = runTest {
        // The same instant checked in the zone it was armed in matches; only that zone counts.
        val h = harness(alarms = listOf(linked().copy(hour = 5, minute = 30)))
        h.fire(occ = occurrence, zone = java.time.ZoneId.of("America/Chicago"))
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun linkedFireWithoutAZoneRingsEvenWhenTheOwnerMovedIt() = runTest {
        // A replay, a re-arm from the records or an intent from an older build carries no zone.
        lateinit var h: Harness
        h = harness(alarms = listOf(linked()), preFire = LambdaPreFire { h.moveTo715(); true })
        h.fire(occ = occurrence, zone = null)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(0, h.finished)
    }

    // ---- moved earlier the same day: ring late at the old time (Shawn's call, 2026-11-01) ----

    /** The pre-fire read copied the owner's move of 6:30 to 6:00 the same morning, already passed. */
    private suspend fun Harness.moveTo600(id: String = "a") {
        repo.upsertLinked(repo.get(id)!!.copy(hour = 6, minute = 0))
    }

    private suspend fun TestScope.assertRangAt(h: Harness, x: Instant = occurrence) {
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(0, h.finished)
        assertEquals(x, h.rawStore.state.first()!!.occurrenceAt)
    }

    private val monday = setOf(java.time.DayOfWeek.MONDAY)

    @Test fun linkedMovedEarlierTheSameDayRingsLateAtTheOldTime() = runTest {
        lateinit var h: Harness
        h = harness(alarms = listOf(linked().copy(days = monday)), preFire = LambdaPreFire { h.moveTo600(); true })
        h.fire(occ = occurrence)
        assertRangAt(h)
        // Marked late, so its dismiss leaves a linked one-shot on (RingingControllerImplTest).
        assertEquals(occurrence, h.fired.lateAt("a"))
    }

    @Test fun linkedRingAtItsOwnTimeIsNotMarkedLate() = runTest {
        val h = harness(alarms = listOf(linked().copy(days = monday)))
        h.fire(occ = occurrence)
        assertRangAt(h)
        assertNull(h.fired.lateAt("a"))
    }

    @Test fun linkedMovedLaterTheSameDayIsSuppressedAndArmsTheNewTime() = runTest {
        lateinit var h: Harness
        h = harness(alarms = listOf(linked().copy(days = monday)), preFire = LambdaPreFire { h.moveTo715(); true })
        h.fire(occ = occurrence)
        assertQuiet(h)
        assertEquals(occurrence.plusSeconds(45 * 60), nextOccurrence(h.repo.get("a")!!, clockNow, TEST_ZONE))
    }

    @Test fun linkedMovedEarlierButNoLongerOnThatDayIsSuppressed() = runTest {
        // 6:00 on Tuesdays only: no occurrence on Monday, so nothing was moved earlier that day.
        lateinit var h: Harness
        val row = linked().copy(days = monday)
        h = harness(alarms = listOf(row), preFire = LambdaPreFire {
            h.repo.upsertLinked(row.copy(hour = 6, minute = 0, days = setOf(java.time.DayOfWeek.TUESDAY))); true
        })
        h.fire(occ = occurrence)
        assertQuiet(h)
    }

    /** 6:00 the same Monday: the time the owner moved X to. */
    private val sixAm = occurrence.minusSeconds(30 * 60)

    /** A Monday row the pre-fire read moves to 6:00, after [stored] put a skip on it. */
    private fun TestScope.movedEarlierWith(stored: (Alarm) -> Alarm): Harness {
        lateinit var h: Harness
        h = harness(alarms = listOf(stored(linked().copy(days = monday))), preFire = LambdaPreFire { h.moveTo600(); true })
        h.fire(occ = occurrence)
        return h
    }

    @Test fun linkedMovedEarlierToATimeTheJoinerSkippedIsSuppressed() = runTest {
        assertQuiet(movedEarlierWith { it.copy(skippedOccurrenceAt = sixAm) })
    }

    @Test fun linkedMovedEarlierToATimeTheOwnerSkippedForBothIsSuppressed() = runTest {
        assertQuiet(movedEarlierWith { it.copy(link = it.link!!.copy(ownerSkippedOccurrenceAt = sixAm)) })
    }

    @Test fun linkedMovedEarlierToATimeWithAPendingBothSkipIsSuppressed() = runTest {
        assertQuiet(movedEarlierWith { it.copy(link = it.link!!.copy(pendingBoth = PendingBoth(CommandType.SKIP_NEXT, sixAm, "c1"))) })
    }

    @Test fun linkedMovedEarlierToATimeTheOwnerSkippedJustForThemselvesStillRingsLate() = runTest {
        // The owner's solo skip never applies to a linked row.
        assertRangAt(movedEarlierWith { it.copy(soloSkippedOccurrenceAt = sixAm) })
    }

    @Test fun linkedOneShotMovedEarlierRingsLateAtTheOldTime() = runTest {
        // The owner's copy is still on: its one-shot hadn't rung and turned itself off when the joiner read it.
        lateinit var h: Harness
        h = harness(alarms = listOf(linked()), preFire = LambdaPreFire { h.moveTo600(); true })
        h.fire(occ = occurrence)
        assertRangAt(h)
    }

    /**
     * The owner edits the one-shot at 6:15, moving 6:30 to 6:00: the owner's copy stays on, next at
     * 6:00 tomorrow. The joiner learns of it at 6:30 and rings late.
     */
    private fun TestScope.lateOneShot(): Harness {
        lateinit var h: Harness
        h = harness(alarms = listOf(linked()), preFire = LambdaPreFire { h.moveTo600(); true })
        h.fire(occ = occurrence)
        return h
    }

    private val tomorrowSixAm = Instant.parse("2026-03-03T11:00:00Z")

    @Test fun lateOneShotDismissedStaysArmedForTheOwnersNextOccurrence() = runTest {
        val h = lateOneShot()
        assertRangAt(h)
        h.controller.dismiss("ring-1")
        runCurrent()
        val row = h.repo.get("a")!!
        assertTrue(row.link!!.joinerEnabled) // a late ring doesn't use up the one-shot
        assertEquals(tomorrowSixAm, nextOccurrence(row, h.clock, TEST_ZONE))
    }

    @Test fun lateOneShotAutoSilencedStaysArmedForTheOwnersNextOccurrence() = runTest {
        val h = lateOneShot()
        assertRangAt(h)
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertEquals(1, h.finished)
        assertNull(h.rawStore.state.first())
        val row = h.repo.get("a")!!
        assertTrue(row.link!!.joinerEnabled)
        assertEquals(tomorrowSixAm, nextOccurrence(row, h.clock, TEST_ZONE))
    }

    @Test fun linkedOneShotAtItsOwnTimeIsStillUsedUpByItsDismiss() = runTest {
        val h = harness(alarms = listOf(linked()))
        h.fire(occ = occurrence)
        assertRangAt(h)
        h.controller.dismiss("ring-1")
        runCurrent()
        assertFalse(h.repo.get("a")!!.link!!.joinerEnabled)
    }

    @Test fun linkedOneShotMovedEarlierAndTurnedOffIsSuppressed() = runTest {
        // The owner's one-shot rang at 6:00 and turned off; the copied enabled = false suppresses first.
        lateinit var h: Harness
        h = harness(alarms = listOf(linked()), preFire = LambdaPreFire {
            h.repo.upsertLinked(h.repo.get("a")!!.copy(hour = 6, minute = 0, enabled = false)); true
        })
        h.fire(occ = occurrence)
        assertQuiet(h)
    }

    @Test fun linkedMovedEarlierOnADstDayRingsLate() = runTest {
        // 8 March 2026: New York springs forward at 2:00. X is 6:30 EDT (10:30Z). The owner moved it to
        // 2:30, which doesn't exist that day; the schedule puts it at 3:30 EDT, still before X.
        val x = Instant.parse("2026-03-08T10:30:00Z")
        val sunday = linked().copy(days = setOf(java.time.DayOfWeek.SUNDAY))
        lateinit var h: Harness
        h = harness(alarms = listOf(sunday), preFire = LambdaPreFire { h.repo.upsertLinked(sunday.copy(hour = 2, minute = 30)); true })
        h.clock = x
        h.fire(occ = x)
        assertRangAt(h, x)
    }

    @Test fun linkedMovedEarlierBetweenReadAndClaimRingsLate() = runTest {
        val h = harness(alarms = listOf(linked().copy(days = monday)))
        val slot = h.controller.before { h.moveTo600() }
        slotSession(h, slot).start("a", occurrence, false, null, TEST_ZONE)
        assertRangAt(h)
        // The read saw 6:30; the claim's re-check saw the move and marked it.
        assertEquals(occurrence, h.fired.lateAt("a"))
    }

    @Test fun linkedMovedEarlierWithAFailedPreFireReadRingsAtX() = runTest {
        // The joiner never learns of the move: its row still says 6:30, so X rings as usual (I1).
        val h = harness(alarms = listOf(linked().copy(days = monday)), preFire = LambdaPreFire { throw RuntimeException("offline") })
        h.fire(occ = occurrence)
        assertRangAt(h)
    }

    // ---- the owner's answer to the late ring (Alarm.lateRing) ----

    private fun TestScope.movedEarlierAnswered(answer: LateRing?): Harness {
        lateinit var h: Harness
        h = harness(alarms = listOf(linked().copy(days = monday)), preFire = LambdaPreFire {
            h.repo.upsertLinked(h.repo.get("a")!!.copy(hour = 6, minute = 0, lateRing = answer)); true
        })
        h.fire(occ = occurrence)
        return h
    }

    @Test fun linkedMovedEarlierWithTheOwnerDecliningTheLateRingIsSuppressed() = runTest {
        val h = movedEarlierAnswered(LateRing(occurrence, ring = false))
        assertQuiet(h)
        assertNull(h.fired.lateAt("a"))
    }

    @Test fun linkedMovedEarlierWithTheOwnerAcceptingTheLateRingRingsLate() = runTest {
        val h = movedEarlierAnswered(LateRing(occurrence, ring = true))
        assertRangAt(h)
        assertEquals(occurrence, h.fired.lateAt("a"))
    }

    @Test fun linkedMovedEarlierWithAnAnswerForAnotherOccurrenceRingsLate() = runTest {
        assertRangAt(movedEarlierAnswered(LateRing(occurrence.plusSeconds(86_400), ring = false)))
        assertRangAt(movedEarlierAnswered(LateRing(occurrence.minusSeconds(86_400), ring = false)))
    }

    @Test fun linkedMovedEarlierWithTheDecliningAnswerArrivingBeforeTheClaimIsSuppressed() = runTest {
        val h = harness(alarms = listOf(linked().copy(days = monday)))
        val slot = h.controller.before {
            h.repo.upsertLinked(h.repo.get("a")!!.copy(hour = 6, minute = 0, lateRing = LateRing(occurrence, ring = false)))
        }
        slotSession(h, slot).start("a", occurrence, false, null, TEST_ZONE)
        assertQuiet(h)
        assertNull(h.fired.lateAt("a"))
    }

    @Test fun linkedDecliningAnswerWithAFailedPreFireReadRingsAtX() = runTest {
        // The answer never arrived (no lateRing on the old copy), and X is still on the schedule (I1).
        val h = harness(alarms = listOf(linked().copy(days = monday)), preFire = LambdaPreFire { false })
        h.fire(occ = occurrence)
        assertRangAt(h)
    }

    @Test fun ownAlarmWithADecliningLateRingAnswerStillRings() = runTest {
        // An own row's lateRing is the owner's answer for its joiners; it never affects the owner's own fires.
        lateinit var h: Harness
        h = harness(preFire = LambdaPreFire {
            h.repo.upsert(h.repo.get("a")!!.copy(hour = 6, minute = 0, lateRing = LateRing(occurrence, ring = false))); true
        })
        h.fire(occ = occurrence)
        assertRangAt(h)
    }

    @Test fun ownAlarmMovedEarlierBeforeTheFireStillRings() = runTest {
        lateinit var h: Harness
        h = harness(preFire = LambdaPreFire { h.repo.upsert(h.repo.get("a")!!.copy(hour = 6, minute = 0)); true })
        h.fire(occ = occurrence)
        assertRangAt(h)
    }

    @Test fun ownAlarmWhoseTimeChangedBeforeTheFireStillRings() = runTest {
        // Only a linked row is checked against its schedule: an own edit never suppresses (I1).
        lateinit var h: Harness
        h = harness(preFire = LambdaPreFire { h.repo.upsert(h.repo.get("a")!!.copy(hour = 7, minute = 15)); true })
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun soloSkippedLinkedRowAtItsOwnTimeStillRings() = runTest {
        // The owner's solo skip is not the joiner's: X still falls on the row's schedule, so it rings.
        val h = harness(alarms = listOf(linked().copy(soloSkippedOccurrenceAt = occurrence)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(0, h.finished)
    }

    @Test fun linkedRowOnADstGapStillRingsAtTheShiftedTime() = runTest {
        // 2:30 doesn't exist on 8 March 2026 in New York; the scheduler arms 3:30 EDT (07:30Z).
        val gapX = Instant.parse("2026-03-08T07:30:00Z")
        val h = harness(alarms = listOf(linked().copy(hour = 2, minute = 30)))
        h.clock = gapX
        assertEquals(gapX, nextOccurrence(h.repo.get("a")!!, gapX.minusSeconds(3600), TEST_ZONE))
        h.fire(occ = gapX)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun linkedSnoozeResumesAfterTheOwnerMovedTheTime() = runTest {
        // Design: a time change while the copy is snoozed lets that snooze run out on the old X.
        val snoozed = RingState("a", "ring-X", clockNow.minusSeconds(600), clockNow, occurrenceAt = occurrence.minusSeconds(600))
        val h = harness(alarms = listOf(linked().copy(hour = 7, minute = 15)), state = snoozed)
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(0, h.finished)
    }

    @Test fun linkedSnoozeResumesAfterTheCopyTurnedOff() = runTest {
        // A solo-skipped one-shot: at X + FIRE_GRACE the owner publishes enabled = false and LinkSync
        // copies it while the joiner is snoozed. The snooze still runs out.
        val snoozed = RingState("a", "ring-X", clockNow.minusSeconds(600), clockNow, occurrenceAt = occurrence.minusSeconds(600))
        val h = harness(alarms = listOf(linked().copy(enabled = false)), state = snoozed)
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(0, h.finished)
    }

    @Test fun notArmedSuppressesEvenWhenEnabled() = runTest {
        val h = harness(isArmed = { false })
        h.fire(occ = occurrence)
        assertQuiet(h)
    }

    @Test fun armedRingsEvenWhenNotEnabled() = runTest {
        val h = harness(alarms = listOf(alarm(enabled = false)), isArmed = { true })
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun disarmedBetweenReadAndClaimSuppressesTheRing() = runTest {
        var armed = true
        val h = harness(isArmed = { armed })
        val slot = h.controller.before { armed = false }
        slotSession(h, slot, isArmed = { armed }).start("a", occurrence, false, null)
        assertQuiet(h)
    }

    @Test fun soloSkipStoredBetweenReadAndClaimSuppressesAndKeepsTheSkip() = runTest {
        val h = harness()
        val slot = h.controller.before { h.repo.setSoloSkip("a", occurrence) }
        slotSession(h, slot).start("a", occurrence, false, null)
        assertQuiet(h)
        assertTrue(h.repo.get("a")!!.enabled)
        assertEquals(occurrence, h.repo.get("a")!!.soloSkippedOccurrenceAt)
    }

    @Test fun ledgerMarkedBeforeTheClaimSuppressesQuietlyWithoutClearingSkips() = runTest {
        val h = harness(alarms = listOf(alarm(skip = occurrence.plusSeconds(86_400))))
        val slot = h.controller.before { h.fired.markRang("a", occurrence) }
        slotSession(h, slot).start("a", occurrence, false, null)
        assertQuiet(h)
        assertEquals(occurrence.plusSeconds(86_400), h.repo.get("a")!!.skippedOccurrenceAt)
    }

    @Test fun ledgerMarkedDuringPreFireDoesNotRing() = runTest {
        lateinit var h: Harness
        h = harness(preFire = LambdaPreFire { h.fired.markRang("a", occurrence); true })
        h.fire(occ = occurrence)
        assertQuiet(h)
    }

    @Test fun preFireReceivesTheLinkedOwnerAlarmId() = runTest {
        val check = me.shawnsquire.tandem.fakes.FakePreFireCheck()
        val h = harness(alarms = listOf(linked(ownerAlarmId = "owner-a")), preFire = check)
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf<String?>("owner-a"), check.linkedOwnerAlarmIds)
    }

    @Test fun preFireGetsNullForAnOwnRow() = runTest {
        val check = me.shawnsquire.tandem.fakes.FakePreFireCheck()
        val h = harness(preFire = check)
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf<String?>(null), check.linkedOwnerAlarmIds)
    }

    // ---- quiet finishes ----

    @Test fun storedSkipSuppressesAndIsCleared() = runTest {
        val h = harness(alarms = listOf(alarm(skip = occurrence)))
        h.fire(occ = occurrence)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertNull(h.repo.get("a")!!.skippedOccurrenceAt)
        assertEquals(1, h.scheduler.rescheduleCount)
        assertEquals(1, h.finished)
        assertNull(h.rawStore.state.first())
    }

    @Test fun skipForOtherOccurrenceRings() = runTest {
        val h = harness(alarms = listOf(alarm(skip = occurrence.plusSeconds(86_400))))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(0, h.finished)
    }

    @Test fun disabledAlarmIsQuiet() = runTest {
        val h = harness(alarms = listOf(alarm(enabled = false)))
        h.fire(occ = occurrence)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(1, h.finished)
    }

    // ---- ringing ----

    @Test fun ringWritesStateAndStartsOutputs() = runTest {
        val settings = FakeSettingsRepository().also { it.setDefaultRingtoneUri("content://tone/default") }
        val h = harness(settings = settings)
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(RingState("a", "ring-1", clockNow, null, occurrence), h.rawStore.state.first())
        assertEquals(listOf(listOf("content://tone/alarm", "content://tone/default")), h.sound.starts)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertEquals(1, h.scheduler.rescheduleCount)
    }

    @Test fun nullRingtonesAreDropped() = runTest {
        val h = harness(alarms = listOf(alarm(ringtone = null)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf(emptyList<String>()), h.sound.starts)
    }

    @Test fun autoSilenceAfterTenMinutesCountsAsDismiss() = runTest {
        val h = harness()
        h.fire(occ = occurrence)
        runCurrent()
        advanceTimeBy(10.minutes - 1.seconds); runCurrent()
        assertNotNull(h.rawStore.state.first())
        assertEquals(0, h.sound.stops)
        advanceTimeBy(2.seconds); runCurrent()
        assertNull(h.rawStore.state.first())
        assertTrue(h.sound.stops >= 1)
        assertFalse(h.repo.get("a")!!.enabled) // one-shot
        assertEquals(listOf(MirrorKind.DISMISS to listOf(RingMember("a", occurrence))), h.mirrored) // as LOCAL
        assertEquals(1, h.finished)
    }

    @Test fun externalDismissStopsOutputsAndFinishesOnce() = runTest {
        val h = harness()
        h.fire(occ = occurrence)
        runCurrent()
        h.controller.dismiss("ring-1")
        runCurrent()
        assertTrue(h.sound.stops >= 1)
        assertTrue(h.vibration.stops >= 1)
        assertEquals(1, h.finished)
        advanceTimeBy(11.minutes); runCurrent()
        assertEquals(1, h.finished)
    }

    @Test fun snoozeStopsOutputsAndFinishes() = runTest {
        val h = harness()
        h.fire(occ = occurrence)
        runCurrent()
        h.controller.snooze()
        runCurrent()
        assertTrue(h.sound.stops >= 1)
        assertTrue(h.vibration.stops >= 1)
        assertEquals(1, h.finished)
        assertNotNull(h.rawStore.state.first()!!.snoozedUntil) // still snoozed, not dismissed
    }

    // ---- snooze path ----

    @Test fun snoozeFireRingsAgainWithSameRingId() = runTest {
        val snoozed = RingState("a", "ring-X", clockNow.minusSeconds(600), clockNow)
        val h = harness(state = snoozed)
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(RingState("a", "ring-X", snoozed.startedAt, null), h.rawStore.state.first())
        assertEquals(0, h.finished)
    }

    @Test fun snoozeFireAfterDismissIsQuiet() = runTest {
        val h = harness(state = null)
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(1, h.finished)
    }

    @Test fun snoozeFireWithDifferentRingIdIsQuiet() = runTest {
        val h = harness(state = RingState("a", "ring-Y", clockNow, clockNow))
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(1, h.finished)
    }

    // ---- re-entry ----

    @Test fun newFireMergesIntoSoundingRing() = runTest {
        val a = alarm("a")
        val b = alarm("b")
        val shared = Harness(this, listOf(a, b), null, LambdaPreFire { true }, clockNow)
        shared.fire("a", occurrence)
        runCurrent()
        val ringA = RingState("a", "ring-1", clockNow, null, occurrence)
        assertEquals(ringA, shared.rawStore.state.first())

        // Second session (its own adapters, as the service builds per start) on the same stores.
        val bSound = RecordingSound { currentTime }
        val bVibration = RecordingVibration()
        var bFinished = 0
        val bOccurrence = occurrence.plusSeconds(60)
        val sessionB = RingingSession(
            shared.repo, shared.store, FakeSettingsRepository(), LambdaPreFire { true },
            shared.scheduler, shared.controller, bSound, bVibration, shared.notifier,
            backgroundScope, StandardTestDispatcher(testScheduler), { clockNow },
            { "ring-2" }, firedLedger = shared.fired, onFinished = { bFinished++ },
        )
        sessionB.start("b", bOccurrence, false, null)
        runCurrent()

        // B joined A's ring: same ringId, alarm and occurrence.
        assertEquals(ringA.copy(merged = listOf(RingMember("b", bOccurrence))), shared.rawStore.state.first())
        // A was not dismissed and keeps sounding.
        assertTrue(shared.repo.get("a")!!.enabled)
        assertTrue(shared.armer.cancelled.isEmpty())
        assertEquals(0, shared.finished)
        assertEquals(0, shared.sound.stops)
        // B plays nothing, posts nothing and ends; its occurrence is recorded against a replay.
        assertTrue(bSound.starts.isEmpty())
        assertEquals(1, bFinished)
        assertEquals(listOf("ring-1"), shared.notifier.ringing)
        assertTrue(shared.notifier.fallback.isEmpty())
        assertEquals(bOccurrence, shared.fired.lastRang("b"))
        assertTrue(shared.repo.get("b")!!.enabled)

        // A's own auto-silence still ends the ring at ten minutes.
        advanceTimeBy(10.minutes); runCurrent()
        assertEquals(1, shared.finished)
        assertNull(shared.rawStore.state.first())
    }

    @Test fun snoozeResumeMergesIntoSoundingRing() = runTest {
        val ringB = RingState("b", "ring-B", clockNow.minusSeconds(60), null, occurrence)
        val h = harness(alarms = listOf(alarm("a"), alarm("b")), state = ringB)
        val xOccurrence = occurrence.minusSeconds(600)
        h.ledger.put("ring-X", "a", clockNow, xOccurrence)
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertEquals(ringB.copy(merged = listOf(RingMember("a", xOccurrence))), h.rawStore.state.first())
        assertNull(h.ledger.get("ring-X"))
        assertEquals(listOf("ring-X"), h.armer.cancelled)
        assertEquals(listOf("ring-X"), h.notifier.cancelledSnoozed)
        assertTrue(h.sound.starts.isEmpty())
        assertTrue(h.notifier.ringing.isEmpty())
        assertTrue(h.notifier.fallback.isEmpty())
        assertTrue(h.repo.get("b")!!.enabled)
        assertEquals(1, h.scheduler.rescheduleCount)
        assertEquals(1, h.finished)
        assertNull(h.fired.lastRang("a"))
    }

    // ---- failing steps still ring ----

    @Test fun stateWriteFailureStillRings() = runTest {
        val h = harness(storeWrap = { ThrowingSetStore(it) })
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        // Stop/Snooze would be ignored without a RingState: inert notification.
        assertEquals(listOf("ring-1"), h.notifier.fallback)
        assertTrue(h.notifier.ringing.isEmpty())
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertEquals(1, h.finished) // auto-silence still ends it
    }

    @Test fun settingsFailureStillRings() = runTest {
        val h = harness(settings = ThrowingSettings())
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf(listOf("content://tone/alarm")), h.sound.starts)
    }

    @Test fun alarmReadFailureStillRings() = runTest {
        val h = harness()
        val failingRepo = object : me.shawnsquire.tandem.data.AlarmRepository by h.repo {
            override suspend fun get(id: String): Alarm? = throw IllegalStateException("db")
        }
        val session = RingingSession(
            failingRepo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
            h.controller, h.sound, h.vibration, h.notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { clockNow }, { "ring-9" }, onFinished = {},
        )
        session.start("a", occurrence, false, null)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun claimReCheckReadFailureStillRings() = runTest {
        val h = harness()
        val reads = AtomicInteger()
        // The first two reads (pre-fire, decision) succeed; the re-check inside the claim (T2's stillDue) throws.
        val flakyRepo = object : me.shawnsquire.tandem.data.AlarmRepository by h.repo {
            override suspend fun get(id: String): Alarm? =
                if (reads.incrementAndGet() <= 2) h.repo.get(id) else throw IllegalStateException("db")
        }
        val session = RingingSession(
            flakyRepo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
            h.controller, h.sound, h.vibration, h.notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { clockNow }, { "ring-1" }, onFinished = { h.finished++ },
        )
        session.start("a", occurrence, false, null)
        runCurrent()
        assertEquals(3, reads.get()) // pre-fire read, decision read, then the re-check
        assertEquals(listOf(listOf("content://tone/alarm")), h.sound.starts) // the alarm from the first read
        assertEquals(RingState("a", "ring-1", clockNow, null, occurrence), h.rawStore.state.first())
        assertEquals(0, h.finished)
    }

    // ---- D1: snoozes survive a supersede; atomic slot claims ----

    private fun TestScope.secondSession(
        h: Harness,
        sound: RecordingSound,
        ringId: String,
        onFinished: () -> Unit,
    ) = RingingSession(
        h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
        h.controller, sound, RecordingVibration(), h.notifier, backgroundScope,
        StandardTestDispatcher(testScheduler), { h.clock }, { ringId }, onFinished = onFinished,
    )

    @Test fun managerScenario_parkedSnoozeRingsAfterSupersedingRingIsStopped() = runTest {
        val sevenOhFive = Instant.parse("2026-03-02T07:05:00Z")
        val sevenTen = Instant.parse("2026-03-02T07:10:00Z")
        val snoozedA = RingState("a", "ring-A", sevenTen.minusSeconds(600), sevenTen)
        val h = Harness(
            this, listOf(alarm("a"), alarm("b")), snoozedA, LambdaPreFire { true }, sevenOhFive,
            ids = listOf("ring-B").iterator(),
        )
        h.ledger.put("ring-A", "a", sevenTen) // as snooze() left it

        // 7:05: B fires while A is snoozed, and the user stops B.
        h.fire("b", sevenOhFive)
        runCurrent()
        assertEquals("ring-B", h.rawStore.state.first()!!.ringId)
        assertEquals(1, h.sound.starts.size)
        h.controller.dismiss("ring-B")
        runCurrent()
        assertEquals(1, h.finished)
        assertFalse(h.repo.get("b")!!.enabled)
        assertFalse("ring-A" in h.armer.cancelled)
        assertNotNull(h.ledger.get("ring-A"))
        assertTrue(h.repo.get("a")!!.enabled)

        // 7:10: A's snooze fires (the service builds a fresh session per start).
        h.clock = sevenTen
        val aSound = RecordingSound { currentTime }
        var aFinished = 0
        secondSession(h, aSound, "unused") { aFinished++ }.start("a", sevenTen, true, "ring-A")
        runCurrent()
        assertEquals(1, aSound.starts.size)
        assertEquals(0, aFinished)
        assertEquals(RingState("a", "ring-A", sevenTen, null), h.rawStore.state.first())
        assertEquals(listOf("ring-B", "ring-A"), h.notifier.ringing)
        assertTrue(h.repo.get("a")!!.enabled)
        assertNull(h.ledger.get("ring-A"))
    }

    @Test fun snoozeFireAfterDismissWhileSnoozedIsQuiet() = runTest {
        val h = harness(state = RingState("a", "ring-X", clockNow.minusSeconds(600), clockNow))
        h.ledger.put("ring-X", "a", clockNow)
        h.controller.dismiss("ring-X")
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(1, h.finished)
        assertNull(h.rawStore.state.first())
    }

    @Test fun snoozeFireAfterDismissingParkedSnoozeIsQuiet() = runTest {
        val ringB = RingState("b", "ring-B", clockNow, null)
        val h = harness(alarms = listOf(alarm("a"), alarm("b")), state = ringB)
        h.ledger.put("ring-X", "a", clockNow)
        h.controller.dismiss("ring-X") // from X's snoozed notification, while B sounds
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(1, h.finished)
        assertEquals(ringB, h.rawStore.state.first())
    }

    @Test fun preUpgradeSnoozeFireMergesIntoTheSoundingRing() = runTest {
        // Written before occurrences were stored: it joins under its snooze end, B is never dismissed.
        val ringB = RingState("b", "ring-B", clockNow, null)
        val h = harness(alarms = listOf(alarm("a"), alarm("b")), state = ringB)
        h.ledger.put("ring-X", "a", clockNow)
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertTrue(h.sound.starts.isEmpty()) // B's session plays for both
        assertEquals(1, h.finished)
        assertEquals(ringB.copy(merged = listOf(RingMember("a", clockNow))), h.rawStore.state.first())
        assertTrue(h.repo.get("b")!!.enabled)
    }

    @Test fun twoSameMinuteFiresLeaveExactlyOneRingSounding() = runTest {
        val h = harness(alarms = listOf(alarm("a"), alarm("b")), storeWrap = { YieldingRingStateStore(it) })
        val bSound = RecordingSound { currentTime }
        var bFinished = 0
        val sessionB = secondSession(h, bSound, "ring-B") { bFinished++ }
        h.fire("a", occurrence)
        sessionB.start("b", occurrence, false, null)
        runCurrent()

        val sounding = listOf(h.sound, bSound).count { it.starts.size > it.stops }
        assertEquals(1, sounding)
        assertEquals(1, h.sound.starts.size + bSound.starts.size) // the loser never started a sound
        assertEquals(1, h.finished + bFinished)
        val winner = if (h.finished == 0) "ring-1" else "ring-B"
        val final = h.rawStore.state.first()!!
        assertEquals(winner, final.ringId)
        // The loser joined the winner's ring, not silently overwritten.
        val loserAlarm = if (winner == "ring-1") "b" else "a"
        assertEquals(listOf(RingMember(loserAlarm, occurrence)), final.merged)
        assertTrue(h.repo.get(loserAlarm)!!.enabled)
    }

    // ---- D2: fired ledger and live registry ----

    private fun TestScope.ledgerHarness(fired: FiredLedger, live: RingRegistry = SyncRingRegistry(), state: RingState? = null) =
        Harness(this, listOf(alarm()), state, LambdaPreFire { true }, clockNow, fired = fired, live = live)

    @Test fun duplicateOccurrenceFinishesQuietlyBeforePreFire() = runTest {
        val fired = InMemoryFiredLedger().apply { markRang("a", occurrence) }
        var preFireCalls = 0
        val h = Harness(this, listOf(alarm()), null, LambdaPreFire { preFireCalls++; true }, clockNow, fired = fired)
        h.fire(occ = occurrence)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(0, preFireCalls)
        assertEquals(1, h.finished)
        assertNull(h.rawStore.state.first())
    }

    @Test fun differentOccurrenceStillRingsAndIsRecorded() = runTest {
        val fired = InMemoryFiredLedger().apply { markRang("a", occurrence.minusSeconds(86400)) }
        val h = ledgerHarness(fired)
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(occurrence, fired.lastRang("a"))
    }

    @Test fun secondFireOfSameOccurrenceIsSuppressed() = runTest {
        val fired = InMemoryFiredLedger()
        val h = ledgerHarness(fired)
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        val h2 = ledgerHarness(fired)
        h2.fire(occ = occurrence)
        runCurrent()
        assertTrue(h2.sound.starts.isEmpty())
        assertEquals(1, h2.finished)
    }

    @Test fun snoozeFireIsExemptFromFiredLedger() = runTest {
        val fired = InMemoryFiredLedger().apply { markRang("a", occurrence) }
        val h = ledgerHarness(fired, state = RingState("a", "ring-X", clockNow.minusSeconds(600), clockNow))
        h.session.start("a", occurrence, true, "ring-X")
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun firedLedgerFailureStillRings() = runTest {
        val h = ledgerHarness(ThrowingFired())
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals("ring-1", h.rawStore.state.first()?.ringId)
    }

    @Test fun ringIdIsRegisteredBeforeTheClaimAndRemovedOnFinish() = runTest {
        val events = mutableListOf<String>()
        val reg = RecordingRegistry(events)
        val h = ledgerHarness(InMemoryFiredLedger(), live = reg)
        val sawLiveAtClaim = mutableListOf<Boolean>()
        val watcher = launch {
            h.rawStore.state.first { it != null }
            sawLiveAtClaim += reg.isLive("ring-1")
        }
        h.fire(occ = occurrence)
        runCurrent()
        watcher.join()
        assertEquals(listOf("register:ring-1"), events)
        assertEquals(listOf(true), sawLiveAtClaim)

        h.rawStore.set(null) // ring ends
        runCurrent()
        assertEquals(listOf("register:ring-1", "unregister:ring-1"), events)
        assertFalse(reg.isLive("ring-1"))
    }
    // ---- D4: ordering and ledger timing ----

    @Test fun vibrationStartsBeforeSound() = runTest {
        val h = harness()
        val events = mutableListOf<String>()
        h.sound.events = events; h.vibration.events = events
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf("vibration", "sound"), events)
    }

    @Test fun fallbackVibrationStartsBeforeSound() = runTest {
        val h = harness(storeWrap = { ThrowingReadStore(it) })
        val events = mutableListOf<String>()
        h.sound.events = events; h.vibration.events = events
        h.session.start("a", clockNow, true, null)
        runCurrent()
        assertEquals(listOf("vibration", "sound"), events)
    }

    @Test fun vibrationStillStartsWhenSoundThrows() = runTest {
        val h = harness()
        val vib = RecordingVibration()
        val session = RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler, h.controller,
            object : AlarmSound { override fun start(candidates: List<String>, appendSystemDefaults: Boolean) = throw IllegalStateException("audio"); override fun stop() {} },
            vib, h.notifier, backgroundScope, StandardTestDispatcher(testScheduler), { clockNow }, { "ring-1" },
            onFinished = {},
        )
        session.start("a", occurrence, false, null)
        runCurrent()
        assertEquals(1, vib.starts)
    }

    @Test fun occurrenceIsMarkedOnlyAfterSoundStarts() = runTest {
        val events = mutableListOf<String>()
        val fired = object : FiredLedger {
            val inner = InMemoryFiredLedger()
            override fun lastRang(alarmId: String) = inner.lastRang(alarmId)
            override fun markRang(alarmId: String, occurrence: Instant) { events += "mark"; inner.markRang(alarmId, occurrence) }
            override fun lateAt(alarmId: String) = inner.lateAt(alarmId)
            override fun markLate(alarmId: String, occurrence: Instant) = inner.markLate(alarmId, occurrence)
        }
        val h = ledgerHarness(fired)
        h.sound.events = events
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf("sound", "mark"), events)
    }

    @Test fun occurrenceNotMarkedWhenSessionDiesBeforeRing() = runTest {
        val fired = InMemoryFiredLedger()
        val h = Harness(this, listOf(alarm()), null, LambdaPreFire { kotlinx.coroutines.delay(2000); true }, clockNow, fired = fired)
        h.fire(occ = occurrence)
        runCurrent()
        // Killed while the pre-fire check is still running: nothing rang, so a boot replay must still be allowed.
        backgroundScope.coroutineContext[Job]!!.children.forEach { it.cancel() }
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertNull(fired.lastRang("a"))
    }

    @Test fun snoozeFallbackRingDoesNotMark() = runTest {
        val fired = InMemoryFiredLedger()
        val h = Harness(this, listOf(alarm()), null, LambdaPreFire { true }, clockNow,
            storeWrap = { ThrowingReadStore(it) }, fired = fired)
        h.session.start("a", occurrence, true, null)
        runCurrent()
        assertNull(fired.lastRang("a"))
    }

    // ---- never wait on the ring slot before vibrating; bounded claims ----

    private fun TestScope.slotSession(
        h: Harness,
        slot: RingSlot,
        ringId: String = "ring-1",
        isArmed: (Alarm) -> Boolean = ::isArmed,
        cache: LinkedMediaCache = DefaultLinkedMediaCache,
    ) = RingingSession(
        h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
        slot, h.sound, h.vibration, h.notifier, backgroundScope,
        StandardTestDispatcher(testScheduler), { h.clock }, { ringId },
        firedLedger = h.fired, linkedMediaCache = cache, liveRings = h.live, isArmed = isArmed, onFinished = { h.finished++ },
    )

    @Test fun stuckClaimFireVibratesAtOnceAndRingsWithinTwoSeconds() = runTest {
        val h = harness()
        val session = slotSession(h, GatedSlot(h.controller)) // gate never opens
        session.start("a", occurrence, false, null)
        runCurrent()
        assertEquals(1, h.vibration.starts) // before the claim, not after it
        assertTrue(h.sound.starts.isEmpty())
        advanceTimeBy(1_999); runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(2_000L, h.sound.startTimes[0])
        assertEquals(1, h.vibration.starts) // ring() did not start it again
        assertEquals(listOf("ring-1"), h.notifier.fallback) // inert: no RingState behind it
        assertTrue(h.notifier.ringing.isEmpty())
        assertNull(h.rawStore.state.first())
        advanceTimeBy(10.minutes); runCurrent()
        assertEquals(1, h.finished) // auto-silence still ends it
        assertTrue(h.sound.stops >= 1)
    }

    @Test fun vibrationStartsBeforeTheClaimAndOnlyOnce() = runTest {
        val h = harness()
        val events = mutableListOf<String>()
        h.vibration.events = events
        h.sound.events = events
        val slot = GatedSlot(h.controller).also { it.events = events; it.gate.complete(Unit) }
        slotSession(h, slot).start("a", occurrence, false, null)
        runCurrent()
        assertEquals(listOf("vibration", "claim", "sound"), events)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
    }

    @Test fun lateClaimFireWriteAdoptsTheRingWithoutStoppingIt() = runTest {
        // A dismiss of ring B holds the controller lock (its reschedule is stuck), so A's claim waits.
        val ringB = RingState("b", "ring-B", clockNow, null)
        val h = harness(alarms = listOf(alarm("a"), alarm("b")), state = ringB)
        val stuck = GatedScheduler()
        val ctl = RingingControllerImpl(h.repo, h.store, FakeSettingsRepository(), stuck, h.armer, h.notifier, h.ledger) { h.clock }
        val holder = backgroundScope.launch { ctl.dismiss("ring-B") }
        runCurrent()
        assertNull(h.rawStore.state.first()) // B cleared; the dismiss is still inside the lock

        slotSession(h, ctl).start("a", occurrence, false, null)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, h.sound.starts.size) // rang on timeout
        assertEquals(listOf("ring-1"), h.notifier.fallback)
        assertNull(h.rawStore.state.first())

        stuck.gate.complete(Unit) // lock released: the claim completes late and writes A
        runCurrent()
        assertTrue(holder.isCompleted)
        assertEquals(RingState("a", "ring-1", clockNow, null, occurrence), h.rawStore.state.first())
        assertEquals(0, h.sound.stops) // the late write did not break the ring
        assertEquals(0, h.finished)
        assertEquals(listOf("ring-1"), h.notifier.ringing) // now with Stop and Snooze

        ctl.dismiss("ring-1") // Stop now works: the session observes the stored ring
        runCurrent()
        assertEquals(1, h.finished)
        assertTrue(h.sound.stops >= 1)
    }

    /** Runs [before] after the session's first alarm read but before the claim's re-check. */
    private fun RingSlot.before(before: suspend () -> Unit): RingSlot {
        val inner = this
        return object : RingSlot by inner {
            override suspend fun claimFire(newRing: RingState, stillDue: suspend () -> Boolean): SlotClaim {
                before()
                return inner.claimFire(newRing, stillDue)
            }
        }
    }

    @Test fun skipStoredBetweenReadAndClaimSuppressesTheRing() = runTest {
        val h = harness()
        val slot = h.controller.before { h.repo.setSkip("a", occurrence) }
        slotSession(h, slot).start("a", occurrence, false, null)
        runCurrent()
        assertEquals(1, h.vibration.starts) // started before the claim
        assertTrue(h.vibration.stops >= 1)
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(occurrence, h.fired.lastRang("a"))
        assertNull(h.repo.get("a")!!.skippedOccurrenceAt)
        assertEquals(1, h.scheduler.rescheduleCount)
        assertEquals(1, h.finished)
        assertNull(h.rawStore.state.first())
        assertFalse(h.live.isLive("ring-1"))
    }

    @Test fun disabledBetweenReadAndClaimSuppressesTheRing() = runTest {
        val h = harness()
        val slot = h.controller.before { h.repo.setEnabled("a", false) }
        slotSession(h, slot).start("a", occurrence, false, null)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertTrue(h.vibration.stops >= 1)
        assertNull(h.fired.lastRang("a"))
        assertEquals(1, h.scheduler.rescheduleCount)
        assertEquals(1, h.finished)
        assertNull(h.rawStore.state.first())
    }

    @Test fun lateClaimFireNotDueDoesNotStopTheRing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness()
        val slot = object : RingSlot by h.controller {
            override suspend fun claimFire(newRing: RingState, stillDue: suspend () -> Boolean): SlotClaim {
                gate.await()
                return SlotClaim.NotDue
            }
        }
        slotSession(h, slot).start("a", occurrence, false, null)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, h.sound.starts.size) // rang on timeout
        gate.complete(Unit) // the claim finishes late with "not due"
        runCurrent()
        assertEquals(0, h.sound.stops)
        assertEquals(0, h.finished)
    }

    @Test fun lateMergedClaimKeepsSoundingUntilTheRingItJoinedEnds() = runTest {
        val ringA = RingState("a", "ring-A", clockNow, null, occurrence)
        val h = harness(alarms = listOf(alarm("a"), alarm("b")), state = ringA)
        val slot = GatedSlot(h.controller)
        val bAt = occurrence.plusSeconds(60)
        slotSession(h, slot, ringId = "ring-B").start("b", bAt, false, null)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, h.sound.starts.size) // rang on timeout, inert
        slot.gate.complete(Unit) // the claim finishes late: b merged into A's sounding ring
        runCurrent()
        assertEquals(ringA.copy(merged = listOf(RingMember("b", bAt))), h.rawStore.state.first())
        assertEquals(0, h.sound.stops)
        assertEquals(0, h.finished)
        assertTrue(h.notifier.ringing.isEmpty()) // the other ring's state is not adopted
        h.rawStore.set(ringA.copy(merged = listOf(RingMember("b", bAt), RingMember("c", bAt)))) // still a member
        runCurrent()
        assertEquals(0, h.finished)

        h.controller.dismiss("ring-A") // Stop on A resolves b too
        runCurrent()
        assertFalse(h.repo.get("b")!!.enabled)
        assertEquals(1, h.finished) // so b's fallback ring ends with it
        assertTrue(h.sound.stops >= 1)
    }

    @Test fun lateMergedSnoozeClaimKeepsSoundingUntilTheRingItJoinedIsSnoozed() = runTest {
        val ringA = RingState("a", "ring-A", clockNow, null, occurrence)
        val h = harness(alarms = listOf(alarm("a"), alarm("b")), state = ringA)
        val xAt = occurrence.minusSeconds(600)
        h.ledger.put("ring-X", "b", clockNow, xAt)
        val slot = GatedSlot(h.controller)
        slotSession(h, slot).start("b", clockNow, true, "ring-X")
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, h.sound.starts.size)
        slot.gate.complete(Unit)
        runCurrent()
        assertEquals(ringA.copy(merged = listOf(RingMember("b", xAt))), h.rawStore.state.first())
        assertEquals(0, h.finished)

        h.controller.snooze() // b splits out into its own snooze, which rings it later
        runCurrent()
        assertEquals(1, h.finished)
        assertTrue(h.ledger.all().any { it.alarmId == "b" && it.occurrenceAt == xAt })
    }

    @Test fun lateMergedClaimEndsAtOnceWhenTheMemberIsAlreadyGone() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness()
        val slot = object : RingSlot by h.controller {
            override suspend fun claimFire(newRing: RingState, stillDue: suspend () -> Boolean): SlotClaim {
                gate.await()
                return SlotClaim.Merged // and that ring was stopped before this session heard back
            }
        }
        slotSession(h, slot).start("a", occurrence, false, null)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, h.sound.starts.size)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, h.finished)
    }

    @Test fun lateClaimAfterSessionFinishedIsIgnored() = runTest {
        val h = harness()
        val slot = GatedSlot(h.controller)
        slotSession(h, slot).start("a", occurrence, false, null)
        advanceTimeBy(2_001); runCurrent()
        advanceTimeBy(10.minutes); runCurrent()
        assertEquals(1, h.finished)
        slot.gate.complete(Unit); runCurrent()
        assertTrue(h.notifier.ringing.isEmpty()) // no actions posted for a finished session
        assertEquals(1, h.finished)
    }

    @Test fun stuckSnoozeClaimRingsWithinTwoSecondsAsLive() = runTest {
        val h = harness(state = RingState("a", "ring-X", clockNow.minusSeconds(600), clockNow))
        val session = slotSession(h, GatedSlot(h.controller))
        session.start("a", clockNow, true, "ring-X")
        advanceTimeBy(1_999); runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-X"), h.notifier.fallback)
        assertTrue("ring-X" in h.notifier.cancelledSnoozed)
        assertEquals(0, h.finished)
    }

    @Test fun autoSilenceStopsSoundEvenWhenDismissHangs() = runTest {
        val h = harness()
        val dismissed = mutableListOf<String>()
        val hanging = AutoSilenceSlot(h.controller) { ringId, _ -> dismissed += ringId; awaitCancellation() }
        slotSession(h, hanging).start("a", occurrence, false, null)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertTrue(h.sound.stops >= 1) // silent at once
        assertTrue(h.vibration.stops >= 1)
        assertEquals(listOf("ring-1"), dismissed) // asked after the sound stopped
        assertEquals(0, h.finished) // the service stays up while the dismiss gets its chance
        advanceTimeBy(58.seconds); runCurrent()
        assertEquals(0, h.finished) // silent, still waiting: a member it merges must not go unheard
        advanceTimeBy(1.seconds); runCurrent()
        assertEquals(1, h.finished) // bounded: a hung dismiss cannot keep the session forever
    }

    @Test fun autoSilenceFinishesOnlyAfterTheDismissCompletes() = runTest {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        val gated = AutoSilenceSlot(h.controller) { ringId, expected -> gate.await(); h.controller.autoSilence(ringId, expected) }
        slotSession(h, gated).start("a", occurrence, false, null)
        runCurrent()
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertTrue(h.sound.stops >= 1)
        assertEquals(0, h.finished)
        gate.complete(Unit); runCurrent()
        assertNull(h.rawStore.state.first())
        assertFalse(h.repo.get("a")!!.enabled) // a real dismiss: one-shot disabled
        assertEquals(1, h.finished)
    }

    @Test fun aMemberMergedBehindAStuckLockSoundsWhenTheDismissFinallyRefuses() = runTest {
        // Past the old 5 s bound the session finished, and nobody sounded b (its fired-ledger entry
        // blocked any replay). Now it waits silently and sounds on for b.
        val h = harness(alarms = listOf(alarm("a"), alarm("b")))
        val bAt = occurrence.plusSeconds(600)
        val gate = CompletableDeferred<Unit>()
        val slot = AutoSilenceSlot(h.controller) { ringId, expected ->
            gate.await()
            h.controller.claimFire(RingState("b", "ring-b", h.clock, null, bAt))
            h.controller.autoSilence(ringId, expected)
        }
        slotSession(h, slot).start("a", occurrence, false, null)
        runCurrent()
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertTrue(h.sound.stops >= 1)
        advanceTimeBy(30.seconds); runCurrent()
        assertEquals(0, h.finished) // silent, but still waiting on the dismiss
        assertEquals(1, h.sound.starts.size)
        gate.complete(Unit); runCurrent()
        assertEquals(2, h.sound.starts.size) // sounds again for b
        assertEquals(0, h.finished)
        assertTrue(h.repo.get("b")!!.enabled)
        h.controller.dismiss("ring-1")
        runCurrent()
        assertEquals(1, h.finished)
    }

    @Test fun autoSilenceDismissIsLocal() = runTest {
        val h = harness()
        h.fire(occ = occurrence)
        runCurrent()
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertEquals(listOf(MirrorKind.DISMISS to listOf(RingMember("a", occurrence))), h.mirrored) // LOCAL: mirrored
    }

    // ---- auto-silence and merged members ----

    @Test fun threeMergedAlarmsEachRingTenMinutes() = runTest {
        // 7:00, 7:05 and 7:09 merged: the ring ends ten minutes after the last one joined.
        val h = harness(alarms = listOf(alarm("a"), alarm("b"), alarm("c")))
        h.fire("a", occurrence)
        runCurrent()
        advanceTimeBy(5.minutes); runCurrent()
        val bAt = occurrence.plusSeconds(300)
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("b", "ring-b", h.clock, null, bAt)))
        runCurrent()
        advanceTimeBy(4.minutes); runCurrent()
        val cAt = occurrence.plusSeconds(540)
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("c", "ring-c", h.clock, null, cAt)))
        runCurrent()

        advanceTimeBy(10.minutes - 1.seconds); runCurrent() // 18:59 in: a's and b's own ten minutes are long gone
        assertNotNull(h.rawStore.state.first())
        assertEquals(0, h.sound.stops)
        assertEquals(0, h.finished)
        advanceTimeBy(2.seconds); runCurrent()
        assertNull(h.rawStore.state.first())
        assertEquals(listOf(MirrorKind.DISMISS to listOf(RingMember("a", occurrence), RingMember("b", bAt), RingMember("c", cAt))), h.mirrored)
        assertFalse(h.repo.get("a")!!.enabled)
        assertFalse(h.repo.get("b")!!.enabled)
        assertFalse(h.repo.get("c")!!.enabled)
        assertEquals(1, h.finished)
        assertEquals(1, h.sound.starts.size) // one sound throughout
    }

    @Test fun memberMergingWhileAutoSilenceWaitsKeepsTheRingSounding() = runTest {
        val h = harness(alarms = listOf(alarm("a"), alarm("b")))
        val bAt = occurrence.plusSeconds(600)
        var silencedWith: List<RingMember>? = null
        // b's claim lands on the lock just before auto-silence's dismiss.
        val slot = AutoSilenceSlot(h.controller) { ringId, expected ->
            silencedWith = expected
            h.controller.claimFire(RingState("b", "ring-b", h.clock, null, bAt))
            h.controller.autoSilence(ringId, expected)
        }
        slotSession(h, slot).start("a", occurrence, false, null)
        runCurrent()
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()

        assertEquals(listOf(RingMember("a", occurrence)), silencedWith)
        assertTrue(h.sound.stops >= 1) // it did go silent first
        assertEquals(2, h.sound.starts.size) // and sounds again for b
        assertEquals(2, h.vibration.starts)
        assertEquals(0, h.finished)
        assertEquals(RingState("a", "ring-1", clockNow, null, occurrence, listOf(RingMember("b", bAt))), h.rawStore.state.first())
        assertTrue(h.repo.get("b")!!.enabled) // b is not resolved
        assertTrue(h.repo.get("a")!!.enabled)
        assertTrue(h.mirrored.isEmpty())

        // A fresh ten minutes from the resume, and Stop works again.
        advanceTimeBy(10.minutes - 2.seconds); runCurrent()
        assertNotNull(h.rawStore.state.first())
        assertEquals(0, h.finished)
        h.controller.dismiss("ring-1")
        runCurrent()
        assertEquals(1, h.finished)
        assertFalse(h.repo.get("b")!!.enabled)
    }

    @Test fun memberJoiningAfterAResumeGetsItsTenMinutesOnAnImmediateDispatcher() = runTest {
        // An immediate dispatcher (as the service's Main.immediate) runs the re-observe's first look
        // inside the resume: its re-arm must replace the resume's own timer, not orphan one.
        val h = harness(alarms = listOf(alarm("a"), alarm("b"), alarm("c")))
        val bAt = occurrence.plusSeconds(600)
        var calls = 0
        val slot = AutoSilenceSlot(h.controller) { ringId, expected ->
            if (calls++ == 0) h.controller.claimFire(RingState("b", "ring-b", h.clock, null, bAt))
            h.controller.autoSilence(ringId, expected)
        }
        val immediate = UnconfinedTestDispatcher(testScheduler)
        RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
            slot, h.sound, h.vibration, h.notifier, CoroutineScope(backgroundScope.coroutineContext + immediate),
            immediate, { h.clock }, { "ring-1" }, firedLedger = h.fired, onFinished = { h.finished++ },
        ).start("a", occurrence, false, null)
        runCurrent()
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertEquals(0, h.finished) // resumed for b
        advanceTimeBy(5.minutes); runCurrent()
        val cAt = occurrence.plusSeconds(900)
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("c", "ring-c", h.clock, null, cAt)))
        runCurrent()
        advanceTimeBy(5.minutes + 1.seconds); runCurrent() // the resume's ten minutes are up; c's are not
        assertNotNull(h.rawStore.state.first())
        assertEquals(0, h.finished)
        advanceTimeBy(5.minutes); runCurrent()
        assertNull(h.rawStore.state.first())
        assertEquals(1, h.finished)
    }

    @Test fun resumedRingWhoseClaimLandedWhileSilentGetsItsActions() = runTest {
        val h = harness(alarms = listOf(alarm("a"), alarm("b")))
        val gated = GatedSlot(h.controller)
        val bAt = occurrence.plusSeconds(600)
        val slot = AutoSilenceSlot(gated) { ringId, expected ->
            gated.gate.complete(Unit) // the stuck claim writes ring-1 only now, while the ring is silent
            repeat(10) { yield() }
            h.controller.claimFire(RingState("b", "ring-b", h.clock, null, bAt))
            h.controller.autoSilence(ringId, expected)
        }
        slotSession(h, slot).start("a", occurrence, false, null)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(listOf("ring-1"), h.notifier.fallback) // inert: no Stop or Snooze yet
        advanceTimeBy(10.minutes); runCurrent()
        assertEquals(RingState("a", "ring-1", clockNow, null, occurrence, listOf(RingMember("b", bAt))), h.rawStore.state.first())
        assertEquals(0, h.finished)
        assertEquals(listOf("ring-1"), h.notifier.ringing) // resumed with its actions
        h.controller.dismiss("ring-1")
        runCurrent()
        assertEquals(1, h.finished)
    }

    @Test fun resumedRingAutoSilencesTenMinutesLater() = runTest {
        val h = harness(alarms = listOf(alarm("a"), alarm("b")))
        val bAt = occurrence.plusSeconds(600)
        var calls = 0
        val slot = AutoSilenceSlot(h.controller) { ringId, expected ->
            if (calls++ == 0) h.controller.claimFire(RingState("b", "ring-b", h.clock, null, bAt))
            h.controller.autoSilence(ringId, expected)
        }
        slotSession(h, slot).start("a", occurrence, false, null)
        runCurrent()
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertEquals(0, h.finished)
        advanceTimeBy(10.minutes - 2.seconds); runCurrent()
        assertNotNull(h.rawStore.state.first())
        advanceTimeBy(3.seconds); runCurrent()
        assertEquals(2, calls)
        assertNull(h.rawStore.state.first())
        assertEquals(listOf(MirrorKind.DISMISS to listOf(RingMember("a", occurrence), RingMember("b", bAt))), h.mirrored)
        assertEquals(1, h.finished)
    }

    @Test fun notifyRingResumedForANotifyMemberRepostsTheNotifyNotification() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), linkedAs(LinkMode.NOTIFY, "b")))
        val bAt = occurrence.plusSeconds(600)
        val slot = AutoSilenceSlot(h.controller) { ringId, expected ->
            h.controller.claimFire(RingState("b", "ring-b", h.clock, null, bAt))
            h.controller.autoSilence(ringId, expected)
        }
        slotSession(h, slot).start("a", occurrence, false, null)
        runCurrent()
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertEquals(listOf("ring-1", "ring-1"), h.notifier.notify)
        assertEquals(listOf("ring-1"), h.notifier.notifyAlertsAgain) // the re-post alerts: the ring sounds on for b
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(0, h.vibration.starts)
        assertEquals(0, h.finished)
    }

    @Test fun notifyRingResumedWhenTheNotifySurfaceCantAlertComesBackInFull() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), linkedAs(LinkMode.NOTIFY, "b")))
        val bAt = occurrence.plusSeconds(600)
        val slot = AutoSilenceSlot(h.controller) { ringId, expected ->
            h.notifier.notifyCanAlert = false // notifications blocked while the ring was up
            h.controller.claimFire(RingState("b", "ring-b", h.clock, null, bAt))
            h.controller.autoSilence(ringId, expected)
        }
        slotSession(h, slot).start("a", occurrence, false, null)
        runCurrent()
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertEquals(0, h.finished)
    }

    @Test fun notifyRingResumedForARingMemberComesBackInFull() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), alarm("b")))
        val bAt = occurrence.plusSeconds(600)
        val slot = AutoSilenceSlot(h.controller) { ringId, expected ->
            h.controller.claimFire(RingState("b", "ring-b", h.clock, null, bAt))
            repeat(10) { yield() } // the member watcher sees b while the ring is silent
            h.controller.autoSilence(ringId, expected)
        }
        slotSession(h, slot).start("a", occurrence, false, null)
        runCurrent()
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertEquals(0, h.finished)
    }

    @Test fun lateClaimFireWriteBehindStuckLockIsClearedByAutoSilence() = runTest {
        // The claim waits behind a stuck dismiss of B; auto-silence's dismiss queues behind the claim.
        val ringB = RingState("b", "ring-B", clockNow, null)
        val h = harness(alarms = listOf(alarm("a"), alarm("b")), state = ringB)
        val stuck = GatedScheduler()
        val ctl = RingingControllerImpl(h.repo, h.store, FakeSettingsRepository(), stuck, h.armer, h.notifier, h.ledger) { h.clock }
        backgroundScope.launch { ctl.dismiss("ring-B") }
        runCurrent()

        slotSession(h, ctl).start("a", occurrence, false, null)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(listOf("ring-1"), h.notifier.fallback) // claim timed out, ringing inertly
        advanceTimeBy(10.minutes + 60.seconds); runCurrent()
        assertEquals(1, h.finished) // auto-silence gave up waiting on the lock
        assertTrue(h.sound.stops >= 1)

        stuck.gate.complete(Unit) // lock released: the late claim writes, then the queued dismiss runs
        runCurrent()
        assertNull(h.rawStore.state.first()) // no RingState left without a session
        assertFalse(h.repo.get("a")!!.enabled) // A counted as dismissed
        assertTrue(h.notifier.ringing.isEmpty()) // the finished session did not adopt the late claim
    }

    @Test fun replayFindsOccurrenceRungDuringPreFireAndIsQuiet() = runTest {
        // The original fire rings (and marks the ledger) while this replay waits on the network check.
        lateinit var h: Harness
        h = Harness(this, listOf(alarm()), null, LambdaPreFire { h.fired.markRang("a", occurrence); true }, clockNow)
        h.fire(occ = occurrence)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(0, h.vibration.starts)
        assertEquals(1, h.finished)
        assertNull(h.rawStore.state.first())
    }

    @Test fun skippedOccurrenceIsMarkedSoItsReplayCannotRing() = runTest {
        val h = harness(alarms = listOf(alarm(skip = occurrence)))
        h.fire(occ = occurrence)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(occurrence, h.fired.lastRang("a"))
        assertNull(h.repo.get("a")!!.skippedOccurrenceAt) // skip consumed

        // Boot replay of the same occurrence, after the skip was cleared.
        val replaySound = RecordingSound { currentTime }
        var replayFinished = 0
        RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
            h.controller, replaySound, RecordingVibration(), h.notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { clockNow }, { "ring-R" },
            firedLedger = h.fired, liveRings = h.live, onFinished = { replayFinished++ },
        ).start("a", occurrence, false, null)
        runCurrent()
        assertTrue(replaySound.starts.isEmpty())
        assertEquals(1, replayFinished)
    }

    // ---- an occurrence resolved before its fire claims (the real controller) ----

    private val repeating = alarm(days = setOf(java.time.DayOfWeek.MONDAY)) // stays armed: only the fired ledger can quiet it
    private val snoozeEnd = occurrence.plusSeconds(300)

    @Test fun earlyStopMakesTheLaterFireQuiet() = runTest {
        val h = harness(alarms = listOf(alarm()))
        h.controller.dismissOccurrence("a", occurrence, RingOrigin.REMOTE)
        assertEquals(occurrence, h.fired.lastRang("a"))
        assertFalse(h.repo.get("a")!!.enabled) // one-shot off, as on a normal dismiss
        h.fire(occ = occurrence)
        assertQuiet(h)
    }

    @Test fun earlySnoozeMakesTheFireQuietAndTheSnoozeRingsWithTheOccurrence() = runTest {
        val h = harness(alarms = listOf(repeating))
        h.controller.snoozeOccurrence("a", occurrence, snoozeEnd, RingOrigin.REMOTE)
        assertEquals(occurrence, h.fired.lastRang("a"))
        val entry = h.ledger.all().single()
        assertEquals(snoozeEnd, entry.until)
        assertEquals(occurrence, entry.occurrenceAt)
        assertEquals(listOf(Triple("a", entry.ringId, snoozeEnd)), h.armer.armed)

        h.fire(occ = occurrence)
        assertQuiet(h)

        h.clock = snoozeEnd
        slotSession(h, h.controller).start("a", snoozeEnd, true, entry.ringId)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(RingState("a", entry.ringId, snoozeEnd, null, occurrence), h.rawStore.state.first())
    }

    private val bOccurrence = occurrence.plusSeconds(60)

    /** a rings in this session; b fires a minute later and merges into it. */
    private suspend fun TestScope.mergedRing(): Harness {
        val h = harness(alarms = listOf(alarm("a"), alarm("b")))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("b", "ring-B", h.clock, null, bOccurrence)))
        runCurrent()
        return h
    }

    @Test fun stopForThePrimaryKeepsTheSessionSoundingForThePromotedMember() = runTest {
        val h = mergedRing()
        h.controller.dismissOccurrence("a", occurrence, RingOrigin.REMOTE)
        runCurrent()
        assertEquals(0, h.finished)
        assertEquals(0, h.sound.stops)
        val state = h.rawStore.state.first()!!
        assertEquals("ring-1", state.ringId)
        assertEquals(RingMember("b", bOccurrence), RingMember(state.alarmId, state.occurrenceAt!!))

        h.controller.dismissOccurrence("b", bOccurrence, RingOrigin.REMOTE) // the last member ends it
        runCurrent()
        assertEquals(1, h.finished)
        assertNull(h.rawStore.state.first())
    }

    @Test fun snoozeForAMemberSplitsItOutAndKeepsTheSessionSounding() = runTest {
        val h = mergedRing()
        h.controller.snoozeOccurrence("b", bOccurrence, snoozeEnd, RingOrigin.REMOTE)
        runCurrent()
        assertEquals(0, h.finished)
        assertEquals(0, h.sound.stops)
        assertEquals(RingState("a", "ring-1", clockNow, null, occurrence), h.rawStore.state.first())
        val entry = h.ledger.all().single()
        assertEquals(SnoozeEntry(entry.ringId, "b", snoozeEnd, bOccurrence), entry)
    }

    /** X fired here at 11:30:00 and sits in its pre-fire check; the partner's command lands at 11:30:01. */
    private fun TestScope.preFireRace(resolve: suspend (RingingControllerImpl) -> Unit): Harness {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, listOf(repeating), null, LambdaPreFire { gate.await(); true }, occurrence.plusSeconds(1))
        h.fire(occ = occurrence)
        runCurrent() // inside the pre-fire check
        backgroundScope.launch { resolve(h.controller) }
        runCurrent()
        gate.complete(Unit)
        return h
    }

    @Test fun stopLandingDuringThePreFireCheckSuppressesTheFire() = runTest {
        val h = preFireRace { it.dismissOccurrence("a", occurrence, RingOrigin.REMOTE) }
        assertQuiet(h)
        assertEquals(0, h.vibration.starts)
        assertEquals(occurrence, h.fired.lastRang("a"))
        assertTrue(h.repo.get("a")!!.enabled)
    }

    @Test fun snoozeLandingDuringThePreFireCheckSuppressesTheFireAndArmsTheSnooze() = runTest {
        val h = preFireRace { it.snoozeOccurrence("a", occurrence, snoozeEnd, RingOrigin.REMOTE) }
        assertQuiet(h)
        assertEquals(occurrence, h.fired.lastRang("a"))
        val entry = h.ledger.all().single()
        assertEquals(SnoozeEntry(entry.ringId, "a", snoozeEnd, occurrence), entry)
        assertEquals(listOf(Triple("a", entry.ringId, snoozeEnd)), h.armer.armed)
    }

    @Test fun stopLandingAfterTheRecheckButBeforeTheClaimSuppressesTheFire() = runTest {
        val h = harness(alarms = listOf(repeating))
        h.clock = occurrence.plusSeconds(1)
        val slot = h.controller.before { h.controller.dismissOccurrence("a", occurrence, RingOrigin.REMOTE) }
        slotSession(h, slot).start("a", occurrence, false, null)
        assertQuiet(h) // stillDue saw the ledger under the controller's lock
        assertEquals(occurrence, h.fired.lastRang("a"))
    }

    @Test fun snoozeLandingAfterTheRecheckButBeforeTheClaimSuppressesTheFire() = runTest {
        val h = harness(alarms = listOf(repeating))
        h.clock = occurrence.plusSeconds(1)
        val slot = h.controller.before { h.controller.snoozeOccurrence("a", occurrence, snoozeEnd, RingOrigin.REMOTE) }
        slotSession(h, slot).start("a", occurrence, false, null)
        assertQuiet(h)
        assertEquals(snoozeEnd, h.ledger.all().single().until)
    }

    // ---- Notify mode ----

    private fun linkedAs(mode: LinkMode, id: String = "a") = alarm(id).copy(
        link = AlarmLink("owner", "owner-$id", mode, null, true, null, null, null, null),
    )

    /** A second session on [h]'s stores, with its own adapters, as the service builds one per start. */
    private class Joiner(val sound: RecordingSound, val vibration: RecordingVibration) { var finished = 0 }

    private fun TestScope.join(h: Harness, alarmId: String, occ: Instant): Joiner {
        val j = Joiner(RecordingSound { currentTime }, RecordingVibration())
        RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true },
            h.scheduler, h.controller, j.sound, j.vibration, h.notifier,
            backgroundScope, StandardTestDispatcher(testScheduler), { h.clock },
            { "ring-2" }, firedLedger = h.fired, onFinished = { j.finished++ },
        ).start(alarmId, occ, false, null)
        return j
    }

    @Test fun notifyLinkedFirePostsTheNotifyNotificationWithoutSoundOrVibration() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf("ring-1"), h.notifier.notify)
        assertTrue(h.notifier.ringing.isEmpty())
        assertTrue(h.notifier.fallback.isEmpty())
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(0, h.vibration.starts)
        assertEquals(occurrence, h.fired.lastRang("a"))
        assertEquals(RingState("a", "ring-1", clockNow, null, occurrence), h.rawStore.state.first())
        assertEquals(0, h.finished)

        // Same auto-silence as a ring: a LOCAL dismiss at ten minutes, which mirrors.
        advanceTimeBy(10.minutes - 1.seconds); runCurrent()
        assertNotNull(h.rawStore.state.first())
        advanceTimeBy(2.seconds); runCurrent()
        assertNull(h.rawStore.state.first())
        assertEquals(listOf(MirrorKind.DISMISS to listOf(RingMember("a", occurrence))), h.mirrored)
        assertEquals(1, h.finished)
        assertTrue(h.sound.starts.isEmpty())
    }

    @Test fun ringLinkedFireRingsInFull() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.RING)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertTrue(h.notifier.notify.isEmpty())
    }

    @Test fun ownFireRingsInFull() = runTest {
        val h = harness()
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(listOf(true), h.sound.appendsDefaults) // the adapter appends the system defaults
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertTrue(h.notifier.notify.isEmpty())
    }

    @Test fun notifyRingSnoozedThenResumedAtTPostsTheNotifyNotificationAgain() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY)))
        h.fire(occ = occurrence)
        runCurrent()
        h.controller.snooze(RingOrigin.LOCAL, snoozeEnd)
        runCurrent()
        assertEquals(1, h.finished) // the session (so its notification) ends
        val entry = h.ledger.all().single()
        assertEquals("ring-1", entry.ringId)
        assertEquals(listOf(MirrorKind.SNOOZE to listOf(RingMember("a", occurrence))), h.mirrored)

        h.clock = snoozeEnd
        slotSession(h, h.controller).start("a", snoozeEnd, true, entry.ringId)
        runCurrent()
        assertEquals(listOf("ring-1", "ring-1"), h.notifier.notify)
        assertTrue(h.notifier.ringing.isEmpty())
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(0, h.vibration.starts)
        assertEquals(RingState("a", "ring-1", clockNow, null, occurrence), h.rawStore.state.first())
    }

    @Test fun ringFireMergingIntoANotifyRingUpgradesItToTheFullSurface() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), alarm("b", ringtone = "content://tone/b")))
        h.fire(occ = occurrence)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())

        val b = join(h, "b", bOccurrence)
        runCurrent()
        assertEquals(listOf(RingMember("b", bOccurrence)), h.rawStore.state.first()!!.merged)
        // The sounding session switches surface: the primary's tone, vibration, full notification.
        assertEquals(listOf(listOf("content://tone/alarm")), h.sound.starts)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.notify)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertEquals(0, h.finished)
        // The merging session plays nothing (its pre-claim vibration share ends with it).
        assertTrue(b.sound.starts.isEmpty())
        assertEquals(1, b.finished)

        advanceTimeBy(10.minutes); runCurrent()
        assertEquals(1, h.finished)
        assertNull(h.rawStore.state.first())
    }

    @Test fun unreadableMemberMergingIntoANotifyRingCountsAsRing() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("gone", "ring-B", h.clock, null, bOccurrence)))
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
    }

    @Test fun notifyFireMergingIntoANotifyRingKeepsItNotify() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), linkedAs(LinkMode.NOTIFY, "b").copy(minute = 31)))
        h.fire(occ = occurrence)
        runCurrent()
        val b = join(h, "b", bOccurrence)
        runCurrent()
        assertEquals(listOf(RingMember("b", bOccurrence)), h.rawStore.state.first()!!.merged)
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(0, h.vibration.starts)
        assertTrue(h.notifier.ringing.isEmpty())
        assertEquals(0, b.vibration.starts)
        assertEquals(1, b.finished)
    }

    @Test fun aNotifyMemberJoiningANotifyRingAlertsWithoutWaitingForTheRelabel() = runTest {
        // The relabel resolves every member through the cache (hanging here, up to its 2 s bound):
        // the joining alarm must be heard now, not then, and not only once a label changes.
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), linkedAs(LinkMode.NOTIFY, "b")))
        slotSession(h, h.controller, cache = StubMediaCache(hang = true)).start("a", occurrence, false, null)
        advanceTimeBy(RING_MEDIA_TIMEOUT.inWholeMilliseconds + 1); runCurrent()
        assertEquals(listOf("ring-1"), h.notifier.notify)
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("b", "ring-B", h.clock, null, bOccurrence)))
        runCurrent()
        assertEquals(listOf("ring-1"), h.notifier.notifyAlertsAgain)
        assertTrue(h.notifier.relabels.isEmpty())
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(0, h.vibration.starts)
    }

    @Test fun aNotifyMemberJoiningANotifyRingThatCantAlertRingsInFull() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), linkedAs(LinkMode.NOTIFY, "b")))
        // Only the alerting post reports it can't: the relabel's update still can, so it isn't what upgrades.
        val notifier = object : RingingNotifier by h.notifier {
            override fun showNotifyRinging(alarm: Alarm?, ringId: String, labels: List<String>, alertAgain: Boolean): Boolean =
                !alertAgain && h.notifier.showNotifyRinging(alarm, ringId, labels, alertAgain)
            override fun updateRingingLabel(alarm: Alarm?, ringId: String, labels: List<String>, notifySurface: Boolean): Boolean {
                h.notifier.updateRingingLabel(alarm, ringId, labels, notifySurface)
                return true
            }
        }
        RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
            h.controller, h.sound, h.vibration, notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { h.clock }, { "ring-1" }, firedLedger = h.fired, onFinished = { h.finished++ },
        ).start("a", occurrence, false, null)
        runCurrent()
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("b", "ring-B", h.clock, null, bOccurrence)))
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertEquals(0, h.finished)
    }

    @Test fun aJoiningMemberWhoseReadFailsStillAlertsBeforeTheUpgrade() = runTest {
        // Unknown counts as Ring (I1): the member watch upgrades, but that takes the media resolve,
        // and a read that failed only here must not leave the join unheard meanwhile.
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), linkedAs(LinkMode.NOTIFY, "b")))
        val repo = object : me.shawnsquire.tandem.data.AlarmRepository by h.repo {
            override suspend fun get(id: String): Alarm? = if (id == "b") throw IllegalStateException("db") else h.repo.get(id)
        }
        RingingSession(
            repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
            h.controller, h.sound, h.vibration, h.notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { h.clock }, { "ring-1" }, firedLedger = h.fired,
            linkedMediaCache = StubMediaCache(hang = true), onFinished = { h.finished++ },
        ).start("a", occurrence, false, null)
        advanceTimeBy(RING_MEDIA_TIMEOUT.inWholeMilliseconds + 1); runCurrent()
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("b", "ring-B", h.clock, null, bOccurrence)))
        runCurrent()
        assertEquals(listOf("ring-1"), h.notifier.notifyAlertsAgain)
        assertTrue(h.sound.starts.isEmpty())
        advanceTimeBy(RING_MEDIA_TIMEOUT.inWholeMilliseconds); runCurrent()
        assertEquals(1, h.sound.starts.size) // then in full
        assertEquals(listOf("ring-1"), h.notifier.ringing)
    }

    @Test fun aJoiningMemberWhoseReadHangsDoesNotDelayTheEndOfTheRing() = runTest {
        // The join's alert reads the member with no bound of its own: off the state observation, so
        // the ring's end still reaches finish() while that read hangs.
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), linkedAs(LinkMode.NOTIFY, "b")))
        val repo = object : me.shawnsquire.tandem.data.AlarmRepository by h.repo {
            override suspend fun get(id: String): Alarm? = if (id == "b") awaitCancellation() else h.repo.get(id)
        }
        RingingSession(
            repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
            h.controller, h.sound, h.vibration, h.notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { h.clock }, { "ring-1" }, firedLedger = h.fired, onFinished = { h.finished++ },
        ).start("a", occurrence, false, null)
        runCurrent()
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("b", "ring-B", h.clock, null, bOccurrence)))
        runCurrent()
        h.controller.dismiss("ring-1")
        runCurrent()
        assertNull(h.rawStore.state.first())
        assertEquals(1, h.finished)
    }

    @Test fun aPromotionInANotifyRingDoesNotAlertAgain() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), linkedAs(LinkMode.NOTIFY, "b")))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("b", "ring-B", h.clock, null, bOccurrence)))
        runCurrent()
        assertEquals(1, h.notifier.notifyAlertsAgain.size)
        h.controller.dismissOccurrence("a", occurrence, RingOrigin.REMOTE) // b promoted: nobody new to hear
        runCurrent()
        assertEquals(1, h.notifier.notifyAlertsAgain.size)
        assertEquals(Triple("ring-1", listOf("b"), true), h.notifier.relabels.last()) // the label still follows
        assertEquals(0, h.finished)
    }

    @Test fun notifyFireMergingIntoARingRingChangesNothing() = runTest {
        val h = harness(alarms = listOf(alarm("a"), linkedAs(LinkMode.NOTIFY, "b").copy(minute = 31)))
        h.fire(occ = occurrence)
        runCurrent()
        val b = join(h, "b", bOccurrence)
        runCurrent()
        assertEquals(listOf(RingMember("b", bOccurrence)), h.rawStore.state.first()!!.merged)
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertTrue(h.notifier.notify.isEmpty())
        assertEquals(0, h.sound.stops)
        // No early vibration for a Notify fire, and nothing played.
        assertTrue(b.sound.starts.isEmpty())
        assertEquals(0, b.vibration.starts)
        assertEquals(1, b.finished)
    }

    @Test fun notifyAlarmReadFailureRingsInFull() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY)))
        val failingRepo = object : me.shawnsquire.tandem.data.AlarmRepository by h.repo {
            override suspend fun get(id: String): Alarm? = throw IllegalStateException("db")
        }
        RingingSession(
            failingRepo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
            h.controller, h.sound, h.vibration, h.notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { clockNow }, { "ring-1" }, onFinished = {},
        ).start("a", occurrence, false, null)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertTrue(h.notifier.notify.isEmpty())
    }

    @Test fun notifyNotifierFailureRingsInFull() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY)))
        val throwing = object : RingingNotifier by h.notifier {
            override fun showNotifyRinging(alarm: Alarm?, ringId: String, labels: List<String>, alertAgain: Boolean): Boolean = throw IllegalStateException("notification")
        }
        RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
            h.controller, h.sound, h.vibration, throwing, backgroundScope,
            StandardTestDispatcher(testScheduler), { clockNow }, { "ring-1" }, onFinished = {},
        ).start("a", occurrence, false, null)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
    }

    @Test fun notifyClaimTimeoutRingsInFull() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY)))
        slotSession(h, GatedSlot(h.controller)).start("a", occurrence, false, null) // gate never opens
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.fallback)
        assertTrue(h.notifier.notify.isEmpty())
    }

    @Test fun notifyRingThatCantAlertRingsInFull() = runTest {
        // Notifications off, or the channel blocked or silenced: NotificationManager.notify() doesn't
        // throw, so only the notifier can say the post would go unseen and unheard.
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY)))
        h.notifier.notifyCanAlert = false
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertTrue(h.notifier.notify.isEmpty())
    }

    @Test fun notifyRingThatCantAlertVibratesBeforeTheMediaResolves() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY)))
        val events = mutableListOf<String>()
        h.vibration.events = events
        h.sound.events = events
        h.notifier.notifyCanAlert = false
        slotSession(h, h.controller, cache = StubMediaCache(hang = true)).start("a", occurrence, false, null)
        advanceTimeBy(RING_MEDIA_TIMEOUT.inWholeMilliseconds); runCurrent() // the Notify ring's own media resolve
        assertEquals(listOf("vibration"), events) // the surface can't alert; the full ring's media takes up to 500 ms more
        advanceTimeBy(RING_MEDIA_TIMEOUT.inWholeMilliseconds); runCurrent()
        assertEquals(listOf("vibration", "sound"), events)
    }

    @Test fun notifyRingRelabelledWhenItCantAlertAnyMoreRingsInFull() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), linkedAs(LinkMode.NOTIFY, "b")))
        // The join's alerting post still goes out; only the relabel's update finds the surface can't alert.
        val notifier = object : RingingNotifier by h.notifier {
            override fun updateRingingLabel(alarm: Alarm?, ringId: String, labels: List<String>, notifySurface: Boolean): Boolean {
                h.notifier.updateRingingLabel(alarm, ringId, labels, notifySurface)
                return !notifySurface
            }
        }
        RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
            h.controller, h.sound, h.vibration, notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { h.clock }, { "ring-1" }, firedLedger = h.fired, onFinished = { h.finished++ },
        ).start("a", occurrence, false, null)
        runCurrent()
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("b", "ring-B", h.clock, null, bOccurrence)))
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertEquals(listOf(listOf("a", "b")), h.notifier.ringingLabels)
    }

    @Test fun notifyFireWhoseClaimTimesOutVibratesBeforeTheMediaResolves() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY)))
        val events = mutableListOf<String>()
        h.vibration.events = events
        h.sound.events = events
        slotSession(h, GatedSlot(h.controller), cache = StubMediaCache(hang = true)).start("a", occurrence, false, null) // gate never opens
        advanceTimeBy(2_001); runCurrent()
        assertEquals(listOf("vibration"), events) // ringing in full is decided; the media takes up to 500 ms more
        advanceTimeBy(RING_MEDIA_TIMEOUT.inWholeMilliseconds); runCurrent()
        assertEquals(listOf("vibration", "sound"), events)
    }

    @Test fun aRingRelabelledToNoLabelNeverShowsTheDismissedPrimarysLabelAgain() = runTest {
        // a (labelled) is dismissed on the partner's phone; b, promoted, has no label. When c (Ring,
        // no label) then upgrades the ring, the full notification must not name a.
        val h = harness(
            alarms = listOf(linkedAs(LinkMode.NOTIFY), linkedAs(LinkMode.NOTIFY, "b").copy(label = ""), alarm("c").copy(label = "")),
        )
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("b", "ring-B", h.clock, null, bOccurrence)))
        runCurrent()
        h.controller.dismissOccurrence("a", occurrence, RingOrigin.REMOTE)
        runCurrent()
        assertEquals(Triple("ring-1", emptyList<String>(), true), h.notifier.relabels.last())
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("c", "ring-C", h.clock, null, bOccurrence.plusSeconds(60))))
        runCurrent()
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertEquals(listOf(emptyList<String>()), h.notifier.ringingLabels)
    }

    // ---- Linked media ----

    @Test fun linkedRingSoundsTheCacheCandidatesInOrder() = runTest {
        val media = RingMedia("Owner", listOf("/override/unreadable", "/owner/good", SYSTEM_ALARM_URI, "content://def"), emptyList())
        val h = harness(alarms = listOf(linkedAs(LinkMode.RING)), cache = StubMediaCache(media))
        h.fire(occ = occurrence)
        runCurrent()
        // The sound adapter walks these in order, so an unreadable override falls to the owner's media.
        assertEquals(listOf(media.soundCandidates), h.sound.starts)
        assertEquals(listOf(false), h.sound.appendsDefaults) // exactly the cache's list: it carries the system alarm Uri
        assertEquals(listOf(listOf("Owner")), h.notifier.ringingLabels)
    }

    @Test fun linkedRingFromTheDefaultCacheGetsTheSystemDefaultsAppended() = runTest {
        // Before AppContainer attaches the real cache: the alarm's tone and the joiner default only.
        val h = harness(alarms = listOf(linkedAs(LinkMode.RING)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf(listOf("content://tone/alarm")), h.sound.starts)
        assertEquals(listOf(true), h.sound.appendsDefaults)
    }

    @Test fun linkedRingWithNoReadableSystemAlarmUriGetsTheSystemDefaultsAppended() = runTest {
        val media = RingMedia("Owner", listOf("/owner/good"), emptyList())
        val h = harness(alarms = listOf(linkedAs(LinkMode.RING)), cache = StubMediaCache(media), systemAlarmUri = { error("settings") })
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf(listOf("/owner/good")), h.sound.starts)
        assertEquals(listOf(true), h.sound.appendsDefaults)
    }

    @Test fun neverReturningCacheStillStartsSoundFromTheFallbackWithinTheTimeout() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.RING)), cache = StubMediaCache(hang = true))
        h.fire(occ = occurrence)
        runCurrent()
        advanceTimeBy(RING_MEDIA_TIMEOUT.inWholeMilliseconds + 1); runCurrent()
        assertEquals(listOf(listOf("content://tone/alarm", SYSTEM_ALARM_URI)), h.sound.starts)
        assertEquals(listOf(false), h.sound.appendsDefaults)
        assertTrue(h.sound.startTimes.single() <= RING_MEDIA_TIMEOUT.inWholeMilliseconds + 3_000)
        assertEquals(1, h.vibration.starts)
    }

    @Test fun throwingCacheStillRingsFromTheFallback() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.RING)), cache = StubMediaCache(fail = true))
        h.fire(occ = occurrence)
        runCurrent()
        // The fallback builds the cache's order itself, the system alarm Uri included.
        assertEquals(listOf(listOf("content://tone/alarm", SYSTEM_ALARM_URI)), h.sound.starts)
        assertEquals(listOf(false), h.sound.appendsDefaults)
    }

    @Test fun ringingNotificationShowsTheOverrideLabelOfALinkedRing() = runTest {
        val a = linkedAs(LinkMode.RING).let { it.copy(link = it.link!!.copy(overrideLabel = "Gym")) }
        val h = harness(alarms = listOf(a))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf(listOf("Gym")), h.notifier.ringingLabels)
    }

    @Test fun notifyNotificationShowsTheResolvedLabel() = runTest {
        val media = RingMedia("Owner label", emptyList(), emptyList())
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY)), cache = StubMediaCache(media))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf(listOf("Owner label")), h.notifier.notifyLabels)
    }

    @Test fun upgradedNotifyRingSoundsTheCacheCandidatesAndLabelOfThePrimary() = runTest {
        val media = RingMedia("Owner label", listOf("/owner/tone", SYSTEM_ALARM_URI), emptyList())
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), alarm("b")), cache = StubMediaCache(media))
        h.fire(occ = occurrence)
        runCurrent()
        join(h, "b", bOccurrence)
        runCurrent()
        assertEquals(listOf(media.soundCandidates), h.sound.starts)
        assertEquals(listOf(false), h.sound.appendsDefaults)
        assertEquals(listOf(listOf("Owner label")), h.notifier.ringingLabels)
    }

    @Test fun notifyRingWhoseStateReadFailsUpgradesToRing() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), linkedAs(LinkMode.NOTIFY, "b")), storeWrap = { ThrowingAfterFirstStore(it) })
        h.fire(occ = occurrence)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        // A change makes the member watch's flow throw: unknown counts as Ring.
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("b", "ring-B", h.clock, null, bOccurrence)))
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(1, h.vibration.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
    }

    // ---- the notification names every member ----

    @Test fun aMergeRelabelsTheRingingNotificationWithoutANewPost() = runTest {
        val h = mergedRing()
        assertEquals(listOf(Triple("ring-1", listOf("a", "b"), false)), h.notifier.relabels)
        assertEquals(listOf("ring-1"), h.notifier.ringing) // the one full-screen post
        assertEquals(0, h.finished)
    }

    @Test fun aPromotionRelabelsTheRingingNotificationWithTheNewPrimary() = runTest {
        val h = mergedRing()
        h.controller.dismissOccurrence("a", occurrence, RingOrigin.REMOTE)
        runCurrent()
        assertEquals(Triple("ring-1", listOf("b"), false), h.notifier.relabels.last())
        assertEquals(2, h.notifier.relabels.size)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
    }

    @Test fun aSoloRingIsNeverRelabelled() = runTest {
        val h = harness()
        h.fire(occ = occurrence)
        runCurrent()
        assertTrue(h.notifier.relabels.isEmpty())
    }

    @Test fun aNotifyRingJoinedByANotifyMemberRelabelsTheNotifyNotification() = runTest {
        val h = harness(alarms = listOf(linkedAs(LinkMode.NOTIFY), linkedAs(LinkMode.NOTIFY, "b")))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("b", "ring-B", h.clock, null, bOccurrence)))
        runCurrent()
        assertEquals(listOf(Triple("ring-1", listOf("a", "b"), true)), h.notifier.relabels)
        assertTrue(h.notifier.ringing.isEmpty()) // still Notify: no full-screen post
    }

    @Test fun aMemberLeavingRelabelsTheNotification() = runTest {
        val h = mergedRing()
        h.controller.dismissOccurrence("b", bOccurrence, RingOrigin.REMOTE)
        runCurrent()
        assertEquals(Triple("ring-1", listOf("a"), false), h.notifier.relabels.last())
    }

    // ---- vibration patterns and the sound switch ----

    private fun outputs(
        id: String = "a",
        sound: Boolean = true,
        vibrate: Boolean = true,
        pattern: VibrationPattern = VibrationPattern.PULSE,
        ringtone: String = "content://tone/$id",
    ) = alarm(id, ringtone = ringtone).copy(soundOn = sound, vibrationOn = vibrate, vibration = pattern)

    @Test fun ringsWithTheAlarmsPattern() = runTest {
        val h = harness(alarms = listOf(outputs(pattern = VibrationPattern.HEARTBEAT)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf(VibrationPattern.HEARTBEAT), h.vibration.patterns)
        assertEquals(listOf(listOf("content://tone/a")), h.sound.starts)
    }

    @Test fun aJoinersOverrideWinsAtRingTime() = runTest {
        val copied = linkedAs(LinkMode.RING).copy(soundOn = true, vibration = VibrationPattern.HEARTBEAT)
        val row = copied.copy(link = copied.link!!.copy(overrideVibration = VibrationPattern.RAPID, overrideSoundOn = false))
        val h = harness(alarms = listOf(row))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf(VibrationPattern.RAPID), h.vibration.patterns)
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(listOf("ring-1"), h.notifier.ringing)
    }

    @Test fun vibrateOnlyPlaysNoSoundAtAll() = runTest {
        val h = harness(alarms = listOf(outputs(sound = false, pattern = VibrationPattern.SLOW_WAVE)))
        h.fire(occ = occurrence)
        runCurrent()
        // No candidates, no system defaults, no bundled tone: the sound port is never started.
        assertTrue(h.sound.starts.isEmpty())
        assertEquals(listOf(VibrationPattern.SLOW_WAVE), h.vibration.patterns)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertEquals(occurrence, h.fired.lastRang("a"))
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertEquals(1, h.finished) // auto-silence as usual
        assertTrue(h.sound.starts.isEmpty())
    }

    @Test fun vibrateOnlyWithoutAVibratorRingsWithSound() = runTest {
        val h = harness(alarms = listOf(outputs(sound = false, pattern = VibrationPattern.RAPID)))
        h.vibration.hasVibrator = false
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf(listOf("content://tone/a")), h.sound.starts)
        assertEquals(listOf(true), h.sound.appendsDefaults)
    }

    @Test fun vibrateOnlyWhoseVibrationThrowsRingsWithSound() = runTest {
        val h = harness(alarms = listOf(outputs(sound = false, pattern = VibrationPattern.ESCALATING)))
        h.vibration.failure = IllegalArgumentException("waveform")
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf(listOf("content://tone/a")), h.sound.starts)
        assertEquals(listOf("ring-1"), h.notifier.ringing)
        assertEquals(0, h.finished)
    }

    @Test fun bothOffRingsWithSoundAndTheAlarmsPattern() = runTest {
        val h = harness(alarms = listOf(outputs(sound = false, vibrate = false, pattern = VibrationPattern.HEARTBEAT)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf(listOf("content://tone/a")), h.sound.starts)
        assertEquals(listOf(VibrationPattern.HEARTBEAT), h.vibration.patterns)
    }

    @Test fun soundOnlyRingsWithoutVibration() = runTest {
        val h = harness(alarms = listOf(outputs(vibrate = false)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(0, h.vibration.starts)
    }

    @Test fun fallbackRingKeepsItsSoundAndPulseForAVibrateOnlyAlarm() = runTest {
        // An unexpected failure before sound (here the armed check) falls back to ringing in full.
        val h = harness(
            alarms = listOf(outputs(sound = false, pattern = VibrationPattern.SLOW_WAVE)),
            isArmed = { throw IllegalStateException("bug") },
        )
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(listOf(emptyList<String>()), h.sound.starts)
        assertEquals(listOf(VibrationPattern.PULSE), h.vibration.patterns)
        assertEquals(1, h.notifier.fallback.size) // the inert fallback notification
    }

    @Test fun failedAlarmReadRingsWithPulseAndSound() = runTest {
        val h = harness(alarms = listOf(outputs(sound = false, pattern = VibrationPattern.RAPID)))
        val failingRepo = object : me.shawnsquire.tandem.data.AlarmRepository by h.repo {
            override suspend fun get(id: String): Alarm? = throw IllegalStateException("db")
        }
        val session = RingingSession(
            failingRepo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
            h.controller, h.sound, h.vibration, h.notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { clockNow }, { "ring-9" }, onFinished = {},
        )
        session.start("a", occurrence, false, null)
        runCurrent()
        assertEquals(listOf(VibrationPattern.PULSE), h.vibration.patterns)
        assertEquals(1, h.sound.starts.size)
    }

    @Test fun earlyVibrationUsesTheAlarmsPattern() = runTest {
        val h = harness(alarms = listOf(outputs(pattern = VibrationPattern.ESCALATING)))
        val session = slotSession(h, GatedSlot(h.controller)) // gate never opens
        session.start("a", occurrence, false, null)
        runCurrent()
        assertEquals(listOf(VibrationPattern.ESCALATING), h.vibration.patterns) // before the claim
        assertTrue(h.sound.starts.isEmpty())
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(listOf(VibrationPattern.ESCALATING), h.vibration.patterns) // not started again
    }

    @Test fun vibrationOffStartsNoEarlyVibrationButRingsWithSound() = runTest {
        val h = harness(alarms = listOf(outputs(vibrate = false)))
        val session = slotSession(h, GatedSlot(h.controller)) // gate never opens
        session.start("a", occurrence, false, null)
        runCurrent()
        assertEquals(0, h.vibration.starts)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(0, h.vibration.starts)
    }

    @Test fun snoozeResumeReadsTheAlarmsSettingsAgain() = runTest {
        val h = harness(alarms = listOf(outputs(sound = false, pattern = VibrationPattern.RAPID)))
        h.fire(occ = occurrence)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        h.controller.snooze(RingOrigin.LOCAL, snoozeEnd)
        runCurrent()
        val entry = h.ledger.all().single()
        h.repo.upsert(outputs(sound = true, pattern = VibrationPattern.HEARTBEAT)) // edited while snoozed

        h.clock = snoozeEnd
        slotSession(h, h.controller).start("a", snoozeEnd, true, entry.ringId)
        runCurrent()
        assertEquals(listOf(listOf("content://tone/a")), h.sound.starts)
        assertEquals(listOf(VibrationPattern.RAPID, VibrationPattern.HEARTBEAT), h.vibration.patterns)
    }

    @Test fun unsilenceRestartsAVibrateOnlyRingWithoutSound() = runTest {
        val a = outputs("a", sound = false, pattern = VibrationPattern.SLOW_WAVE)
        val b = outputs("b", sound = false, pattern = VibrationPattern.RAPID)
        val h = harness(alarms = listOf(a, b))
        val bAt = occurrence.plusSeconds(600)
        // b's claim lands on the lock just before auto-silence's dismiss, so the ring sounds on for it.
        val slot = AutoSilenceSlot(h.controller) { ringId, expected ->
            h.controller.claimFire(RingState("b", "ring-b", h.clock, null, bAt))
            h.controller.autoSilence(ringId, expected)
        }
        slotSession(h, slot).start("a", occurrence, false, null)
        runCurrent()
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertEquals(0, h.finished)
        assertTrue(h.vibration.stops >= 1) // it did go still first
        // Back with the same outputs (the primary's pattern; b's vibrate-only adds no sound).
        assertEquals(listOf(VibrationPattern.SLOW_WAVE, VibrationPattern.SLOW_WAVE), h.vibration.patterns)
        assertTrue(h.sound.starts.isEmpty())
    }

    // ---- merged rings ----

    @Test fun soundMemberMergingIntoAVibrateOnlyRingStartsThatMembersSound() = runTest {
        val h = harness(alarms = listOf(outputs("a", sound = false, pattern = VibrationPattern.HEARTBEAT), outputs("b")))
        h.fire(occ = occurrence)
        runCurrent()
        assertTrue(h.sound.starts.isEmpty())
        val b = join(h, "b", bOccurrence)
        runCurrent()
        assertEquals(listOf(RingMember("b", bOccurrence)), h.rawStore.state.first()!!.merged)
        // The ring's own session starts b's sound, resolved as b's own ring would.
        assertEquals(listOf(listOf("content://tone/b")), h.sound.starts)
        assertEquals(listOf(true), h.sound.appendsDefaults)
        assertEquals(listOf(VibrationPattern.HEARTBEAT), h.vibration.patterns) // the primary's, not restarted
        assertTrue(b.sound.starts.isEmpty())
        assertEquals(1, b.finished)
    }

    @Test fun vibrateOnlyMemberMergingIntoASoundRingKeepsTheSoundAndAddsItsVibration() = runTest {
        val h = harness(alarms = listOf(outputs("a", vibrate = false), outputs("b", sound = false, pattern = VibrationPattern.RAPID)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(0, h.vibration.starts)
        join(h, "b", bOccurrence)
        runCurrent()
        assertEquals(listOf(listOf("content://tone/a")), h.sound.starts)
        assertEquals(0, h.sound.stops)
        // a's vibration is off, so the first member with vibration on gives the pattern.
        assertEquals(listOf(VibrationPattern.RAPID), h.vibration.patterns)
    }

    @Test fun vibratingMemberMergingIntoAVibrationOffRingStartsVibration() = runTest {
        val h = harness(alarms = listOf(outputs("a", vibrate = false), outputs("b", pattern = VibrationPattern.SLOW_WAVE)))
        h.fire(occ = occurrence)
        runCurrent()
        join(h, "b", bOccurrence)
        runCurrent()
        assertEquals(listOf(VibrationPattern.SLOW_WAVE), h.vibration.patterns)
        assertEquals(1, h.sound.starts.size) // a's sound, not started again
    }

    @Test fun memberThatLeavesTheResultAloneChangesNothing() = runTest {
        val h = harness(alarms = listOf(outputs("a", pattern = VibrationPattern.HEARTBEAT), outputs("b", pattern = VibrationPattern.ESCALATING)))
        h.fire(occ = occurrence)
        runCurrent()
        join(h, "b", bOccurrence)
        runCurrent()
        assertEquals(listOf(VibrationPattern.HEARTBEAT), h.vibration.patterns) // no re-vibrate
        assertEquals(1, h.sound.starts.size)
        assertEquals(0, h.vibration.stops)
    }

    @Test fun unreadableMemberMergingIntoAVibrateOnlyRingStartsSound() = runTest {
        val h = harness(alarms = listOf(outputs("a", sound = false, pattern = VibrationPattern.RAPID)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(SlotClaim.Merged, h.controller.claimFire(RingState("gone", "ring-B", h.clock, null, bOccurrence)))
        runCurrent()
        // Unknown counts as ringing in full (I1): sound, from that member's (default) candidates.
        assertEquals(1, h.sound.starts.size)
        assertEquals(listOf(VibrationPattern.RAPID), h.vibration.patterns)
    }

    @Test fun notifyRingGoingFullAppliesTheMergedOutputs() = runTest {
        val notify = linkedAs(LinkMode.NOTIFY).copy(soundOn = false, vibration = VibrationPattern.HEARTBEAT)
        val h = harness(alarms = listOf(notify, outputs("b", sound = false, pattern = VibrationPattern.RAPID)))
        h.fire(occ = occurrence)
        runCurrent()
        assertEquals(0, h.vibration.starts)
        join(h, "b", bOccurrence)
        runCurrent()
        assertEquals(listOf("ring-1"), h.notifier.ringing) // upgraded by the Ring member
        // Both are vibrate-only: the primary's pattern, and no sound.
        assertEquals(listOf(VibrationPattern.HEARTBEAT), h.vibration.patterns)
        assertTrue(h.sound.starts.isEmpty())
    }

    @Test fun aVibrationStartThatThrowsTriesPulseAndStillRingsWithSound() = runTest {
        val h = harness(alarms = listOf(outputs(sound = false, pattern = VibrationPattern.ESCALATING)))
        val failing = object : AlarmVibration {
            val patterns = mutableListOf<VibrationPattern>()
            override fun start(pattern: VibrationPattern): Boolean {
                patterns += pattern
                if (pattern != VibrationPattern.PULSE) throw IllegalArgumentException("waveform")
                return true
            }
            override fun stop() {}
        }
        RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler, h.controller,
            h.sound, failing, h.notifier, backgroundScope, StandardTestDispatcher(testScheduler), { clockNow },
            { "ring-1" }, onFinished = {},
        ).start("a", occurrence, false, null)
        runCurrent()
        // Early, then again in ring(): each failed start falls back to Pulse (a no-op on the arbiter the second time).
        assertEquals(listOf(VibrationPattern.ESCALATING, VibrationPattern.PULSE, VibrationPattern.ESCALATING, VibrationPattern.PULSE), failing.patterns)
        assertEquals(1, h.sound.starts.size)
    }

    private suspend fun TestScope.splitOut(h: Harness) {
        h.fire(occ = occurrence)
        runCurrent()
        join(h, "b", bOccurrence)
        runCurrent()
        h.controller.dismissOccurrence("b", bOccurrence, RingOrigin.REMOTE)
        runCurrent()
        assertEquals(RingState("a", "ring-1", clockNow, null, occurrence), h.rawStore.state.first())
        assertEquals(0, h.finished)
    }

    @Test fun theLastSoundMemberSplittingOutQuietsAVibrateOnlyRing() = runTest {
        val h = harness(alarms = listOf(outputs("a", sound = false, pattern = VibrationPattern.HEARTBEAT), outputs("b")))
        splitOut(h)
        assertEquals(listOf(listOf("content://tone/b")), h.sound.starts)
        assertEquals(1, h.sound.stops)
        assertEquals(listOf(VibrationPattern.HEARTBEAT), h.vibration.patterns)
        assertEquals(0, h.vibration.stops)
    }

    @Test fun aSplitOutNeverQuietsTheSoundWhileTheVibrationCantRun() = runTest {
        val h = harness(alarms = listOf(outputs("a", sound = false, pattern = VibrationPattern.HEARTBEAT), outputs("b")))
        h.vibration.hasVibrator = false
        splitOut(h)
        assertEquals(1, h.sound.starts.size) // a's own, since nothing vibrates (I1)
        assertEquals(0, h.sound.stops)
    }

    @Test fun theLastVibratingMemberSplittingOutStopsTheVibration() = runTest {
        val h = harness(alarms = listOf(outputs("a", vibrate = false), outputs("b", pattern = VibrationPattern.RAPID)))
        splitOut(h)
        assertEquals(listOf(VibrationPattern.RAPID), h.vibration.patterns)
        assertEquals(1, h.vibration.stops)
        assertEquals(1, h.sound.starts.size)
        assertEquals(0, h.sound.stops)
    }

    @Test fun aPromotedMembersPatternTakesOver() = runTest {
        val h = harness(alarms = listOf(outputs("a"), outputs("b", pattern = VibrationPattern.ESCALATING)))
        h.fire(occ = occurrence)
        runCurrent()
        join(h, "b", bOccurrence)
        runCurrent()
        assertEquals(listOf(VibrationPattern.PULSE), h.vibration.patterns) // the primary's while it rings
        h.controller.dismissOccurrence("a", occurrence, RingOrigin.REMOTE) // b promoted
        runCurrent()
        assertEquals(listOf(VibrationPattern.PULSE, VibrationPattern.ESCALATING), h.vibration.patterns)
        assertEquals(1, h.sound.starts.size) // b has sound too: a's tone plays on
        assertEquals(0, h.sound.stops)
    }
}
