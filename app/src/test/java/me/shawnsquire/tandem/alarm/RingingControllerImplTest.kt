package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant

class RecordingNotifier : RingingNotifier {
    val ringing = mutableListOf<String>()
    val snoozed = mutableListOf<Pair<String, Instant>>()
    val cancelledSnoozed = mutableListOf<String>()
    val cancelSnoozedCount get() = cancelledSnoozed.size
    override fun showRinging(alarm: Alarm?, ringId: String) { ringing += ringId }
    val fallback = mutableListOf<String>()
    override fun showFallbackRinging(ringId: String) { fallback += ringId }
    override fun showSnoozed(ringId: String, until: Instant) { snoozed += ringId to until }
    override fun cancelSnoozed(ringId: String) { cancelledSnoozed += ringId }
}

/** Records arms and cancels; [armFailure] makes arming throw. */
class RecordingArmer(private val events: MutableList<String> = mutableListOf()) : SnoozeArmer {
    val armed = mutableListOf<Triple<String, String, Instant>>()
    val cancelled = mutableListOf<String>()
    var armFailure: Throwable? = null
    override fun armSnooze(alarmId: String, ringId: String, at: Instant) {
        events += "arm"
        armFailure?.let { throw it }
        armed += Triple(alarmId, ringId, at)
    }
    override fun cancelSnooze(ringId: String) { cancelled += ringId }
}

class RingingControllerImplTest {
    private val now = Instant.parse("2026-03-02T10:00:00Z")
    private fun alarm(id: String, days: Set<DayOfWeek>) = Alarm(
        id, id, 6, 30, days, true, null, null, null,
    )

    private class H(alarms: List<Alarm>, state: RingState?) {
        val repo = FakeAlarmRepository(alarms)
        val store = FakeRingStateStore(state)
        val settings = FakeSettingsRepository()
        val scheduler = FakeAlarmScheduler()
        val notifier = RecordingNotifier()
        val events = mutableListOf<String>()
        val armer = RecordingArmer(events)
        val armed get() = armer.armed
        var armFailure: Throwable?
            get() = armer.armFailure
            set(v) { armer.armFailure = v }
        val ledger = InMemorySnoozeLedger()
    }

    private fun H.controller() = RingingControllerImpl(repo, store, settings, scheduler, armer, notifier, ledger) { now }

    private val ringing = RingState("a", "ring-1", Instant.parse("2026-03-02T09:59:00Z"), null)

    @Test fun dismissWithWrongRingIdIsIgnored() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        h.controller().dismiss("other")
        assertEquals(ringing, h.store.state.first())
        assertEquals(0, h.scheduler.rescheduleCount)
        assertTrue(h.repo.get("a")!!.enabled)
    }

    @Test fun dismissWithNothingRingingIsIgnored() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        h.controller().dismiss("ring-1")
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    @Test fun dismissOneShotDisablesIt() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        h.controller().dismiss("ring-1")
        assertFalse(h.repo.get("a")!!.enabled)
        assertNull(h.store.state.first())
        assertEquals(1, h.scheduler.rescheduleCount)
        assertEquals(1, h.notifier.cancelSnoozedCount)
    }

    @Test fun dismissRepeatingStaysEnabled() = runTest {
        val h = H(listOf(alarm("a", setOf(DayOfWeek.MONDAY))), ringing)
        h.controller().dismiss("ring-1")
        assertTrue(h.repo.get("a")!!.enabled)
        assertNull(h.store.state.first())
        assertEquals(1, h.scheduler.rescheduleCount)
    }

    @Test fun snoozeSetsSnoozedUntil() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        h.settings.setSnoozeMinutes(7)
        h.controller().snooze()
        val until = Instant.parse("2026-03-02T10:07:00Z")
        assertEquals(ringing.copy(snoozedUntil = until), h.store.state.first())
        assertEquals(1, h.scheduler.rescheduleCount)
        assertEquals(listOf("ring-1" to until), h.notifier.snoozed)
    }

    @Test fun snoozeWithNothingRingingIsNoOp() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        h.controller().snooze()
        assertNull(h.store.state.first())
        assertEquals(0, h.scheduler.rescheduleCount)
        assertTrue(h.notifier.snoozed.isEmpty())
    }

    @Test fun snoozeWhileAlreadySnoozedIsNoOp() = runTest {
        val snoozed = ringing.copy(snoozedUntil = now)
        val h = H(listOf(alarm("a", emptySet())), snoozed)
        h.controller().snooze()
        assertEquals(snoozed, h.store.state.first())
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    @Test fun dismissWhileSnoozedClearsStateAndReschedules() = runTest {
        val h = H(listOf(alarm("a", setOf(DayOfWeek.MONDAY))), ringing.copy(snoozedUntil = now))
        h.controller().dismiss("ring-1")
        assertNull(h.store.state.first())
        assertEquals(1, h.scheduler.rescheduleCount)
        assertEquals(1, h.notifier.cancelSnoozedCount)
    }

    @Test fun snoozeArmFailureLeavesRingingAndPropagates() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        h.armFailure = IllegalStateException("alarm manager")
        var thrown = false
        try { h.controller().snooze() } catch (e: IllegalStateException) { thrown = true }
        assertTrue(thrown)
        assertEquals(ringing, h.store.state.first())
        assertTrue(h.notifier.snoozed.isEmpty())
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    @Test fun snoozeArmsBeforeWritingState() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val recording = object : RingStateStore by h.store {
            override suspend fun set(state: RingState?) {
                h.events += "set"
                h.store.set(state)
            }
        }
        val c = RingingControllerImpl(h.repo, recording, h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { now }
        h.settings.setSnoozeMinutes(7)
        c.snooze()
        val until = Instant.parse("2026-03-02T10:07:00Z")
        assertEquals(listOf("arm", "set"), h.events)
        assertEquals(listOf(Triple("a", "ring-1", until)), h.armed)
    }

    @Test fun snoozeRescheduleFailureAfterArmingStaysSnoozed() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val bad = object : AlarmScheduler {
            override suspend fun rescheduleAll() { throw IllegalStateException("alarm manager") }
        }
        val c = RingingControllerImpl(h.repo, h.store, h.settings, bad, h.armer, h.notifier, h.ledger) { now }
        c.snooze()
        assertTrue(h.store.state.first()!!.snoozedUntil != null)
        assertEquals(1, h.notifier.snoozed.size)
    }

    @Test fun dismissRunsRemainingStepsWhenAlarmWriteFails() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val failingRepo = object : me.shawnsquire.tandem.data.AlarmRepository by h.repo {
            override suspend fun setEnabled(id: String, enabled: Boolean) { throw IllegalStateException("db") }
        }
        val c = RingingControllerImpl(failingRepo, h.store, h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { now }
        var thrown = false
        try { c.dismiss("ring-1") } catch (e: IllegalStateException) { thrown = true }
        assertTrue(thrown)
        assertNull(h.store.state.first())
        assertEquals(1, h.notifier.cancelSnoozedCount)
        assertEquals(1, h.scheduler.rescheduleCount)
    }

    /** Scheduler that suspends once, so a cancelled caller would lose the call. */
    private class YieldingScheduler : AlarmScheduler {
        var completed = 0
        override suspend fun rescheduleAll() { yield(); completed++ }
    }

    @Test fun snoozeCompletesWhenCallerIsCancelledAfterStateWrite() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val sched = YieldingScheduler()
        lateinit var job: Job
        val cancelling = object : RingStateStore by h.store {
            override suspend fun set(state: RingState?) {
                job.cancel()
                h.store.set(state)
            }
        }
        val c = RingingControllerImpl(h.repo, cancelling, h.settings, sched, h.armer, h.notifier, h.ledger) { now }
        job = launch { c.snooze() }
        job.join()
        assertEquals(1, sched.completed)
        assertEquals(1, h.notifier.snoozed.size)
        assertTrue(h.store.state.first()!!.snoozedUntil != null)
    }

    @Test fun dismissCompletesWhenCallerIsCancelledAfterStateWrite() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val sched = YieldingScheduler()
        lateinit var job: Job
        val cancelling = object : RingStateStore by h.store {
            override suspend fun set(state: RingState?) {
                job.cancel()
                h.store.set(state)
            }
        }
        val yieldingRepo = object : AlarmRepository by h.repo {
            override suspend fun setEnabled(id: String, enabled: Boolean) {
                yield()
                h.repo.setEnabled(id, enabled)
            }
        }
        val c = RingingControllerImpl(yieldingRepo, cancelling, h.settings, sched, h.armer, h.notifier, h.ledger) { now }
        job = launch { c.dismiss("ring-1") }
        job.join()
        assertFalse(h.repo.get("a")!!.enabled)
        assertEquals(1, sched.completed)
        assertEquals(1, h.notifier.cancelSnoozedCount)
    }

    // ---- D1: snoozes survive a supersede; the ring slot is claimed atomically ----

    private val sevenTen = Instant.parse("2026-03-02T07:10:00Z")
    private val sevenOhFive = Instant.parse("2026-03-02T07:05:00Z")
    private val ringA = RingState("a", "ring-A", Instant.parse("2026-03-02T07:00:00Z"), sevenTen)
    private val ringB = RingState("b", "ring-B", sevenOhFive, null)

    /** A (one-shot) snoozed until 7:10, with its ledger entry, as [RingingControllerImpl.snooze] leaves it. */
    private fun snoozedA(): H =
        H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringA).also {
            it.ledger.put("ring-A", "a", sevenTen)
        }

    @Test fun managerScenario_snoozeSurvivesSupersedeAndStop() = runTest {
        val h = snoozedA()
        var clock = sevenOhFive
        val c = RingingControllerImpl(h.repo, h.store, h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { clock }

        c.claimFire(ringB) // B fires at 7:05 while A is snoozed
        assertEquals(ringB, h.store.state.first())
        c.dismiss("ring-B") // the user stops B
        assertNull(h.store.state.first())
        assertFalse(h.repo.get("b")!!.enabled)

        // A's snooze is untouched: PendingIntent not cancelled, ledger entry kept, notification kept.
        assertFalse("ring-A" in h.armer.cancelled)
        assertEquals(SnoozeEntry("ring-A", "a", sevenTen), h.ledger.get("ring-A"))
        assertFalse("ring-A" in h.notifier.cancelledSnoozed)
        assertTrue(h.repo.get("a")!!.enabled)

        clock = sevenTen
        assertTrue(c.claimSnoozeResume("ring-A", "a"))
        assertEquals(RingState("a", "ring-A", sevenTen, null), h.store.state.first())
        assertTrue(h.repo.get("a")!!.enabled)
        assertNull(h.ledger.get("ring-A"))
        assertTrue("ring-A" in h.notifier.cancelledSnoozed)
    }

    @Test fun claimFireParksSnoozedRing() = runTest {
        val h = snoozedA()
        h.controller().claimFire(ringB)
        assertEquals(ringB, h.store.state.first())
        assertTrue(h.repo.get("a")!!.enabled)
        assertTrue(h.armer.cancelled.isEmpty())
        assertTrue(h.notifier.cancelledSnoozed.isEmpty())
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    @Test fun claimFireDismissesSoundingRing() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringing)
        h.controller().claimFire(ringB)
        assertEquals(ringB, h.store.state.first())
        assertFalse(h.repo.get("a")!!.enabled) // one-shot superseded counts as dismissed
        assertTrue(h.repo.get("b")!!.enabled)
        assertEquals(1, h.scheduler.rescheduleCount)
    }

    @Test fun claimFireStillWritesWhenSupersedeFails() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val failingRepo = object : AlarmRepository by h.repo {
            override suspend fun setEnabled(id: String, enabled: Boolean) { throw IllegalStateException("db") }
        }
        val c = RingingControllerImpl(failingRepo, h.store, h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { now }
        c.claimFire(ringB) // does not throw: the new ring was written
        assertEquals(ringB, h.store.state.first())
    }

    @Test fun claimFireStillWritesWhenStateReadFails() = runTest {
        val h = H(emptyList(), null)
        val unreadable = object : RingStateStore by h.store {
            override val state = kotlinx.coroutines.flow.flow<RingState?> { throw IllegalStateException("read") }
        }
        val c = RingingControllerImpl(h.repo, unreadable, h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { now }
        c.claimFire(ringB)
        assertEquals(ringB, h.store.state.first())
    }

    @Test fun dismissParkedSnoozeLeavesCurrentRingAlone() = runTest {
        val h = snoozedA()
        h.store.set(ringB) // A parked behind sounding B
        h.controller().dismiss("ring-A") // Dismiss on A's snoozed notification

        assertEquals(listOf("ring-A"), h.armer.cancelled)
        assertNull(h.ledger.get("ring-A"))
        assertEquals(listOf("ring-A"), h.notifier.cancelledSnoozed)
        assertFalse(h.repo.get("a")!!.enabled) // one-shot
        assertTrue(h.repo.get("b")!!.enabled)
        assertEquals(ringB, h.store.state.first())
        assertEquals(1, h.scheduler.rescheduleCount)
    }

    @Test fun dismissParkedRepeatingSnoozeStaysEnabled() = runTest {
        val h = H(listOf(alarm("a", setOf(DayOfWeek.MONDAY))), ringB)
        h.ledger.put("ring-A", "a", sevenTen)
        h.controller().dismiss("ring-A")
        assertNull(h.ledger.get("ring-A"))
        assertTrue(h.repo.get("a")!!.enabled)
        assertEquals(ringB, h.store.state.first())
    }

    @Test fun dismissCurrentSnoozedRingDropsItsSnooze() = runTest {
        val h = snoozedA()
        h.controller().dismiss("ring-A")
        assertNull(h.store.state.first())
        assertNull(h.ledger.get("ring-A"))
        assertEquals(listOf("ring-A"), h.armer.cancelled)
        assertEquals(listOf("ring-A"), h.notifier.cancelledSnoozed)
        assertFalse(h.repo.get("a")!!.enabled)
    }

    @Test fun dismissRemovesLedgerEntryBeforeRescheduling() = runTest {
        val h = snoozedA()
        var ledgerAtReschedule: SnoozeEntry? = SnoozeEntry("x", "x", now)
        val probe = object : AlarmScheduler {
            override suspend fun rescheduleAll() { ledgerAtReschedule = h.ledger.get("ring-A") }
        }
        RingingControllerImpl(h.repo, h.store, h.settings, probe, h.armer, h.notifier, h.ledger) { now }.dismiss("ring-A")
        assertNull(ledgerAtReschedule)
    }

    @Test fun snoozeResumeAfterDismissWhileSnoozedIsNotLive() = runTest {
        val h = snoozedA()
        val c = h.controller()
        c.dismiss("ring-A")
        assertFalse(c.claimSnoozeResume("ring-A", "a"))
        assertNull(h.store.state.first())
    }

    @Test fun snoozeResumeOfParkedAfterItsDismissIsNotLive() = runTest {
        val h = snoozedA()
        h.store.set(ringB)
        val c = h.controller()
        c.dismiss("ring-A")
        assertFalse(c.claimSnoozeResume("ring-A", "a"))
        assertEquals(ringB, h.store.state.first())
    }

    @Test fun snoozeResumeSupersedesSoundingRing() = runTest {
        val h = snoozedA()
        h.store.set(ringB)
        assertTrue(h.controller().claimSnoozeResume("ring-A", "a"))
        assertEquals(RingState("a", "ring-A", now, null), h.store.state.first())
        assertFalse(h.repo.get("b")!!.enabled) // B (one-shot) was sounding: dismissed
        assertTrue(h.repo.get("a")!!.enabled)
    }

    @Test fun snoozeResumeParksSnoozedRing() = runTest {
        val h = snoozedA()
        val bSnoozed = ringB.copy(snoozedUntil = sevenTen.plusSeconds(60))
        h.store.set(bSnoozed)
        h.ledger.put("ring-B", "b", bSnoozed.snoozedUntil!!)
        assertTrue(h.controller().claimSnoozeResume("ring-A", "a"))
        assertEquals("ring-A", h.store.state.first()!!.ringId)
        assertTrue(h.repo.get("b")!!.enabled)
        assertEquals(SnoozeEntry("ring-B", "b", bSnoozed.snoozedUntil), h.ledger.get("ring-B"))
        assertFalse("ring-B" in h.armer.cancelled)
    }

    @Test fun snoozeResumeNeverSupersedesItself() = runTest {
        // snooze() armed and wrote the ledger, then died before the state write: A still "sounding".
        val sounding = ringA.copy(snoozedUntil = null)
        val h = H(listOf(alarm("a", emptySet())), sounding)
        h.ledger.put("ring-A", "a", sevenTen)
        assertTrue(h.controller().claimSnoozeResume("ring-A", "a"))
        assertTrue(h.repo.get("a")!!.enabled)
        assertEquals(sounding, h.store.state.first()) // same ring: startedAt kept
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    @Test fun snoozeResumeLiveFromStateAloneKeepsStartedAt() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringA) // no ledger entry
        assertTrue(h.controller().claimSnoozeResume("ring-A", "a"))
        assertEquals(ringA.copy(snoozedUntil = null), h.store.state.first())
    }

    @Test fun snoozeWritesLedgerBetweenArmAndState() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val recordingLedger = object : SnoozeLedger by h.ledger {
            override fun put(ringId: String, alarmId: String, until: Instant) {
                h.events += "ledger"
                h.ledger.put(ringId, alarmId, until)
            }
        }
        val recordingStore = object : RingStateStore by h.store {
            override suspend fun set(state: RingState?) { h.events += "set"; h.store.set(state) }
        }
        h.settings.setSnoozeMinutes(7)
        RingingControllerImpl(h.repo, recordingStore, h.settings, h.scheduler, h.armer, h.notifier, recordingLedger) { now }.snooze()
        val until = Instant.parse("2026-03-02T10:07:00Z")
        assertEquals(listOf("arm", "ledger", "set"), h.events)
        assertEquals(SnoozeEntry("ring-1", "a", until), h.ledger.get("ring-1"))
    }

    @Test fun snoozeLedgerFailureWritesNothingAndCancelsArm() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val failingLedger = object : SnoozeLedger by h.ledger {
            override fun put(ringId: String, alarmId: String, until: Instant) { throw IllegalStateException("prefs") }
        }
        val c = RingingControllerImpl(h.repo, h.store, h.settings, h.scheduler, h.armer, h.notifier, failingLedger) { now }
        var thrown = false
        try { c.snooze() } catch (e: IllegalStateException) { thrown = true }
        assertTrue(thrown)
        assertEquals(ringing, h.store.state.first())
        assertEquals(listOf("ring-1"), h.armer.cancelled)
        assertTrue(h.notifier.snoozed.isEmpty())
    }

    @Test fun snoozeStateWriteFailureTakesTheSnoozeBack() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val failingStore = object : RingStateStore by h.store {
            override suspend fun set(state: RingState?) { throw IllegalStateException("disk") }
        }
        val c = RingingControllerImpl(h.repo, failingStore, h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { now }
        var thrown = false
        try { c.snooze() } catch (e: IllegalStateException) { thrown = true }
        assertTrue(thrown)
        assertNull(h.ledger.get("ring-1"))
        assertEquals(listOf("ring-1"), h.armer.cancelled)
        assertTrue(h.notifier.snoozed.isEmpty())
    }

    /** Store whose read and write both suspend, so unlocked claims would interleave. */
    private class YieldingStore(private val inner: FakeRingStateStore) : RingStateStore {
        override val state = kotlinx.coroutines.flow.flow {
            yield()
            emit(inner.state.first())
        }
        override suspend fun set(state: RingState?) { yield(); inner.set(state) }
    }

    @Test fun interleavedClaimFiresEndWithOneRingAndNoLostDismiss() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet()), alarm("c", emptySet())), ringing)
        val c = RingingControllerImpl(h.repo, YieldingStore(h.store), h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { now }
        val ringC = RingState("c", "ring-C", now, null)
        val j1 = launch { c.claimFire(ringB) }
        val j2 = launch { c.claimFire(ringC) }
        j1.join(); j2.join()

        val final = h.store.state.first()!!
        assertTrue(final == ringB || final == ringC)
        // Every ring that lost the slot was dismissed: A, and whichever of B/C claimed first.
        assertFalse(h.repo.get("a")!!.enabled)
        val loser = if (final == ringB) "c" else "b"
        assertFalse(h.repo.get(loser)!!.enabled)
        assertTrue(h.repo.get(final.alarmId)!!.enabled)
    }

    @Test fun claimFireWaitsForInFlightDismiss() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringing)
        val c = RingingControllerImpl(h.repo, YieldingStore(h.store), h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { now }
        val j1 = launch { c.dismiss("ring-1") }
        val j2 = launch { c.claimFire(ringB) }
        j1.join(); j2.join()
        assertEquals(ringB, h.store.state.first()) // the dismiss's clear did not wipe B
        assertFalse(h.repo.get("a")!!.enabled)
    }

    // ---- stuck snoozed notification ----

    @Test fun ignoredDismissCancelsThatRingsSnoozedNotification() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        h.controller().dismiss("ring-gone")
        assertEquals(listOf("ring-gone"), h.notifier.cancelledSnoozed)
        assertEquals(ringing, h.store.state.first()) // stops no ring
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    @Test fun notLiveSnoozeResumeCancelsItsSnoozedNotification() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        assertFalse(h.controller().claimSnoozeResume("ring-gone", "a"))
        assertEquals(listOf("ring-gone"), h.notifier.cancelledSnoozed)
    }

    // ---- clearStale ----

    private val notLive: (String) -> Boolean = { false }

    @Test fun clearStaleClearsSoundingStateWithNoLiveSession() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        h.controller().clearStale(notLive)
        assertNull(h.store.state.first())
        assertTrue(h.repo.get("a")!!.enabled) // not a dismiss
        assertEquals(1, h.scheduler.rescheduleCount)
    }

    @Test fun clearStaleCanSkipTheReschedule() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        h.controller().clearStale(notLive, reschedule = false)
        assertNull(h.store.state.first())
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    @Test fun clearStaleKeepsLiveRing() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        h.controller().clearStale(isLive = { it == "ring-1" })
        assertEquals(ringing, h.store.state.first())
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    @Test fun clearStaleKeepsFutureSnooze() = runTest {
        val snoozed = ringing.copy(snoozedUntil = now.plusSeconds(300))
        val h = H(listOf(alarm("a", emptySet())), snoozed)
        h.controller().clearStale(notLive)
        assertEquals(snoozed, h.store.state.first())
    }

    @Test fun clearStaleKeepsSnoozeThatCameDueWithinTenMinutes() = runTest {
        val snoozed = ringing.copy(snoozedUntil = now.minusSeconds(300))
        val h = H(listOf(alarm("a", emptySet())), snoozed)
        h.controller().clearStale(notLive)
        assertEquals(snoozed, h.store.state.first())
    }

    @Test fun clearStaleClearsSnoozeMoreThanTenMinutesPast() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing.copy(snoozedUntil = now.minusSeconds(601)))
        h.controller().clearStale(notLive)
        assertNull(h.store.state.first())
        assertTrue(h.repo.get("a")!!.enabled)
        assertEquals(1, h.scheduler.rescheduleCount)
    }

    @Test fun clearStaleWithNoStateIsNoOp() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        h.controller().clearStale(notLive)
        assertEquals(0, h.scheduler.rescheduleCount)
    }
}
