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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.sync.PreFireCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RecordingSound(private val clock: () -> Long) : AlarmSound {
    val starts = mutableListOf<List<String>>()
    val startTimes = mutableListOf<Long>()
    var stops = 0
    var events: MutableList<String>? = null
    override fun start(candidates: List<String>) { starts += candidates; startTimes += clock(); events?.add("sound") }
    override fun stop() { stops++ }
}

class RecordingVibration : AlarmVibration {
    var starts = 0
    var stops = 0
    var events: MutableList<String>? = null
    override fun start() { starts++; events?.add("vibration") }
    override fun stop() { stops++ }
}

private class LambdaPreFire(val block: suspend () -> Boolean) : PreFireCheck {
    override suspend fun pullAndApply(timeout: Duration): Boolean = block()
}

private class ThrowingSetStore(private val inner: FakeRingStateStore) : RingStateStore {
    override val state: Flow<RingState?> = inner.state
    override suspend fun set(state: RingState?) { throw IllegalStateException("disk full") }
}

private class ThrowingReadStore(inner: FakeRingStateStore) : RingStateStore by inner {
    override val state: Flow<RingState?> = flow { throw IllegalStateException("read") }
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
    override suspend fun claimFire(newRing: RingState, stillDue: suspend () -> Boolean): Boolean {
        events?.add("claim"); gate.await(); return inner.claimFire(newRing, stillDue)
    }
    override suspend fun claimSnoozeResume(ringId: String, alarmId: String): Boolean {
        gate.await()
        return inner.claimSnoozeResume(ringId, alarmId)
    }
    override suspend fun clearStale(isLive: (String) -> Boolean, reschedule: Boolean) = inner.clearStale(isLive, reschedule)
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
        val controller = RingingControllerImpl(repo, store, settings, scheduler, armer, notifier, ledger) { clock }
        private val dispatcher = ioDispatcher ?: StandardTestDispatcher(scope.testScheduler)
        val session = RingingSession(
            repo, store, settings, preFire, scheduler, controller, controller, sound, vibration, notifier,
            scope.backgroundScope, dispatcher, { clock }, { ids.next() },
            firedLedger = fired, liveRings = live,
            onFinished = { finished++ },
        )
        fun fire(id: String = "a", occ: Instant) = session.start(id, occ, false, null)
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
    }

    private fun TestScope.harness(
        alarms: List<Alarm> = listOf(alarm()),
        state: RingState? = null,
        preFire: PreFireCheck = LambdaPreFire { true },
        ioDispatcher: CoroutineDispatcher? = null,
        settings: SettingsRepository = FakeSettingsRepository(),
        storeWrap: ((FakeRingStateStore) -> RingStateStore)? = null,
    ) = Harness(this, alarms, state, preFire, clockNow, ioDispatcher, settings, storeWrap)

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
            h.controller, h.controller, h.sound, h.vibration, h.notifier, backgroundScope,
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
            scheduler ?: h.scheduler, h.controller, h.controller, h.sound, h.vibration, notifier ?: h.notifier,
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
            override fun showRinging(alarm: Alarm?, ringId: String) { throw NoClassDefFoundError("x") }
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
            h.scheduler, h.controller, h.controller, h.sound, h.vibration, h.notifier,
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
        val ctl = object : RingingController {
            override suspend fun dismiss(ringId: String) { throw CancellationException("foreign") }
            override suspend fun snooze() = Unit
        }
        val session = RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true },
            h.scheduler, ctl, h.controller, h.sound, h.vibration, h.notifier, backgroundScope,
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
        assertEquals(RingState("a", "ring-1", clockNow, null), h.rawStore.state.first())
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

    @Test fun newFireSupersedesActiveRing() = runTest {
        val a = alarm("a")
        val b = alarm("b", days = setOf(java.time.DayOfWeek.MONDAY))
        val shared = Harness(this, listOf(a, b), null, LambdaPreFire { true }, clockNow)
        shared.fire("a", occurrence)
        runCurrent()
        assertEquals("ring-1", shared.rawStore.state.first()!!.ringId)

        // Second session (its own adapters, as the service builds per start) on the same stores.
        val bSound = RecordingSound { currentTime }
        var bFinished = 0
        val sessionB = RingingSession(
            shared.repo, shared.store, FakeSettingsRepository(), LambdaPreFire { true },
            shared.scheduler, shared.controller, shared.controller, bSound, RecordingVibration(), shared.notifier,
            backgroundScope, StandardTestDispatcher(testScheduler), { clockNow },
            { "ring-2" }, onFinished = { bFinished++ },
        )
        sessionB.start("b", occurrence, false, null)
        runCurrent()

        assertFalse(shared.repo.get("a")!!.enabled) // A (one-shot) counted as dismissed
        assertEquals(1, shared.finished)
        assertTrue(shared.sound.stops >= 1)
        assertEquals(RingState("b", "ring-2", clockNow, null), shared.rawStore.state.first())
        assertEquals(1, bSound.starts.size)
        assertEquals(0, bFinished)
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
            h.controller, h.controller, h.sound, h.vibration, h.notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { clockNow }, { "ring-9" }, onFinished = {},
        )
        session.start("a", occurrence, false, null)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
    }

    // ---- D1: snoozes survive a supersede; atomic slot claims ----

    private fun TestScope.secondSession(
        h: Harness,
        sound: RecordingSound,
        ringId: String,
        onFinished: () -> Unit,
    ) = RingingSession(
        h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
        h.controller, h.controller, sound, RecordingVibration(), h.notifier, backgroundScope,
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

    @Test fun parkedSnoozeFireSupersedesSoundingRing() = runTest {
        val ringB = RingState("b", "ring-B", clockNow, null)
        val h = harness(alarms = listOf(alarm("a"), alarm("b")), state = ringB)
        h.ledger.put("ring-X", "a", clockNow)
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        assertEquals(RingState("a", "ring-X", clockNow, null), h.rawStore.state.first())
        assertFalse(h.repo.get("b")!!.enabled)
    }

    @Test fun twoSameMinuteFiresLeaveExactlyOneRingSounding() = runTest {
        val h = harness(alarms = listOf(alarm("a"), alarm("b")), storeWrap = { YieldingRingStateStore(it) })
        val bSound = RecordingSound { currentTime }
        var bFinished = 0
        val sessionB = secondSession(h, bSound, "ring-B") { bFinished++ }
        h.fire("a", occurrence)
        sessionB.start("b", occurrence, false, null)
        runCurrent()

        val sounding = (h.sound.starts.size - h.sound.stops) + (bSound.starts.size - bSound.stops)
        assertEquals(1, sounding)
        assertEquals(1, h.finished + bFinished)
        val winner = if (h.finished == 0) "ring-1" else "ring-B"
        assertEquals(winner, h.rawStore.state.first()!!.ringId)
        // The loser (a one-shot) was dismissed, not silently overwritten.
        val loserAlarm = if (winner == "ring-1") "b" else "a"
        assertFalse(h.repo.get(loserAlarm)!!.enabled)
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
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler, h.controller, h.controller,
            object : AlarmSound { override fun start(candidates: List<String>) = throw IllegalStateException("audio"); override fun stop() {} },
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
        controller: RingingController = h.controller,
        ringId: String = "ring-1",
    ) = RingingSession(
        h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true }, h.scheduler,
        controller, slot, h.sound, h.vibration, h.notifier, backgroundScope,
        StandardTestDispatcher(testScheduler), { h.clock }, { ringId },
        firedLedger = h.fired, liveRings = h.live, onFinished = { h.finished++ },
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

        slotSession(h, ctl, controller = ctl).start("a", occurrence, false, null)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, h.sound.starts.size) // rang on timeout
        assertEquals(listOf("ring-1"), h.notifier.fallback)
        assertNull(h.rawStore.state.first())

        stuck.gate.complete(Unit) // lock released: the claim completes late and writes A
        runCurrent()
        assertTrue(holder.isCompleted)
        assertEquals(RingState("a", "ring-1", clockNow, null), h.rawStore.state.first())
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
            override suspend fun claimFire(newRing: RingState, stillDue: suspend () -> Boolean): Boolean {
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
            override suspend fun claimFire(newRing: RingState, stillDue: suspend () -> Boolean): Boolean {
                gate.await()
                return false
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
        val hanging = object : RingingController {
            override suspend fun dismiss(ringId: String) { dismissed += ringId; awaitCancellation() }
            override suspend fun snooze() = Unit
        }
        slotSession(h, h.controller, controller = hanging).start("a", occurrence, false, null)
        runCurrent()
        assertEquals(1, h.sound.starts.size)
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertTrue(h.sound.stops >= 1) // silent at once
        assertTrue(h.vibration.stops >= 1)
        assertEquals(listOf("ring-1"), dismissed) // asked after the sound stopped
        assertEquals(0, h.finished) // the service stays up while the dismiss gets its chance
        advanceTimeBy(5.seconds); runCurrent()
        assertEquals(1, h.finished) // bounded: a hung dismiss cannot keep the session forever
    }

    @Test fun autoSilenceFinishesOnlyAfterTheDismissCompletes() = runTest {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        val gated = object : RingingController {
            override suspend fun dismiss(ringId: String) { gate.await(); h.controller.dismiss(ringId) }
            override suspend fun snooze() = Unit
        }
        slotSession(h, h.controller, controller = gated).start("a", occurrence, false, null)
        runCurrent()
        advanceTimeBy(10.minutes + 1.seconds); runCurrent()
        assertTrue(h.sound.stops >= 1)
        assertEquals(0, h.finished)
        gate.complete(Unit); runCurrent()
        assertNull(h.rawStore.state.first())
        assertFalse(h.repo.get("a")!!.enabled) // a real dismiss: one-shot disabled
        assertEquals(1, h.finished)
    }

    @Test fun lateClaimFireWriteBehindStuckLockIsClearedByAutoSilence() = runTest {
        // The claim waits behind a stuck dismiss of B; auto-silence's dismiss queues behind the claim.
        val ringB = RingState("b", "ring-B", clockNow, null)
        val h = harness(alarms = listOf(alarm("a"), alarm("b")), state = ringB)
        val stuck = GatedScheduler()
        val ctl = RingingControllerImpl(h.repo, h.store, FakeSettingsRepository(), stuck, h.armer, h.notifier, h.ledger) { h.clock }
        backgroundScope.launch { ctl.dismiss("ring-B") }
        runCurrent()

        slotSession(h, ctl, controller = ctl).start("a", occurrence, false, null)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(listOf("ring-1"), h.notifier.fallback) // claim timed out, ringing inertly
        advanceTimeBy(10.minutes + 5.seconds); runCurrent()
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
            h.controller, h.controller, replaySound, RecordingVibration(), h.notifier, backgroundScope,
            StandardTestDispatcher(testScheduler), { clockNow }, { "ring-R" },
            firedLedger = h.fired, liveRings = h.live, onFinished = { replayFinished++ },
        ).start("a", occurrence, false, null)
        runCurrent()
        assertTrue(replaySound.starts.isEmpty())
        assertEquals(1, replayFinished)
    }
}
