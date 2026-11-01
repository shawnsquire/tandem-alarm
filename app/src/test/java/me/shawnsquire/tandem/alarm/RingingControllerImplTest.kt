package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.ParkedSnooze
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingOrigin
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    val cancelledRinging = mutableListOf<String>()
    override fun cancelRinging(ringId: String) { cancelledRinging += ringId }
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

    private fun H.controller(mirror: RingMirrorPort = NoOpRingMirrorPort) =
        RingingControllerImpl(repo, store, settings, scheduler, armer, notifier, ledger, mirror) { now }

    private class RecordingMirror : RingMirrorPort {
        val calls = mutableListOf<Triple<MirrorKind, List<RingMember>, Instant?>>()
        override fun onLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?) {
            calls += Triple(kind, members, snoozedUntil)
        }
    }

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

    @Test fun remoteSnoozeBehavesLikeLocalAndMirrorIsNotCalled() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val mirror = RecordingMirror()
        h.settings.setSnoozeMinutes(7)
        h.controller(mirror).snooze(RingOrigin.REMOTE)
        val until = Instant.parse("2026-03-02T10:07:00Z")
        assertEquals(ringing.copy(snoozedUntil = until), h.store.state.first())
        assertEquals(listOf("ring-1" to until), h.notifier.snoozed)
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun remoteDismissBehavesLikeLocalAndMirrorIsNotCalled() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val mirror = RecordingMirror()
        h.controller(mirror).dismiss("ring-1", RingOrigin.REMOTE)
        assertNull(h.store.state.first())
        assertFalse(h.repo.get("a")!!.enabled)
        assertEquals(1, h.scheduler.rescheduleCount)
        assertTrue(mirror.calls.isEmpty())
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
        assertEquals(SlotClaim.Claimed, c.claimSnoozeResume("ring-A", "a"))
        assertEquals(RingState("a", "ring-A", sevenTen, null), h.store.state.first())
        assertTrue(h.repo.get("a")!!.enabled)
        assertNull(h.ledger.get("ring-A"))
        assertTrue("ring-A" in h.notifier.cancelledSnoozed)
    }

    @Test fun claimFireParksSnoozedRing() = runTest {
        val h = snoozedA()
        val ringB = ringB.copy(occurrenceAt = sevenOhFive) // a known occurrence still parks, never merges
        assertEquals(SlotClaim.Claimed, h.controller().claimFire(ringB))
        assertEquals(ringB, h.store.state.first())
        assertTrue(h.repo.get("a")!!.enabled)
        assertTrue(h.armer.cancelled.isEmpty())
        assertTrue(h.notifier.cancelledSnoozed.isEmpty())
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    /** No occurrence (never on the fire path): no member can be written, so it supersedes as before. */
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

    @Test fun claimFireNotStillDueWritesNothingAndSupersedesNothing() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringing)
        assertEquals(SlotClaim.NotDue, h.controller().claimFire(ringB) { false })
        assertEquals(ringing, h.store.state.first()) // the sounding ring stays
        assertTrue(h.repo.get("a")!!.enabled) // not dismissed
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    @Test fun claimFireWritesWhenStillDueThrows() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        assertEquals(SlotClaim.Claimed, h.controller().claimFire(ringB) { throw IllegalStateException("db") })
        assertEquals(ringB, h.store.state.first())
    }

    @Test fun claimFireStillDueRunsInsideTheLock() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringing)
        val c = h.controller()
        val gate = CompletableDeferred<Unit>()
        val claim = launch { c.claimFire(ringB) { gate.await(); true } }
        runCurrent()
        val dismiss = launch { c.dismiss("ring-1") }
        runCurrent()
        // The dismiss waits for the claim: it cannot slip in between stillDue and the write.
        assertTrue(dismiss.isActive)
        assertEquals(ringing, h.store.state.first())
        assertTrue(h.repo.get("a")!!.enabled)
        gate.complete(Unit)
        claim.join(); dismiss.join()
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
        assertEquals(SlotClaim.NotDue, c.claimSnoozeResume("ring-A", "a"))
        assertNull(h.store.state.first())
    }

    @Test fun snoozeResumeOfParkedAfterItsDismissIsNotLive() = runTest {
        val h = snoozedA()
        h.store.set(ringB)
        val c = h.controller()
        c.dismiss("ring-A")
        assertEquals(SlotClaim.NotDue, c.claimSnoozeResume("ring-A", "a"))
        assertEquals(ringB, h.store.state.first())
    }

    /** A pre-upgrade entry has no occurrence: the sounding ring is superseded, never merged with a guessed one. */
    @Test fun snoozeResumeSupersedesSoundingRing() = runTest {
        val h = snoozedA()
        h.store.set(ringB)
        assertEquals(SlotClaim.Claimed, h.controller().claimSnoozeResume("ring-A", "a"))
        assertEquals(RingState("a", "ring-A", now, null), h.store.state.first())
        assertFalse(h.repo.get("b")!!.enabled) // B (one-shot) was sounding: dismissed
        assertTrue(h.repo.get("a")!!.enabled)
    }

    @Test fun snoozeResumeParksSnoozedRing() = runTest {
        val h = snoozedA()
        val bSnoozed = ringB.copy(snoozedUntil = sevenTen.plusSeconds(60))
        h.store.set(bSnoozed)
        h.ledger.put("ring-B", "b", bSnoozed.snoozedUntil!!)
        assertEquals(SlotClaim.Claimed, h.controller().claimSnoozeResume("ring-A", "a"))
        assertEquals("ring-A", h.store.state.first()!!.ringId)
        assertTrue(h.repo.get("b")!!.enabled)
        assertEquals(SnoozeEntry("ring-B", "b", bSnoozed.snoozedUntil), h.ledger.get("ring-B"))
        assertFalse("ring-B" in h.armer.cancelled)
    }

    private val occurrenceX = Instant.parse("2026-03-02T06:30:00Z")

    private fun linkedAlarm(id: String, enabled: Boolean = true) = alarm(id, emptySet()).copy(
        enabled = enabled,
        link = AlarmLink("owner", "owner-$id", LinkMode.RING, null, true, null, null, null, null),
    )

    @Test fun snoozeWritesLedgerEntryWithTheRingsOccurrence() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing.copy(occurrenceAt = occurrenceX))
        h.controller().snooze()
        assertEquals(occurrenceX, h.ledger.get("ring-1")!!.occurrenceAt)
    }

    @Test fun snoozeResumeKeepsOccurrenceAndMergedWhenSlotHoldsTheRing() = runTest {
        val merged = listOf(RingMember("m", occurrenceX))
        val state = ringA.copy(occurrenceAt = occurrenceX, merged = merged)
        val h = H(listOf(alarm("a", emptySet())), state)
        h.ledger.put("ring-A", "a", sevenTen, occurrenceX)
        assertEquals(SlotClaim.Claimed, h.controller().claimSnoozeResume("ring-A", "a"))
        assertEquals(state.copy(snoozedUntil = null), h.store.state.first())
    }

    @Test fun snoozeResumeTakesOccurrenceFromStateWhenLedgerLacksIt() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringA.copy(occurrenceAt = occurrenceX))
        h.ledger.put("ring-A", "a", sevenTen)
        assertEquals(SlotClaim.Claimed, h.controller().claimSnoozeResume("ring-A", "a"))
        assertEquals(occurrenceX, h.store.state.first()!!.occurrenceAt)
    }

    @Test fun snoozeResumeOfParkedEntryUsesTheParkedOccurrence() = runTest {
        // B snoozed: a sounding B would take the entry into its merged list instead.
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringB.copy(snoozedUntil = sevenTen, occurrenceAt = Instant.EPOCH))
        h.ledger.put("ring-A", "a", sevenTen, occurrenceX)
        assertEquals(SlotClaim.Claimed, h.controller().claimSnoozeResume("ring-A", "a"))
        assertEquals(RingState("a", "ring-A", now, null, occurrenceX), h.store.state.first())
    }

    // ---- merge into a sounding ring ----

    private val occurrenceY = Instant.parse("2026-03-02T07:05:00Z")
    private val soundingX = ringing.copy(occurrenceAt = occurrenceX)

    @Test fun claimFireMergesIntoSoundingRing() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), soundingX)
        val result = h.controller().claimFire(RingState("b", "ring-B", sevenOhFive, null, occurrenceY))
        assertEquals(SlotClaim.Merged, result)
        // Same ring keeps sounding: ringId, alarmId, startedAt and occurrence unchanged.
        assertEquals(soundingX.copy(merged = listOf(RingMember("b", occurrenceY))), h.store.state.first())
        // Not a dismiss: the one-shot stays on and nothing of a dismiss ran.
        assertTrue(h.repo.get("a")!!.enabled)
        assertTrue(h.repo.get("b")!!.enabled)
        assertTrue(h.armer.cancelled.isEmpty())
        assertTrue(h.notifier.cancelledSnoozed.isEmpty())
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    @Test fun claimFireDoesNotAddADuplicateMember() = runTest {
        val state = soundingX.copy(merged = listOf(RingMember("b", occurrenceY)))
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), state)
        val c = h.controller()
        assertEquals(SlotClaim.Merged, c.claimFire(RingState("b", "ring-B2", sevenOhFive, null, occurrenceY)))
        assertEquals(SlotClaim.Merged, c.claimFire(RingState("a", "ring-A2", sevenOhFive, null, occurrenceX))) // the primary
        assertEquals(state, h.store.state.first())
    }

    @Test fun claimFireMergeWriteFailurePropagates() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), soundingX)
        val failing = object : RingStateStore by h.store {
            override suspend fun set(state: RingState?) { throw IllegalStateException("disk full") }
        }
        val c = RingingControllerImpl(h.repo, failing, h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { now }
        // I1: the caller must not mistake an unwritten merge for one the sounding ring covers.
        val thrown = runCatching { c.claimFire(RingState("b", "ring-B", sevenOhFive, null, occurrenceY)) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(soundingX, h.store.state.first())
    }

    @Test fun snoozeResumeMergesIntoSoundingRing() = runTest {
        val soundingB = ringB.copy(occurrenceAt = occurrenceY)
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), soundingB)
        h.ledger.put("ring-A", "a", sevenTen, occurrenceX)
        assertEquals(SlotClaim.Merged, h.controller().claimSnoozeResume("ring-A", "a"))
        assertEquals(soundingB.copy(merged = listOf(RingMember("a", occurrenceX))), h.store.state.first())
        assertNull(h.ledger.get("ring-A"))
        assertEquals(listOf("ring-A"), h.armer.cancelled)
        assertEquals(listOf("ring-A"), h.notifier.cancelledSnoozed)
        assertTrue(h.repo.get("b")!!.enabled) // B was not dismissed
        assertTrue(h.repo.get("a")!!.enabled)
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    @Test fun snoozeResumeWithOccurrenceStillParksSnoozedRing() = runTest {
        val bSnoozed = ringB.copy(snoozedUntil = sevenTen.plusSeconds(60), occurrenceAt = occurrenceY)
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), bSnoozed)
        h.ledger.put("ring-B", "b", bSnoozed.snoozedUntil!!, occurrenceY)
        h.ledger.put("ring-A", "a", sevenTen, occurrenceX)
        assertEquals(SlotClaim.Claimed, h.controller().claimSnoozeResume("ring-A", "a"))
        assertEquals(RingState("a", "ring-A", now, null, occurrenceX), h.store.state.first())
        assertNotNull(h.ledger.get("ring-B"))
    }

    @Test fun writeUnlessRingingRefusesForAMergedMember() = runTest {
        val state = soundingX.copy(merged = listOf(RingMember("b", occurrenceY)))
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), state)
        var writes = 0
        val c = h.controller()
        assertFalse(c.writeUnlessRinging("b", null) { writes++ })
        assertFalse(c.writeUnlessRinging("b", occurrenceY) { writes++ })
        assertEquals(0, writes)
        assertTrue(c.writeUnlessRinging("b", occurrenceY.plusSeconds(86400)) { writes++ }) // a later occurrence
        assertEquals(1, writes)
    }

    @Test fun dismissLinkedOneShotUsesSetJoinerEnabledAndLeavesEnabled() = runTest {
        val h = H(listOf(linkedAlarm("a")), ringing)
        h.controller().dismiss("ring-1")
        val row = h.repo.get("a")!!
        assertTrue(row.enabled)
        assertFalse(row.link!!.joinerEnabled)
    }

    @Test fun dismissOwnOneShotStillCallsSetEnabled() = runTest {
        val calls = mutableListOf<String>()
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val repo = object : AlarmRepository by h.repo {
            override suspend fun setEnabled(id: String, enabled: Boolean) { calls += "setEnabled"; h.repo.setEnabled(id, enabled) }
            override suspend fun setJoinerEnabled(id: String, enabled: Boolean) { calls += "setJoinerEnabled" }
        }
        RingingControllerImpl(repo, h.store, h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { now }.dismiss("ring-1")
        assertEquals(listOf("setEnabled"), calls)
    }

    @Test fun dropParkedLinkedOneShotUsesSetJoinerEnabled() = runTest {
        val h = H(listOf(linkedAlarm("a"), alarm("b", emptySet())), ringB)
        h.ledger.put("ring-A", "a", sevenTen)
        h.controller().dismiss("ring-A")
        val row = h.repo.get("a")!!
        assertTrue(row.enabled)
        assertFalse(row.link!!.joinerEnabled)
    }

    @Test fun snoozeResumeNeverSupersedesItself() = runTest {
        // snooze() armed and wrote the ledger, then died before the state write: A still "sounding".
        val sounding = ringA.copy(snoozedUntil = null)
        val h = H(listOf(alarm("a", emptySet())), sounding)
        h.ledger.put("ring-A", "a", sevenTen)
        assertEquals(SlotClaim.Claimed, h.controller().claimSnoozeResume("ring-A", "a"))
        assertTrue(h.repo.get("a")!!.enabled)
        assertEquals(sounding, h.store.state.first()) // same ring: startedAt kept
        assertEquals(0, h.scheduler.rescheduleCount)
    }

    @Test fun snoozeResumeLiveFromStateAloneKeepsStartedAt() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringA) // no ledger entry
        assertEquals(SlotClaim.Claimed, h.controller().claimSnoozeResume("ring-A", "a"))
        assertEquals(ringA.copy(snoozedUntil = null), h.store.state.first())
    }

    @Test fun snoozeWritesLedgerBetweenArmAndState() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val recordingLedger = object : SnoozeLedger by h.ledger {
            override fun put(ringId: String, alarmId: String, until: Instant, occurrenceAt: Instant?) {
                h.events += "ledger"
                h.ledger.put(ringId, alarmId, until, occurrenceAt)
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
            override fun put(ringId: String, alarmId: String, until: Instant, occurrenceAt: Instant?) { throw IllegalStateException("prefs") }
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
        assertEquals(SlotClaim.NotDue, h.controller().claimSnoozeResume("ring-gone", "a"))
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
        assertEquals(listOf("ring-1"), h.notifier.cancelledRinging) // its ringing notification goes too
    }

    @Test fun clearStaleOfOldSnoozeCancelsSnoozedNotNotRinging() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing.copy(snoozedUntil = now.minusSeconds(3600)))
        h.controller().clearStale(notLive, reschedule = false)
        assertNull(h.store.state.first())
        assertEquals(listOf("ring-1"), h.notifier.cancelledSnoozed)
        assertTrue(h.notifier.cancelledRinging.isEmpty())
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

    @Test fun writeUnlessRingingRefusesWhileThatAlarmRings() = runTest {
        val h = H(listOf(alarm("a", setOf(DayOfWeek.MONDAY))), ringing)
        var wrote = false
        assertFalse(h.controller().writeUnlessRinging("a", null) { wrote = true })
        assertFalse(h.controller().writeUnlessRinging("a", ringing.startedAt) { wrote = true })
        assertFalse(wrote)
    }

    @Test fun writeUnlessRingingWritesForOtherAlarmsAndEarlierRings() = runTest {
        val h = H(listOf(alarm("a", setOf(DayOfWeek.MONDAY))), ringing)
        var writes = 0
        assertTrue(h.controller().writeUnlessRinging("b", null) { writes++ })
        assertTrue(h.controller().writeUnlessRinging("a", ringing.startedAt.plusSeconds(60)) { writes++ })
        assertEquals(2, writes)
    }

    @Test fun claimFireWaitsForAWriteInProgressAndSeesIt() = runTest {
        val h = H(listOf(alarm("a", setOf(DayOfWeek.MONDAY))), null)
        val c = h.controller()
        val inWrite = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        var skipped = false
        val writer = launch {
            c.writeUnlessRinging("a", null) {
                inWrite.complete(Unit)
                finishWrite.await()
                skipped = true
            }
        }
        runCurrent()
        inWrite.await()
        var sawSkip: Boolean? = null
        val claim = launch {
            c.claimFire(RingState("a", "r-new", now, null), stillDue = { (!skipped).also { sawSkip = skipped } })
        }
        runCurrent()
        // The claim is parked behind the write: it has not even run its re-check.
        assertNull(sawSkip)
        finishWrite.complete(Unit)
        runCurrent()
        writer.join()
        claim.join()
        assertEquals(true, sawSkip)
        assertNull(h.store.state.first())
    }

    @Test fun ringContextReadsRingMergedSnoozesAndLastRang() = runTest {
        val member = RingMember("b", now.minusSeconds(60))
        val ring = RingState("a", "ring-1", now, null, occurrenceAt = now, merged = listOf(member))
        val h = H(listOf(alarm("a", setOf(DayOfWeek.MONDAY))), ring)
        val occ = Instant.parse("2026-03-02T09:00:00Z")
        val until = Instant.parse("2026-03-02T10:05:00Z")
        h.ledger.put("ring-1", "a", until, now)
        h.ledger.put("ring-0", "c", until.plusSeconds(60))
        val fired = InMemoryFiredLedger().apply { markRang("a", occ) }
        val c = RingingControllerImpl(h.repo, h.store, h.settings, h.scheduler, h.armer, h.notifier, h.ledger, NoOpRingMirrorPort, fired) { now }

        val ctx = c.ringContext("a")
        assertEquals(ring, ctx.ring)
        assertEquals(listOf(member), ctx.merged)
        assertEquals(
            listOf(ParkedSnooze("a", now, until), ParkedSnooze("c", null, until.plusSeconds(60))),
            ctx.snoozes,
        )
        assertEquals(occ, ctx.lastRang)
        assertNull(c.ringContext("zzz").lastRang)
    }

    @Test fun ringContextIsEmptyWithNoRing() = runTest {
        val h = H(emptyList(), null)
        val ctx = h.controller().ringContext("a")
        assertNull(ctx.ring)
        assertTrue(ctx.merged.isEmpty())
        assertTrue(ctx.snoozes.isEmpty())
        assertNull(ctx.lastRang)
    }
}
