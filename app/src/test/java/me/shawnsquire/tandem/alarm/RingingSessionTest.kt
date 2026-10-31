package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CancellationException
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
    override fun start(candidates: List<String>) { starts += candidates; startTimes += clock() }
    override fun stop() { stops++ }
}

class RecordingVibration : AlarmVibration {
    var starts = 0
    var stops = 0
    override fun start() { starts++ }
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
    ) {
        val repo = FakeAlarmRepository(alarms)
        val rawStore = FakeRingStateStore(state)
        val store: RingStateStore = storeWrap?.invoke(rawStore) ?: rawStore
        val scheduler = FakeAlarmScheduler()
        val notifier = RecordingNotifier()
        val sound = RecordingSound { scope.testScheduler.currentTime }
        val vibration = RecordingVibration()
        var finished = 0
        val controller = RingingControllerImpl(repo, store, settings, scheduler, notifier) { now }
        private val dispatcher = ioDispatcher ?: StandardTestDispatcher(scope.testScheduler)
        val session = RingingSession(
            repo, store, settings, preFire, scheduler, controller, sound, vibration, notifier,
            scope.backgroundScope, dispatcher, { now }, { ids.next() },
            onFinished = { finished++ },
        )
        fun fire(id: String = "a", occ: Instant) = session.start(id, occ, false, null)
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
        assertEquals(listOf("ring-X"), h.notifier.ringing)
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
        val ctl = object : RingingController {
            override suspend fun dismiss(ringId: String) { throw CancellationException("foreign") }
            override suspend fun snooze() = Unit
        }
        val session = RingingSession(
            h.repo, h.store, FakeSettingsRepository(), LambdaPreFire { true },
            h.scheduler, ctl, h.sound, h.vibration, h.notifier, backgroundScope,
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

    @Test fun snoozeResumeCancelsSnoozedNotification() = runTest {
        val h = harness(state = RingState("a", "ring-X", clockNow.minusSeconds(600), clockNow))
        h.session.start("a", clockNow, true, "ring-X")
        runCurrent()
        assertEquals(1, h.notifier.cancelSnoozedCount)
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
            shared.scheduler, shared.controller, bSound, RecordingVibration(), shared.notifier,
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
}
