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
    val ringingLabels = mutableListOf<List<String>>()
    val snoozed = mutableListOf<Pair<String, Instant>>()
    val cancelledSnoozed = mutableListOf<String>()
    val cancelSnoozedCount get() = cancelledSnoozed.size
    override fun showRinging(alarm: Alarm?, ringId: String, labels: List<String>) { ringing += ringId; ringingLabels += labels }
    val fallback = mutableListOf<String>()
    override fun showFallbackRinging(ringId: String) { fallback += ringId }
    /** Whether the Notify surface can alert; false and [showNotifyRinging] posts nothing, a Notify relabel reports it too. */
    var notifyCanAlert = true
    val notify = mutableListOf<String>()
    val notifyLabels = mutableListOf<List<String>>()
    /** The Notify posts asked to alert again (an un-silence). */
    val notifyAlertsAgain = mutableListOf<String>()
    override fun showNotifyRinging(alarm: Alarm?, ringId: String, labels: List<String>, alertAgain: Boolean): Boolean {
        if (!notifyCanAlert) return false
        notify += ringId; notifyLabels += labels
        if (alertAgain) notifyAlertsAgain += ringId
        return true
    }
    /** [updateRingingLabel] calls: (ringId, labels, notifySurface). */
    val relabels = mutableListOf<Triple<String, List<String>, Boolean>>()
    override fun updateRingingLabel(alarm: Alarm?, ringId: String, labels: List<String>, notifySurface: Boolean): Boolean {
        relabels += Triple(ringId, labels, notifySurface)
        return !notifySurface || notifyCanAlert
    }
    val cancelledRinging = mutableListOf<String>()
    override fun cancelRinging(ringId: String) { cancelledRinging += ringId }
    val snoozedLabels = mutableListOf<List<String>>()
    override fun showSnoozed(ringId: String, until: Instant, labels: List<String>) { snoozed += ringId to until; snoozedLabels += labels }
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
        val fired = InMemoryFiredLedger()
        val notices = RemoteRingNotices()
        val groups = InMemorySnoozeGroups()
    }

    private fun H.controller(
        mirror: RingMirrorPort = NoOpRingMirrorPort,
        armer: SnoozeArmer = this.armer,
        store: RingStateStore = this.store,
        ledger: SnoozeLedger = this.ledger,
        fired: FiredLedger = this.fired,
        groups: SnoozeGroups = this.groups,
    ): RingingControllerImpl {
        var splits = 0
        return RingingControllerImpl(
            repo, store, settings, scheduler, armer, notifier, ledger, mirror, fired, newRingId = { "split-${++splits}" }, remoteNotices = notices,
            snoozeGroups = groups,
        ) { now }
    }

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

    /** A pre-upgrade entry has no occurrence: it merges under its snooze end as a placeholder, never superseding the sounding ring. */
    @Test fun snoozeResumeOfALegacyEntryMergesIntoTheSoundingRing() = runTest {
        val h = snoozedA()
        h.store.set(ringB)
        val merged = mutableListOf<RingMember>()
        assertEquals(SlotClaim.Merged, h.controller().claimSnoozeResume("ring-A", "a") { merged += it })
        assertEquals(ringB.copy(merged = listOf(RingMember("a", sevenTen))), h.store.state.first())
        assertEquals(listOf(RingMember("a", sevenTen)), merged)
        assertTrue(h.repo.get("b")!!.enabled) // B sounds on
        assertTrue(h.repo.get("a")!!.enabled)
        assertNull(h.ledger.get("ring-A"))
        assertEquals(listOf("ring-A"), h.armer.cancelled)
    }

    @Test fun snoozeResumeOfALegacyEntryIntoAnEmptySlotWritesNoOccurrence() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null).also { it.ledger.put("ring-A", "a", sevenTen) }
        assertEquals(SlotClaim.Claimed, h.controller().claimSnoozeResume("ring-A", "a"))
        assertEquals(RingState("a", "ring-A", now, null), h.store.state.first()) // the placeholder is never stored as X
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

    @Test fun snoozeResumeStateWriteFailureLeavesTheSnoozeInPlace() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        h.ledger.put("ring-A", "a", sevenTen, occurrenceX)
        val failing = object : RingStateStore by h.store {
            override suspend fun set(state: RingState?) { throw IllegalStateException("disk full") }
        }
        val c = RingingControllerImpl(h.repo, failing, h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { now }
        assertTrue(runCatching { c.claimSnoozeResume("ring-A", "a") }.exceptionOrNull() is IllegalStateException)
        // Nothing written: the snooze is still there for the scheduler and a later dismiss.
        assertEquals(SnoozeEntry("ring-A", "a", sevenTen, occurrenceX), h.ledger.get("ring-A"))
        assertTrue(h.armer.cancelled.isEmpty())
        assertTrue(h.notifier.cancelledSnoozed.isEmpty())
    }

    @Test fun snoozeResumeMergeWriteFailureLeavesTheSnoozeInPlace() = runTest {
        val soundingB = ringB.copy(occurrenceAt = occurrenceY)
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), soundingB)
        h.ledger.put("ring-A", "a", sevenTen, occurrenceX)
        val failing = object : RingStateStore by h.store {
            override suspend fun set(state: RingState?) { throw IllegalStateException("disk full") }
        }
        val c = RingingControllerImpl(h.repo, failing, h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { now }
        assertTrue(runCatching { c.claimSnoozeResume("ring-A", "a") }.exceptionOrNull() is IllegalStateException)
        assertEquals(soundingB, h.store.state.first())
        assertNotNull(h.ledger.get("ring-A"))
        assertTrue(h.armer.cancelled.isEmpty())
        assertTrue(h.notifier.cancelledSnoozed.isEmpty())
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

    @Test fun writeUnlessRingingRefusesForAMemberSplitOutBySnooze() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), soundingX.copy(merged = listOf(RingMember("b", occurrenceY))))
        val c = h.controller()
        c.snooze()
        assertTrue(h.store.state.first()!!.merged.isEmpty()) // b now lives in its own ledger entry
        var writes = 0
        assertFalse(c.writeUnlessRinging("b", null) { writes++ })
        assertFalse(c.writeUnlessRinging("b", occurrenceY) { writes++ })
        assertEquals(0, writes)
        assertTrue(c.writeUnlessRinging("b", occurrenceY.plusSeconds(86400)) { writes++ }) // a later occurrence
        assertEquals(1, writes)
    }

    @Test fun writeUnlessRingingRefusesForASnoozeParkedBehindAnotherRing() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("c", emptySet())), soundingX)
        h.ledger.put("ring-P", "c", now.plusSeconds(300), occurrenceZ)
        h.ledger.put("ring-L", "d", now.plusSeconds(300)) // written before occurrences were stored
        var writes = 0
        val c = h.controller()
        assertFalse(c.writeUnlessRinging("c", occurrenceZ) { writes++ })
        assertFalse(c.writeUnlessRinging("d", occurrenceZ.plusSeconds(86400)) { writes++ }) // unknown occurrence: any
        assertEquals(0, writes)
        assertTrue(c.writeUnlessRinging("c", occurrenceZ.plusSeconds(86400)) { writes++ })
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

    // ---- whole-ring dismiss and snooze: every member resolves, only LOCAL mirrors (I4) ----

    private val occurrenceZ = Instant.parse("2026-03-02T07:20:00Z")
    private val mergedRing = soundingX.copy(merged = listOf(RingMember("b", occurrenceY), RingMember("c", occurrenceZ)))
    private val allMembers = listOf(RingMember("a", occurrenceX), RingMember("b", occurrenceY), RingMember("c", occurrenceZ))
    private fun mergedH() = H(listOf(alarm("a", emptySet()), linkedAlarm("b"), alarm("c", setOf(DayOfWeek.MONDAY))), mergedRing)

    private suspend fun H.assertEveryMemberResolved() {
        assertNull(store.state.first())
        assertFalse(repo.get("a")!!.enabled) // own one-shot: off
        assertTrue(repo.get("b")!!.enabled) // linked one-shot: our side off, the owner's enabled untouched
        assertFalse(repo.get("b")!!.link!!.joinerEnabled)
        assertTrue(repo.get("c")!!.enabled) // repeating
        assertEquals(1, scheduler.rescheduleCount)
    }

    @Test fun localDismissOfMergedRingResolvesEveryMemberAndMirrorsThemOnce() = runTest {
        val h = mergedH()
        val mirror = RecordingMirror()
        h.controller(mirror).dismiss("ring-1", RingOrigin.LOCAL)
        h.assertEveryMemberResolved()
        assertEquals(listOf(Triple(MirrorKind.DISMISS, allMembers, null as Instant?)), mirror.calls)
    }

    @Test fun remoteRingIdStopOfAMergedRingResolvesOnlyThePrimaryTheRestSoundOn() = runTest {
        // a rings as ring-1, the partner taps Stop on it, and b merges before the command lands:
        // b was never seen by the partner, so it sounds on under the same ringId.
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), soundingX)
        val mirror = RecordingMirror()
        val c = h.controller(mirror)
        assertEquals(SlotClaim.Merged, c.claimFire(RingState("b", "ring-B", now, null, occurrenceY)))
        c.dismiss("ring-1", RingOrigin.REMOTE)
        assertEquals(RingState("b", "ring-1", ringing.startedAt, null, occurrenceY), h.store.state.first())
        assertFalse(h.repo.get("a")!!.enabled) // the one-shot the partner stopped is off
        assertTrue(h.repo.get("b")!!.enabled)
        assertTrue(mirror.calls.isEmpty())
        assertEquals(RemoteRingEnd("ring-1", RingMember("a", occurrenceX), MirrorKind.DISMISS, null, endsRing = false, seq = 1), h.notices.latest.value)
    }

    @Test fun aSecondRemoteRingIdStopResolvesThePromotedPrimary() = runTest {
        // After the promotion validate takes a stop only for the new primary: it resolves that one.
        val h = mergedH()
        val c = h.controller()
        c.dismiss("ring-1", RingOrigin.REMOTE)
        assertEquals(promoted, h.store.state.first())
        c.dismiss("ring-1", RingOrigin.REMOTE)
        assertEquals(RingState("c", "ring-1", ringing.startedAt, null, occurrenceZ), h.store.state.first())
        assertFalse(h.repo.get("b")!!.link!!.joinerEnabled)
        c.dismiss("ring-1", RingOrigin.REMOTE) // c alone: the ring ends
        assertNull(h.store.state.first())
    }

    @Test fun remoteRingIdStopOfAMergedRingWithALegacyPrimaryStillPromotes() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringing.copy(merged = listOf(RingMember("b", occurrenceY))))
        h.controller().dismiss("ring-1", RingOrigin.REMOTE)
        assertEquals(RingState("b", "ring-1", ringing.startedAt, null, occurrenceY), h.store.state.first())
        assertFalse(h.repo.get("a")!!.enabled)
        assertTrue(h.repo.get("b")!!.enabled)
        assertEquals(RemoteRingEnd("ring-1", null, MirrorKind.DISMISS, null, endsRing = false, seq = 1), h.notices.latest.value)
    }

    @Test fun localDismissLeavesOutAPrimaryWithoutOccurrence() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringing.copy(merged = listOf(RingMember("b", occurrenceY))))
        val mirror = RecordingMirror()
        h.controller(mirror).dismiss("ring-1")
        assertFalse(h.repo.get("b")!!.enabled)
        assertEquals(listOf(Triple(MirrorKind.DISMISS, listOf(RingMember("b", occurrenceY)), null as Instant?)), mirror.calls)
    }

    @Test fun localDismissWithNoKnownMemberSkipsTheMirror() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val mirror = RecordingMirror()
        h.controller(mirror).dismiss("ring-1")
        assertNull(h.store.state.first())
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun localDismissStillMirrorsWhenAStepFails() = runTest {
        val h = mergedH()
        val mirror = RecordingMirror()
        val failingRepo = object : AlarmRepository by h.repo {
            override suspend fun setEnabled(id: String, enabled: Boolean) { throw IllegalStateException("db") }
        }
        val c = RingingControllerImpl(failingRepo, h.store, h.settings, h.scheduler, h.armer, h.notifier, h.ledger, mirror) { now }
        var thrown = false
        try { c.dismiss("ring-1") } catch (e: IllegalStateException) { thrown = true }
        assertTrue(thrown)
        assertEquals(listOf(Triple(MirrorKind.DISMISS, allMembers, null as Instant?)), mirror.calls)
    }

    @Test fun mirrorFailureNeverFailsTheDismiss() = runTest {
        val h = mergedH()
        val throwing = object : RingMirrorPort {
            override fun onLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?) { error("port") }
        }
        h.controller(throwing).dismiss("ring-1")
        h.assertEveryMemberResolved()
    }

    @Test fun localDismissOfSnoozedSlotRingMirrorsIt() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringA.copy(occurrenceAt = occurrenceX))
        h.ledger.put("ring-A", "a", sevenTen, occurrenceX)
        val mirror = RecordingMirror()
        h.controller(mirror).dismiss("ring-A")
        assertNull(h.store.state.first())
        assertEquals(listOf(Triple(MirrorKind.DISMISS, listOf(RingMember("a", occurrenceX)), null as Instant?)), mirror.calls)
    }

    @Test fun localDismissOfParkedSnoozeMirrorsThatMember() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringB)
        h.ledger.put("ring-A", "a", sevenTen, occurrenceX)
        val mirror = RecordingMirror()
        h.controller(mirror).dismiss("ring-A", RingOrigin.LOCAL)
        assertNull(h.ledger.get("ring-A"))
        assertEquals(ringB, h.store.state.first())
        assertEquals(listOf(Triple(MirrorKind.DISMISS, listOf(RingMember("a", occurrenceX)), null as Instant?)), mirror.calls)
    }

    @Test fun remoteDismissOfParkedSnoozeNeverMirrors() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringB)
        h.ledger.put("ring-A", "a", sevenTen, occurrenceX)
        val mirror = RecordingMirror()
        h.controller(mirror).dismiss("ring-A", RingOrigin.REMOTE)
        assertNull(h.ledger.get("ring-A"))
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun localDismissOfPreUpgradeParkedSnoozeSkipsTheMirror() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringB)
        h.ledger.put("ring-A", "a", sevenTen) // no occurrence
        val mirror = RecordingMirror()
        h.controller(mirror).dismiss("ring-A")
        assertNull(h.ledger.get("ring-A"))
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun supersedeNeverMirrors() = runTest {
        val h = mergedH()
        val mirror = RecordingMirror()
        h.controller(mirror).claimFire(ringB) // no occurrence: supersedes the merged ring
        assertEquals(ringB, h.store.state.first())
        assertFalse(h.repo.get("a")!!.enabled)
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun clearStaleNeverMirrors() = runTest {
        val h = mergedH()
        val mirror = RecordingMirror()
        h.controller(mirror).clearStale(isLive = { false })
        assertNull(h.store.state.first())
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun localSnoozeOfMergedRingSplitsEachMemberIntoItsOwnSnooze() = runTest {
        val h = mergedH()
        val mirror = RecordingMirror()
        h.settings.setSnoozeMinutes(7)
        h.controller(mirror).snooze(RingOrigin.LOCAL)
        val t = Instant.parse("2026-03-02T10:07:00Z")
        assertEquals(mergedRing.copy(snoozedUntil = t, merged = emptyList()), h.store.state.first())
        assertEquals(
            setOf(
                SnoozeEntry("ring-1", "a", t, occurrenceX),
                SnoozeEntry("split-1", "b", t, occurrenceY),
                SnoozeEntry("split-2", "c", t, occurrenceZ),
            ),
            h.ledger.all().toSet(),
        )
        assertEquals(listOf(Triple("a", "ring-1", t), Triple("b", "split-1", t), Triple("c", "split-2", t)), h.armed)
        assertEquals(listOf("ring-1" to t), h.notifier.snoozed) // one notification for the group
        assertEquals(mapOf("ring-1" to listOf("ring-1", "split-1", "split-2")), h.groups.all())
        assertEquals(1, h.scheduler.rescheduleCount)
        // Not a dismiss: every one-shot stays on.
        assertTrue(h.repo.get("a")!!.enabled)
        assertTrue(h.repo.get("b")!!.link!!.joinerEnabled)
        assertEquals(listOf(Triple(MirrorKind.SNOOZE, allMembers, t as Instant?)), mirror.calls)
    }

    @Test fun splitMembersShowUpInRingContext() = runTest {
        val h = mergedH()
        val c = h.controller()
        c.snooze()
        val snoozes = c.ringContext("b").snoozes
        assertEquals(setOf("a", "b", "c"), snoozes.map { it.alarmId }.toSet())
        assertEquals(occurrenceY, snoozes.single { it.alarmId == "b" }.occurrenceAt)
    }

    @Test fun remoteSnoozeEndsAtTheGivenTAndNeverMirrors() = runTest {
        val h = mergedH()
        val mirror = RecordingMirror()
        val t = Instant.parse("2026-03-02T10:04:30Z")
        h.controller(mirror).snooze(RingOrigin.REMOTE, until = t)
        assertEquals(t, h.store.state.first()!!.snoozedUntil)
        assertEquals(listOf(t, t, t), h.ledger.all().map { it.until })
        assertEquals(listOf(t, t, t), h.armed.map { it.third })
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun snoozeMemberArmFailureRollsBackAndKeepsTheRingSounding() = runTest {
        val h = mergedH()
        val mirror = RecordingMirror()
        val failing = object : SnoozeArmer by h.armer {
            override fun armSnooze(alarmId: String, ringId: String, at: Instant) {
                if (alarmId == "c") throw IllegalStateException("alarm manager")
                h.armer.armSnooze(alarmId, ringId, at)
            }
        }
        var thrown = false
        try { h.controller(mirror, armer = failing).snooze() } catch (e: IllegalStateException) { thrown = true }
        assertTrue(thrown)
        assertEquals(mergedRing, h.store.state.first()) // still sounding, merged list intact
        assertTrue(h.ledger.all().isEmpty())
        assertEquals(listOf("ring-1", "split-1"), h.armed.map { it.second })
        assertTrue(h.armed.map { it.second }.all { it in h.armer.cancelled }) // every armed snooze taken back
        assertTrue(h.notifier.snoozed.isEmpty())
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun snoozeStateWriteFailureTakesEverySplitSnoozeBack() = runTest {
        val h = mergedH()
        val failingStore = object : RingStateStore by h.store {
            override suspend fun set(state: RingState?) { throw IllegalStateException("disk") }
        }
        var splits = 0
        val c = RingingControllerImpl(h.repo, failingStore, h.settings, h.scheduler, h.armer, h.notifier, h.ledger, newRingId = { "split-${++splits}" }) { now }
        var thrown = false
        try { c.snooze() } catch (e: IllegalStateException) { thrown = true }
        assertTrue(thrown)
        assertTrue(h.ledger.all().isEmpty())
        assertEquals(listOf("ring-1", "split-1", "split-2"), h.armer.cancelled)
        assertTrue(h.notifier.snoozed.isEmpty())
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

    // ---- one occurrence: dismissOccurrence / snoozeOccurrence ----

    private val tenOhFive = Instant.parse("2026-03-02T10:05:00Z")
    private val promoted = RingState("b", "ring-1", ringing.startedAt, null, occurrenceY, listOf(RingMember("c", occurrenceZ)))

    @Test fun remoteStopForThePrimaryOfAMergedRingPromotesTheNextMemberAndKeepsSounding() = runTest {
        val h = mergedH()
        val mirror = RecordingMirror()
        h.controller(mirror).dismissOccurrence("a", occurrenceX, RingOrigin.REMOTE)
        assertEquals(promoted, h.store.state.first()) // same ringId and startedAt: the session plays on
        assertFalse(h.repo.get("a")!!.enabled) // the primary's one-shot is off
        assertTrue(h.repo.get("b")!!.link!!.joinerEnabled) // the others are untouched
        assertTrue(h.ledger.all().isEmpty())
        assertEquals(1, h.scheduler.rescheduleCount)
        assertTrue(mirror.calls.isEmpty())
    }

    // ---- auto-silence: compare-and-dismiss ----

    @Test fun autoSilenceDismissesWhenEveryMemberWasSeenAndMirrorsOnce() = runTest {
        val h = mergedH()
        val mirror = RecordingMirror()
        val seenMore = allMembers + RingMember("gone", occurrenceZ) // a superset: a member already resolved
        assertTrue(h.controller(mirror).autoSilence("ring-1", seenMore))
        h.assertEveryMemberResolved()
        assertEquals(listOf(Triple(MirrorKind.DISMISS, allMembers, null as Instant?)), mirror.calls)
    }

    @Test fun autoSilenceRefusesWhenAMemberJoinedSinceAndWritesNothing() = runTest {
        val h = mergedH()
        val mirror = RecordingMirror()
        assertFalse(h.controller(mirror).autoSilence("ring-1", allMembers.take(2)))
        assertEquals(mergedRing, h.store.state.first())
        assertTrue(h.repo.get("a")!!.enabled)
        assertEquals(0, h.scheduler.rescheduleCount)
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun autoSilenceLeavesARingSnoozedMeanwhileSnoozed() = runTest {
        val snoozed = ringA.copy(snoozedUntil = tenOhFive) // still pending at 10:00
        val h = H(listOf(alarm("a", emptySet())), snoozed).also { it.ledger.put("ring-A", "a", tenOhFive) }
        assertTrue(h.controller().autoSilence("ring-A", emptyList()))
        assertEquals(snoozed, h.store.state.first())
        assertNotNull(h.ledger.get("ring-A"))
        assertTrue(h.armer.cancelled.isEmpty())
        assertTrue(h.repo.get("a")!!.enabled)
    }

    @Test fun autoSilenceDismissesARingWhoseSnoozeAlreadyFired() = runTest {
        // The snooze fired at 7:10 but its claim couldn't write: the fallback ring under ring-A
        // still finds its snoozed slot. Nothing is pending, so this is an ordinary auto-silence.
        val occ = Instant.parse("2026-03-02T07:00:00Z")
        val h = H(listOf(alarm("a", emptySet())), ringA.copy(occurrenceAt = occ)).also { it.ledger.put("ring-A", "a", sevenTen, occ) }
        val mirror = RecordingMirror()
        assertTrue(h.controller(mirror).autoSilence("ring-A", emptyList()))
        assertNull(h.store.state.first())
        assertNull(h.ledger.get("ring-A"))
        assertEquals(listOf("ring-A"), h.armer.cancelled)
        assertFalse(h.repo.get("a")!!.enabled)
        assertEquals(listOf(Triple(MirrorKind.DISMISS, listOf(RingMember("a", occ)), null as Instant?)), mirror.calls)
    }

    @Test fun autoSilenceLeavesASnoozeParkedMeanwhileAlone() = runTest {
        // Snoozed while auto-silence waited, then parked behind a newer ring: the snooze stands.
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), soundingX.copy(alarmId = "b", ringId = "ring-B"))
        h.ledger.put("ring-P", "a", tenOhFive, occurrenceX)
        val mirror = RecordingMirror()
        assertTrue(h.controller(mirror).autoSilence("ring-P", emptyList()))
        assertNotNull(h.ledger.get("ring-P"))
        assertTrue(h.armer.cancelled.isEmpty())
        assertTrue(h.notifier.cancelledSnoozed.isEmpty())
        assertTrue(h.repo.get("a")!!.enabled)
        assertEquals("ring-B", h.store.state.first()!!.ringId)
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun autoSilenceOfARingNotInTheSlotDropsItsParkedSnoozeAsADismissWould() = runTest {
        // A parked snooze whose time (7:10) has passed: nothing of it is pending any more.
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), soundingX.copy(alarmId = "b", ringId = "ring-B"))
        h.ledger.put("ring-P", "a", sevenTen, occurrenceX)
        val mirror = RecordingMirror()
        assertTrue(h.controller(mirror).autoSilence("ring-P", emptyList()))
        assertNull(h.ledger.get("ring-P"))
        assertEquals(listOf("ring-P"), h.armer.cancelled)
        assertFalse(h.repo.get("a")!!.enabled)
        assertEquals("ring-B", h.store.state.first()!!.ringId) // the slot ring is untouched
        assertEquals(listOf(Triple(MirrorKind.DISMISS, listOf(RingMember("a", occurrenceX)), null as Instant?)), mirror.calls)
    }

    @Test fun writeUnlessRingingStillRefusesForAPromotedMember() = runTest {
        // b fired at 10:03 while a (started 09:59) was sounding: after a's stop, b is the primary
        // of a ring that started before its own occurrence.
        val bAt = Instant.parse("2026-03-02T10:03:00Z")
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), soundingX.copy(merged = listOf(RingMember("b", bAt))))
        val c = h.controller()
        c.dismissOccurrence("a", occurrenceX, RingOrigin.REMOTE)
        assertEquals("b", h.store.state.first()!!.alarmId)
        var writes = 0
        assertFalse(c.writeUnlessRinging("b", bAt) { writes++ })
        assertEquals(0, writes)
        assertTrue(c.writeUnlessRinging("b", bAt.plusSeconds(86400)) { writes++ }) // a later occurrence
    }

    @Test fun remoteStopForAMergedMemberRemovesOnlyIt() = runTest {
        val h = mergedH()
        val mirror = RecordingMirror()
        h.controller(mirror).dismissOccurrence("b", occurrenceY, RingOrigin.REMOTE)
        assertEquals(mergedRing.copy(merged = listOf(RingMember("c", occurrenceZ))), h.store.state.first())
        assertFalse(h.repo.get("b")!!.link!!.joinerEnabled)
        assertTrue(h.repo.get("a")!!.enabled)
        assertEquals(1, h.scheduler.rescheduleCount)
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun remoteSnoozeForThePrimarySplitsItOutAndKeepsTheRestSounding() = runTest {
        val h = mergedH()
        val mirror = RecordingMirror()
        val c = h.controller(mirror)
        c.snoozeOccurrence("a", occurrenceX, tenOhFive, RingOrigin.REMOTE)
        assertEquals(promoted, h.store.state.first())
        assertEquals(listOf(SnoozeEntry("split-1", "a", tenOhFive, occurrenceX)), h.ledger.all())
        assertEquals(listOf(Triple("a", "split-1", tenOhFive)), h.armed)
        assertEquals(listOf("split-1" to tenOhFive), h.notifier.snoozed)
        assertTrue(h.repo.get("a")!!.enabled) // not a dismiss
        assertEquals(1, h.scheduler.rescheduleCount)
        assertTrue(mirror.calls.isEmpty())
        val ctx = c.ringContext("a")
        assertEquals(listOf(ParkedSnooze("a", occurrenceX, tenOhFive)), ctx.snoozes)
        assertEquals(promoted.merged, ctx.merged)
    }

    @Test fun remoteSnoozeForAMergedMemberSplitsItOutAndKeepsTheRingSounding() = runTest {
        val h = mergedH()
        val c = h.controller()
        c.snoozeOccurrence("c", occurrenceZ, tenOhFive, RingOrigin.REMOTE)
        assertEquals(mergedRing.copy(merged = listOf(RingMember("b", occurrenceY))), h.store.state.first())
        assertEquals(listOf(SnoozeEntry("split-1", "c", tenOhFive, occurrenceZ)), h.ledger.all())
        assertEquals(listOf(Triple("c", "split-1", tenOhFive)), h.armed)
        assertEquals(listOf("split-1" to tenOhFive), h.notifier.snoozed)
        assertEquals(listOf(ParkedSnooze("c", occurrenceZ, tenOhFive)), c.ringContext("c").snoozes)
    }

    @Test fun splitArmFailureKeepsTheRingSoundingUnchanged() = runTest {
        val h = mergedH()
        h.armFailure = IllegalStateException("alarm manager")
        val thrown = runCatching { h.controller().snoozeOccurrence("a", occurrenceX, tenOhFive, RingOrigin.REMOTE) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(mergedRing, h.store.state.first())
        assertTrue(h.ledger.all().isEmpty())
        assertTrue(h.notifier.snoozed.isEmpty())
    }

    @Test fun splitStateWriteFailureTakesTheSnoozeBack() = runTest {
        val h = mergedH()
        val failing = object : RingStateStore by h.store {
            override suspend fun set(state: RingState?) { throw IllegalStateException("disk") }
        }
        val thrown = runCatching { h.controller(store = failing).snoozeOccurrence("b", occurrenceY, tenOhFive, RingOrigin.REMOTE) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(mergedRing, h.store.state.first())
        assertTrue(h.ledger.all().isEmpty())
        assertEquals(listOf("split-1"), h.armer.cancelled)
        assertTrue(h.notifier.snoozed.isEmpty())
    }

    @Test fun remoteStopForASoloSlotRingEndsIt() = runTest {
        val h = H(listOf(alarm("a", emptySet())), soundingX)
        val mirror = RecordingMirror()
        h.controller(mirror).dismissOccurrence("a", occurrenceX, RingOrigin.REMOTE)
        assertNull(h.store.state.first())
        assertFalse(h.repo.get("a")!!.enabled)
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun remoteSnoozeForASoloSlotRingSnoozesItToExactlyT() = runTest {
        val h = H(listOf(alarm("a", emptySet())), soundingX)
        h.settings.setSnoozeMinutes(7)
        val mirror = RecordingMirror()
        h.controller(mirror).snoozeOccurrence("a", occurrenceX, tenOhFive, RingOrigin.REMOTE)
        assertEquals(soundingX.copy(snoozedUntil = tenOhFive), h.store.state.first())
        assertEquals(listOf(SnoozeEntry("ring-1", "a", tenOhFive, occurrenceX)), h.ledger.all())
        assertEquals(listOf("ring-1" to tenOhFive), h.notifier.snoozed)
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun stopForASnoozedSlotRingEndsIt() = runTest {
        val h = H(listOf(alarm("a", emptySet())), soundingX.copy(snoozedUntil = tenOhFive))
        h.ledger.put("ring-1", "a", tenOhFive, occurrenceX)
        h.controller().dismissOccurrence("a", occurrenceX, RingOrigin.REMOTE)
        assertNull(h.store.state.first())
        assertTrue(h.ledger.all().isEmpty())
        assertEquals(listOf("ring-1"), h.armer.cancelled)
        assertFalse(h.repo.get("a")!!.enabled)
    }

    @Test fun snoozeForASnoozedSlotRingMovesItOnlyLater() = runTest {
        val snoozed = soundingX.copy(snoozedUntil = tenOhFive)
        val h = H(listOf(alarm("a", emptySet())), snoozed)
        h.ledger.put("ring-1", "a", tenOhFive, occurrenceX)
        val c = h.controller()
        c.snoozeOccurrence("a", occurrenceX, tenOhFive, RingOrigin.REMOTE) // equal: nothing
        c.snoozeOccurrence("a", occurrenceX, tenOhFive.minusSeconds(60), RingOrigin.REMOTE) // earlier: nothing
        assertEquals(snoozed, h.store.state.first())
        assertTrue(h.armed.isEmpty())
        assertEquals(0, h.scheduler.rescheduleCount)
        assertTrue(h.notifier.snoozed.isEmpty())

        val later = tenOhFive.plusSeconds(120)
        c.snoozeOccurrence("a", occurrenceX, later, RingOrigin.REMOTE)
        assertEquals(snoozed.copy(snoozedUntil = later), h.store.state.first())
        assertEquals(listOf(SnoozeEntry("ring-1", "a", later, occurrenceX)), h.ledger.all())
        assertEquals(listOf(Triple("a", "ring-1", later)), h.armed) // same ringId
        assertEquals(listOf("ring-1" to later), h.notifier.snoozed)
        assertEquals(1, h.scheduler.rescheduleCount)
    }

    @Test fun snoozeForAParkedSnoozeMovesItOnlyLater() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringB)
        h.ledger.put("ring-A", "a", sevenTen, occurrenceX)
        val c = h.controller()
        c.snoozeOccurrence("a", occurrenceX, sevenTen, RingOrigin.REMOTE)
        c.snoozeOccurrence("a", occurrenceX, sevenOhFive, RingOrigin.REMOTE)
        assertEquals(SnoozeEntry("ring-A", "a", sevenTen, occurrenceX), h.ledger.get("ring-A"))
        assertTrue(h.armed.isEmpty())
        assertEquals(0, h.scheduler.rescheduleCount)

        val later = sevenTen.plusSeconds(300)
        c.snoozeOccurrence("a", occurrenceX, later, RingOrigin.REMOTE)
        assertEquals(listOf(SnoozeEntry("ring-A", "a", later, occurrenceX)), h.ledger.all())
        assertEquals(listOf(Triple("a", "ring-A", later)), h.armed)
        assertEquals(listOf("ring-A" to later), h.notifier.snoozed)
        assertEquals(ringB, h.store.state.first()) // the slot ring is untouched
        assertEquals(1, h.scheduler.rescheduleCount)
    }

    @Test fun movedSnoozeGoesBackWhenTheLedgerWriteFails() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringB)
        h.ledger.put("ring-A", "a", sevenTen, occurrenceX)
        var fail = true
        val flaky = object : SnoozeLedger by h.ledger {
            override fun put(ringId: String, alarmId: String, until: Instant, occurrenceAt: Instant?) {
                if (fail) { fail = false; throw IllegalStateException("prefs") }
                h.ledger.put(ringId, alarmId, until, occurrenceAt)
            }
        }
        val later = sevenTen.plusSeconds(300)
        val thrown = runCatching { h.controller(ledger = flaky).snoozeOccurrence("a", occurrenceX, later, RingOrigin.REMOTE) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(SnoozeEntry("ring-A", "a", sevenTen, occurrenceX), h.ledger.get("ring-A"))
        assertEquals(listOf(Triple("a", "ring-A", later), Triple("a", "ring-A", sevenTen)), h.armed) // re-armed at the old end
        assertTrue(h.notifier.snoozed.isEmpty())
    }

    @Test fun stopForAParkedSnoozeDropsItAndLeavesTheSlotAlone() = runTest {
        val h = H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringB)
        h.ledger.put("ring-A", "a", sevenTen, occurrenceX)
        h.controller().dismissOccurrence("a", occurrenceX, RingOrigin.REMOTE)
        assertNull(h.ledger.get("ring-A"))
        assertEquals(listOf("ring-A"), h.armer.cancelled)
        assertEquals(ringB, h.store.state.first())
        assertFalse(h.repo.get("a")!!.enabled)
    }

    @Test fun alreadyResolvedOccurrenceIsANoOp() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        h.fired.markRang("a", occurrenceX)
        val mirror = RecordingMirror()
        val c = h.controller(mirror)
        c.dismissOccurrence("a", occurrenceX, RingOrigin.LOCAL)
        c.snoozeOccurrence("a", occurrenceX, tenOhFive, RingOrigin.LOCAL)
        assertTrue(h.repo.get("a")!!.enabled)
        assertTrue(h.ledger.all().isEmpty())
        assertTrue(h.armed.isEmpty())
        assertEquals(0, h.scheduler.rescheduleCount)
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun resolvingAnOlderOccurrenceNeverMovesTheFiredLedgerBack() = runTest {
        // A later occurrence already rang: recording the older X over it would let that one replay.
        val h = H(listOf(alarm("a", setOf(DayOfWeek.MONDAY))), null)
        val later = occurrenceX.plusSeconds(7 * 86400)
        h.fired.markRang("a", later)
        val c = h.controller()
        c.dismissOccurrence("a", occurrenceX, RingOrigin.REMOTE)
        assertEquals(later, h.fired.lastRang("a"))
        c.snoozeOccurrence("a", occurrenceX, tenOhFive, RingOrigin.REMOTE)
        assertEquals(later, h.fired.lastRang("a"))
        assertEquals(listOf(SnoozeEntry("split-1", "a", tenOhFive, occurrenceX)), h.ledger.all()) // the snooze still stands
    }

    @Test fun earlyStopRecordsTheOccurrenceAndTurnsAOneShotOff() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        h.controller().dismissOccurrence("a", occurrenceX, RingOrigin.REMOTE)
        assertEquals(occurrenceX, h.fired.lastRang("a"))
        assertFalse(h.repo.get("a")!!.enabled)
        assertNull(h.store.state.first())
        assertEquals(1, h.scheduler.rescheduleCount)
    }

    @Test fun earlyStopOfALinkedOneShotTurnsOnlyOurSideOff() = runTest {
        val h = H(listOf(linkedAlarm("a")), null)
        h.controller().dismissOccurrence("a", occurrenceX, RingOrigin.REMOTE)
        assertTrue(h.repo.get("a")!!.enabled)
        assertFalse(h.repo.get("a")!!.link!!.joinerEnabled)
    }

    @Test fun earlySnoozeRecordsTheOccurrenceAndArmsTheSnooze() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        val c = h.controller()
        c.snoozeOccurrence("a", occurrenceX, tenOhFive, RingOrigin.REMOTE)
        assertEquals(occurrenceX, h.fired.lastRang("a"))
        assertEquals(listOf(SnoozeEntry("split-1", "a", tenOhFive, occurrenceX)), h.ledger.all())
        assertEquals(listOf(Triple("a", "split-1", tenOhFive)), h.armed)
        assertEquals(listOf("split-1" to tenOhFive), h.notifier.snoozed)
        assertTrue(h.repo.get("a")!!.enabled) // a snooze, not a dismiss
        assertNull(h.store.state.first())
        assertEquals(1, h.scheduler.rescheduleCount)
        val ctx = c.ringContext("a")
        assertEquals(listOf(ParkedSnooze("a", occurrenceX, tenOhFive)), ctx.snoozes)
        assertEquals(occurrenceX, ctx.lastRang)
    }

    @Test fun earlySnoozeArmFailureLeavesTheOccurrenceUnresolved() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        h.armFailure = IllegalStateException("alarm manager")
        val thrown = runCatching { h.controller().snoozeOccurrence("a", occurrenceX, tenOhFive, RingOrigin.REMOTE) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertNull(h.fired.lastRang("a")) // I1: X still rings
        assertTrue(h.ledger.all().isEmpty())
    }

    @Test fun earlySnoozeLedgerFailureCancelsTheArmAndLeavesTheOccurrenceUnresolved() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        val failing = object : SnoozeLedger by h.ledger {
            override fun put(ringId: String, alarmId: String, until: Instant, occurrenceAt: Instant?) { throw IllegalStateException("prefs") }
        }
        val thrown = runCatching { h.controller(ledger = failing).snoozeOccurrence("a", occurrenceX, tenOhFive, RingOrigin.REMOTE) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertNull(h.fired.lastRang("a"))
        assertEquals(listOf("split-1"), h.armer.cancelled)
        assertTrue(h.notifier.snoozed.isEmpty())
    }

    @Test fun earlySnoozeFiredLedgerFailureTakesTheSnoozeBack() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        val failing = object : FiredLedger by h.fired {
            override fun markRang(alarmId: String, occurrence: Instant) { throw IllegalStateException("prefs") }
        }
        val thrown = runCatching { h.controller(fired = failing).snoozeOccurrence("a", occurrenceX, tenOhFive, RingOrigin.REMOTE) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(h.ledger.all().isEmpty())
        assertEquals(listOf("split-1"), h.armer.cancelled)
        assertTrue(h.notifier.snoozed.isEmpty())
    }

    /** Every path that resolves one occurrence, with the single member LOCAL must report. */
    private class OccurrenceCase(
        val name: String,
        val h: () -> H,
        val kind: MirrorKind,
        val member: RingMember,
        val until: Instant?,
        /** REMOTE's notice: whether it ends the ring, or null for none (parked, early, or nothing done). */
        val endsRing: Boolean?,
        val act: suspend (RingingControllerImpl, RingOrigin) -> Unit,
    )

    private val occurrenceCases: List<OccurrenceCase> get() {
        val later = tenOhFive.plusSeconds(120)
        val a = RingMember("a", occurrenceX)
        val snoozedSlot = { H(listOf(alarm("a", emptySet())), soundingX.copy(snoozedUntil = tenOhFive)).also { it.ledger.put("ring-1", "a", tenOhFive, occurrenceX) } }
        val parked = { H(listOf(alarm("a", emptySet()), alarm("b", emptySet())), ringB).also { it.ledger.put("ring-A", "a", sevenTen, occurrenceX) } }
        val solo = { H(listOf(alarm("a", emptySet())), soundingX) }
        val early = { H(listOf(alarm("a", setOf(DayOfWeek.MONDAY))), null) }
        fun stop(name: String, h: () -> H, m: RingMember, endsRing: Boolean?) =
            OccurrenceCase(name, h, MirrorKind.DISMISS, m, null, endsRing) { c, o -> c.dismissOccurrence(m.alarmId, m.occurrenceAt, o) }
        fun snooze(name: String, h: () -> H, m: RingMember, t: Instant, endsRing: Boolean?) =
            OccurrenceCase(name, h, MirrorKind.SNOOZE, m, t, endsRing) { c, o -> c.snoozeOccurrence(m.alarmId, m.occurrenceAt, t, o) }
        return listOf(
            stop("stop primary of merged", ::mergedH, a, endsRing = false),
            stop("stop merged member", ::mergedH, RingMember("b", occurrenceY), endsRing = false),
            stop("stop solo slot", solo, a, endsRing = true),
            stop("stop snoozed slot", snoozedSlot, a, endsRing = true),
            stop("stop parked", parked, a, endsRing = null),
            stop("stop early", early, a, endsRing = null),
            snooze("snooze primary of merged", ::mergedH, a, tenOhFive, endsRing = false),
            snooze("snooze merged member", ::mergedH, RingMember("c", occurrenceZ), tenOhFive, endsRing = false),
            snooze("snooze solo slot", solo, a, tenOhFive, endsRing = true),
            snooze("snooze snoozed slot later", snoozedSlot, a, later, endsRing = true),
            snooze("snooze parked later", parked, a, later, endsRing = null),
            snooze("snooze early", early, a, tenOhFive, endsRing = null),
        )
    }

    @Test fun localResolutionMirrorsJustThatMemberOnceOnEveryPath() = runTest {
        for (case in occurrenceCases) {
            val mirror = RecordingMirror()
            case.act(case.h().controller(mirror), RingOrigin.LOCAL)
            assertEquals(case.name, listOf(Triple(case.kind, listOf(case.member), case.until)), mirror.calls)
        }
    }

    @Test fun remoteResolutionNeverCallsThePortOnAnyPath() = runTest {
        for (case in occurrenceCases) {
            val mirror = RecordingMirror()
            case.act(case.h().controller(mirror), RingOrigin.REMOTE)
            assertTrue(case.name, mirror.calls.isEmpty())
        }
    }

    @Test fun localSnoozeThatDoesNotMoveTheSnoozeMirrorsNothing() = runTest {
        val h = H(listOf(alarm("a", emptySet())), soundingX.copy(snoozedUntil = tenOhFive))
        h.ledger.put("ring-1", "a", tenOhFive, occurrenceX)
        val mirror = RecordingMirror()
        h.controller(mirror).snoozeOccurrence("a", occurrenceX, tenOhFive, RingOrigin.LOCAL)
        assertTrue(mirror.calls.isEmpty())
    }

    // ---- REMOTE notices for the ringing screen ----

    /** The store [h] writes through, recording the latest notice each write sees. */
    private fun H.noticeRecordingStore(seen: MutableList<RemoteRingEnd?>) = object : RingStateStore by store {
        override suspend fun set(state: RingState?) { seen += notices.latest.value; store.set(state) }
    }

    @Test fun remoteOccurrenceResolutionEmitsOneNoticeBeforeTheStateWrite() = runTest {
        for (case in occurrenceCases) {
            val h = case.h()
            val seen = mutableListOf<RemoteRingEnd?>()
            case.act(h.controller(store = h.noticeRecordingStore(seen)), RingOrigin.REMOTE)
            val notice = h.notices.latest.value
            if (case.endsRing == null) {
                assertNull(case.name, notice)
                continue
            }
            val expected = RemoteRingEnd("ring-1", case.member, case.kind, case.until, case.endsRing, seq = 1)
            assertEquals(case.name, expected, notice)
            assertEquals(case.name, expected, seen.first()) // the first write already saw it
        }
    }

    @Test fun localResolutionNeverEmitsANotice() = runTest {
        for (case in occurrenceCases) {
            val h = case.h()
            case.act(h.controller(), RingOrigin.LOCAL)
            assertNull(case.name, h.notices.latest.value)
        }
        val h = mergedH()
        h.controller().dismiss("ring-1", RingOrigin.LOCAL)
        val s = mergedH()
        s.controller().snooze(RingOrigin.LOCAL)
        assertNull(h.notices.latest.value)
        assertNull(s.notices.latest.value)
    }

    @Test fun remoteDismissOfTheSlotRingEmitsAnEndingNoticeBeforeClearingIt() = runTest {
        val h = H(listOf(alarm("a", emptySet())), soundingX)
        val seen = mutableListOf<RemoteRingEnd?>()
        h.controller(store = h.noticeRecordingStore(seen)).dismiss("ring-1", RingOrigin.REMOTE)
        val expected = RemoteRingEnd("ring-1", RingMember("a", occurrenceX), MirrorKind.DISMISS, null, endsRing = true, seq = 1)
        assertEquals(expected, h.notices.latest.value)
        assertEquals(listOf<RemoteRingEnd?>(expected), seen)
    }

    @Test fun remoteDismissOfAMergedSlotRingEmitsAPromotionNoticeBeforeTheWrite() = runTest {
        val h = mergedH()
        val seen = mutableListOf<RemoteRingEnd?>()
        h.controller(store = h.noticeRecordingStore(seen)).dismiss("ring-1", RingOrigin.REMOTE)
        val expected = RemoteRingEnd("ring-1", RingMember("a", occurrenceX), MirrorKind.DISMISS, null, endsRing = false, seq = 1)
        assertEquals(expected, h.notices.latest.value)
        assertEquals(listOf<RemoteRingEnd?>(expected), seen)
        assertEquals(promoted, h.store.state.first())
    }

    @Test fun remoteDismissOfAnotherRingEmitsNothing() = runTest {
        val h = mergedH()
        h.controller().dismiss("other", RingOrigin.REMOTE)
        assertNull(h.notices.latest.value)
    }

    @Test fun remoteSnoozeOfTheSlotRingEmitsAnEndingNoticeBeforeTheWrite() = runTest {
        val h = mergedH()
        val seen = mutableListOf<RemoteRingEnd?>()
        h.controller(store = h.noticeRecordingStore(seen)).snooze(RingOrigin.REMOTE, until = tenOhFive)
        val expected = RemoteRingEnd("ring-1", RingMember("a", occurrenceX), MirrorKind.SNOOZE, tenOhFive, endsRing = true, seq = 1)
        assertEquals(expected, h.notices.latest.value)
        assertEquals(listOf<RemoteRingEnd?>(expected), seen)
    }

    /** [h]'s store, but every write throws. */
    private fun H.failingWrites() = object : RingStateStore by store {
        override suspend fun set(state: RingState?) = throw IllegalStateException("disk")
    }

    @Test fun remoteDismissWhoseWriteFailsTakesItsNoticeBack() = runTest {
        // Left up, a later LOCAL Stop (or auto-silence) clearing the ring would read as the partner's.
        val h = mergedH()
        assertTrue(runCatching { h.controller(store = h.failingWrites()).dismiss("ring-1", RingOrigin.REMOTE) }.isFailure)
        assertEquals(mergedRing, h.store.state.first())
        assertNull(h.notices.latest.value)
    }

    @Test fun remoteOccurrenceDismissWhoseWriteFailsTakesItsNoticeBack() = runTest {
        val solo = H(listOf(alarm("a", emptySet())), soundingX)
        assertTrue(runCatching { solo.controller(store = solo.failingWrites()).dismissOccurrence("a", occurrenceX, RingOrigin.REMOTE) }.isFailure)
        assertNull(solo.notices.latest.value)
        val merged = mergedH()
        assertTrue(runCatching { merged.controller(store = merged.failingWrites()).dismissOccurrence("b", occurrenceY, RingOrigin.REMOTE) }.isFailure)
        assertNull(merged.notices.latest.value)
    }

    @Test fun remoteSnoozeWhoseWriteFailsTakesItsNoticeBack() = runTest {
        val h = mergedH()
        assertTrue(runCatching { h.controller(store = h.failingWrites()).snooze(RingOrigin.REMOTE, tenOhFive) }.isFailure)
        assertEquals(mergedRing, h.store.state.first())
        assertTrue(h.ledger.all().isEmpty()) // the rollback is unchanged
        assertNull(h.notices.latest.value)
    }

    @Test fun remoteSplitWhoseWriteFailsTakesItsNoticeBack() = runTest {
        val h = mergedH()
        assertTrue(runCatching { h.controller(store = h.failingWrites()).snoozeOccurrence("b", occurrenceY, tenOhFive, RingOrigin.REMOTE) }.isFailure)
        assertEquals(mergedRing, h.store.state.first())
        assertTrue(h.ledger.all().isEmpty())
        assertEquals(listOf("split-1"), h.armer.cancelled)
        assertNull(h.notices.latest.value)
    }

    @Test fun remoteMoveOfASnoozedSlotWhoseWriteFailsTakesItsNoticeBack() = runTest {
        val snoozed = soundingX.copy(snoozedUntil = tenOhFive)
        val h = H(listOf(alarm("a", emptySet())), snoozed)
        h.ledger.put("ring-1", "a", tenOhFive, occurrenceX)
        val later = tenOhFive.plusSeconds(120)
        assertTrue(runCatching { h.controller(store = h.failingWrites()).snoozeOccurrence("a", occurrenceX, later, RingOrigin.REMOTE) }.isFailure)
        assertEquals(snoozed, h.store.state.first())
        assertEquals(SnoozeEntry("ring-1", "a", tenOhFive, occurrenceX), h.ledger.get("ring-1")) // back at the old end
        assertEquals(Triple("a", "ring-1", tenOhFive), h.armed.last())
        assertNull(h.notices.latest.value)
    }

    @Test fun remoteDismissWhoseLaterStepFailsKeepsItsNotice() = runTest {
        // The ring did end: the notice explains it even though a step after the write threw.
        val h = H(listOf(alarm("a", emptySet())), soundingX)
        val failingScheduler = object : AlarmScheduler { override suspend fun rescheduleAll() = throw IllegalStateException("alarm manager") }
        val c = RingingControllerImpl(h.repo, h.store, h.settings, failingScheduler, h.armer, h.notifier, h.ledger, remoteNotices = h.notices) { now }
        assertTrue(runCatching { c.dismiss("ring-1", RingOrigin.REMOTE) }.isFailure)
        assertNull(h.store.state.first())
        assertEquals(RemoteRingEnd("ring-1", RingMember("a", occurrenceX), MirrorKind.DISMISS, null, endsRing = true, seq = 1), h.notices.latest.value)
    }

    @Test fun aNoticeAfterAWithdrawnOneIsStillNewer() = runTest {
        val notices = RemoteRingNotices()
        val first = notices.emit(RemoteRingEnd("r", null, MirrorKind.DISMISS, null, endsRing = true))
        notices.withdraw(first)
        assertNull(notices.latest.value)
        val second = notices.emit(RemoteRingEnd("r", null, MirrorKind.DISMISS, null, endsRing = true))
        assertTrue(second.seq > first.seq) // a screen that handled the first still shows this one
        notices.withdraw(first) // only the notice it names
        assertEquals(second, notices.latest.value)
    }

    @Test fun remoteNoticeForALegacyPrimaryHasNoMember() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        h.controller().dismiss("ring-1", RingOrigin.REMOTE)
        assertEquals(RemoteRingEnd("ring-1", null, MirrorKind.DISMISS, null, endsRing = true, seq = 1), h.notices.latest.value)
    }

    // ---- snoozed notifications name their alarm ----

    @Test fun mergedSnoozeLabelsTheGroupNotificationWithEveryMembersAlarm() = runTest {
        val h = mergedH()
        h.repo.updateLink("b") { it.copy(overrideLabel = "Gym") }
        h.controller().snooze(RingOrigin.LOCAL, until = tenOhFive)
        assertEquals(listOf("ring-1"), h.notifier.snoozed.map { it.first })
        assertEquals(listOf(listOf("a", "Gym", "c")), h.notifier.snoozedLabels)
    }

    @Test fun splitOutMoveAndEarlySnoozeLabelTheirNotification() = runTest {
        val h = mergedH()
        val c = h.controller()
        c.snoozeOccurrence("c", occurrenceZ, tenOhFive, RingOrigin.REMOTE) // split out
        c.snoozeOccurrence("c", occurrenceZ, tenOhFive.plusSeconds(60), RingOrigin.REMOTE) // moved
        val early = H(listOf(alarm("e", setOf(DayOfWeek.MONDAY))), null)
        early.controller().snoozeOccurrence("e", occurrenceX, tenOhFive, RingOrigin.REMOTE)
        assertEquals(listOf(listOf("c"), listOf("c")), h.notifier.snoozedLabels)
        assertEquals(listOf(listOf("e")), early.notifier.snoozedLabels)
    }

    @Test fun anUnreadableAlarmSnoozesWithoutALabel() = runTest {
        val h = H(listOf(alarm("a", emptySet())), soundingX)
        val failingRepo = object : AlarmRepository by h.repo {
            override suspend fun get(id: String): Alarm? = throw IllegalStateException("db")
        }
        RingingControllerImpl(failingRepo, h.store, h.settings, h.scheduler, h.armer, h.notifier, h.ledger) { now }.snooze(until = tenOhFive)
        assertEquals(listOf("ring-1" to tenOhFive), h.notifier.snoozed)
        assertEquals(listOf(emptyList<String>()), h.notifier.snoozedLabels)
    }

    // ---- snooze groups: one notification and one Dismiss per snoozed merged ring ----

    private val laterT = tenOhFive.plusSeconds(300)
    private val bMember = RingMember("b", occurrenceY)
    private val cMember = RingMember("c", occurrenceZ)

    /** [mergedH] snoozed LOCAL until [tenOhFive]: ring-1 (a), split-1 (b), split-2 (c), grouped as ring-1. */
    private suspend fun snoozedGroup(mirror: RingMirrorPort = NoOpRingMirrorPort, groups: SnoozeGroups? = null): Pair<H, RingingControllerImpl> {
        val h = mergedH()
        val c = if (groups == null) h.controller(mirror) else h.controller(mirror, groups = groups)
        c.snooze(RingOrigin.LOCAL, until = tenOhFive)
        return h to c
    }

    @Test fun aSoloSnoozeWritesNoGroup() = runTest {
        val h = H(listOf(alarm("a", emptySet())), soundingX)
        h.controller().snooze(until = tenOhFive)
        assertTrue(h.groups.all().isEmpty())
        assertEquals(listOf("ring-1" to tenOhFive), h.notifier.snoozed)
        assertEquals(listOf(listOf("a")), h.notifier.snoozedLabels)
    }

    @Test fun dismissOnTheGroupNotificationResolvesEveryMemberAndMirrorsThemOnce() = runTest {
        val mirror = RecordingMirror()
        val (h, c) = snoozedGroup(mirror)
        c.dismiss("ring-1", RingOrigin.LOCAL) // the notification's Dismiss carries the groupId
        assertNull(h.store.state.first())
        assertTrue(h.ledger.all().isEmpty())
        assertEquals(listOf("ring-1", "split-1", "split-2"), h.armer.cancelled)
        assertFalse(h.repo.get("a")!!.enabled)
        assertFalse(h.repo.get("b")!!.link!!.joinerEnabled)
        assertTrue(h.repo.get("c")!!.enabled) // repeating
        assertEquals(Triple(MirrorKind.DISMISS, allMembers, null as Instant?), mirror.calls.last())
        assertEquals(2, mirror.calls.size) // the snooze, then this one dismiss
        assertEquals(2, h.scheduler.rescheduleCount) // the snooze's and one for the dismiss
        assertEquals(listOf("ring-1"), h.notifier.cancelledSnoozed)
        assertTrue(h.groups.all().isEmpty())
    }

    @Test fun groupDismissStillResolvesTheRestAfterThePrimaryWasStoppedRemotely() = runTest {
        val mirror = RecordingMirror()
        val (h, c) = snoozedGroup(mirror)
        c.dismissOccurrence("a", occurrenceX, RingOrigin.REMOTE)
        assertNull(h.store.state.first())
        assertEquals("ring-1" to tenOhFive, h.notifier.snoozed.last()) // the group's notification, relabelled
        assertEquals(listOf("b", "c"), h.notifier.snoozedLabels.last())
        assertEquals(1, mirror.calls.size) // REMOTE: nothing
        c.dismiss("ring-1", RingOrigin.LOCAL)
        assertTrue(h.ledger.all().isEmpty())
        assertFalse(h.repo.get("b")!!.link!!.joinerEnabled)
        assertEquals(Triple(MirrorKind.DISMISS, listOf(bMember, cMember), null as Instant?), mirror.calls.last())
        assertEquals("ring-1", h.notifier.cancelledSnoozed.last())
        assertTrue(h.groups.all().isEmpty())
    }

    @Test fun remoteRingIdStopOfASnoozedGroupResolvesOnlyThePrimary() = runTest {
        val mirror = RecordingMirror()
        val (h, c) = snoozedGroup(mirror)
        c.dismiss("ring-1", RingOrigin.REMOTE)
        assertNull(h.store.state.first())
        assertEquals(setOf("split-1", "split-2"), h.ledger.all().map { it.ringId }.toSet())
        assertEquals(listOf("b", "c"), h.notifier.snoozedLabels.last())
        assertEquals(1, mirror.calls.size)
    }

    @Test fun remoteStopOfOneGroupedMemberResolvesOnlyItAndRelabelsTheGroup() = runTest {
        val mirror = RecordingMirror()
        val (h, c) = snoozedGroup(mirror)
        c.dismissOccurrence("b", occurrenceY, RingOrigin.REMOTE)
        assertEquals(setOf("ring-1", "split-2"), h.ledger.all().map { it.ringId }.toSet())
        assertEquals(tenOhFive, h.store.state.first()!!.snoozedUntil) // the slot stays snoozed
        assertFalse(h.repo.get("b")!!.link!!.joinerEnabled)
        assertTrue(h.repo.get("a")!!.enabled)
        assertEquals("ring-1" to tenOhFive, h.notifier.snoozed.last())
        assertEquals(listOf("a", "c"), h.notifier.snoozedLabels.last())
        assertFalse("ring-1" in h.notifier.cancelledSnoozed)
        assertEquals(1, mirror.calls.size) // REMOTE never mirrors
        c.dismissOccurrence("a", occurrenceX, RingOrigin.REMOTE)
        assertEquals(listOf("c"), h.notifier.snoozedLabels.last())
        c.dismissOccurrence("c", occurrenceZ, RingOrigin.REMOTE) // the last one: the notification goes
        assertTrue(h.ledger.all().isEmpty())
        assertEquals("ring-1", h.notifier.cancelledSnoozed.last())
        assertTrue(h.groups.all().isEmpty())
        assertEquals(1, mirror.calls.size)
    }

    @Test fun localStopOfOneGroupedMemberResolvesAndMirrorsOnlyIt() = runTest {
        val mirror = RecordingMirror()
        val (h, c) = snoozedGroup(mirror)
        c.dismissOccurrence("b", occurrenceY, RingOrigin.LOCAL) // e.g. Stop on the partner tab's joined copy
        assertEquals(setOf("ring-1", "split-2"), h.ledger.all().map { it.ringId }.toSet())
        assertEquals(listOf("a", "c"), h.notifier.snoozedLabels.last())
        assertEquals(Triple(MirrorKind.DISMISS, listOf(bMember), null as Instant?), mirror.calls.last())
    }

    @Test fun remoteSnoozeMovingOneMemberLaterTakesItOutOfTheGroup() = runTest {
        val mirror = RecordingMirror()
        val (h, c) = snoozedGroup(mirror)
        c.snoozeOccurrence("c", occurrenceZ, laterT, RingOrigin.REMOTE)
        assertEquals(SnoozeEntry("split-2", "c", laterT, occurrenceZ), h.ledger.get("split-2"))
        assertEquals(mapOf("ring-1" to listOf("ring-1", "split-1")), h.groups.all())
        assertEquals(listOf("ring-1" to tenOhFive, "split-2" to laterT), h.notifier.snoozed.takeLast(2))
        assertEquals(listOf(listOf("a", "b"), listOf("c")), h.notifier.snoozedLabels.takeLast(2))
        assertEquals(1, mirror.calls.size)
        c.dismiss("ring-1", RingOrigin.LOCAL) // the group's Dismiss leaves c's own snooze alone
        assertEquals(listOf(SnoozeEntry("split-2", "c", laterT, occurrenceZ)), h.ledger.all())
        assertEquals(Triple(MirrorKind.DISMISS, listOf(RingMember("a", occurrenceX), bMember), null as Instant?), mirror.calls.last())
    }

    @Test fun movingThePrimaryLaterRenamesTheGroupAfterItsNextMember() = runTest {
        // The primary's own notification takes its ringId: the group's moves to its next member's.
        val mirror = RecordingMirror()
        val (h, c) = snoozedGroup(mirror)
        c.snoozeOccurrence("a", occurrenceX, laterT, RingOrigin.REMOTE)
        assertEquals(laterT, h.store.state.first()!!.snoozedUntil)
        assertEquals(mapOf("split-1" to listOf("split-1", "split-2")), h.groups.all())
        assertEquals(listOf("split-1" to tenOhFive, "ring-1" to laterT), h.notifier.snoozed.takeLast(2))
        assertEquals(listOf(listOf("b", "c"), listOf("a")), h.notifier.snoozedLabels.takeLast(2))
        c.dismiss("split-1", RingOrigin.LOCAL)
        assertEquals(listOf("ring-1"), h.ledger.all().map { it.ringId })
        assertEquals(laterT, h.store.state.first()!!.snoozedUntil) // the primary's own snooze stands
        assertEquals(Triple(MirrorKind.DISMISS, listOf(bMember, cMember), null as Instant?), mirror.calls.last())
        assertEquals("split-1", h.notifier.cancelledSnoozed.last())
    }

    @Test fun theGroupResumesAtTAsOneRingAndItsNotificationGoesWithTheFirstResume() = runTest {
        val (h, c) = snoozedGroup()
        assertEquals(SlotClaim.Claimed, c.claimSnoozeResume("ring-1", "a"))
        assertTrue("ring-1" in h.notifier.cancelledSnoozed)
        assertTrue(h.groups.all().isEmpty())
        assertEquals(SlotClaim.Merged, c.claimSnoozeResume("split-1", "b"))
        assertEquals(SlotClaim.Merged, c.claimSnoozeResume("split-2", "c"))
        assertEquals(listOf(bMember, cMember), h.store.state.first()!!.merged)
        assertTrue(h.ledger.all().isEmpty())
    }

    @Test fun aMemberResumingBeforeThePrimaryTakesTheGroupNotificationAway() = runTest {
        val (h, c) = snoozedGroup()
        assertEquals(SlotClaim.Claimed, c.claimSnoozeResume("split-1", "b")) // parks the snoozed slot ring
        assertTrue("ring-1" in h.notifier.cancelledSnoozed)
        assertTrue(h.groups.all().isEmpty())
        assertEquals(SlotClaim.Merged, c.claimSnoozeResume("ring-1", "a"))
        assertEquals(SlotClaim.Merged, c.claimSnoozeResume("split-2", "c"))
        assertEquals(setOf(RingMember("a", occurrenceX), cMember), h.store.state.first()!!.merged.toSet())
    }

    @Test fun aGroupStoreThatThrowsSnoozesEveryMemberOnItsOwn() = runTest {
        // I1: the ledger keeps every snooze armed; without the group each gets its own notification and Dismiss.
        val throwing = object : SnoozeGroups {
            override fun put(groupId: String, members: List<String>) = throw IllegalStateException("prefs")
            override fun remove(groupId: String) = throw IllegalStateException("prefs")
            override fun all(): Map<String, List<String>> = throw IllegalStateException("prefs")
        }
        val mirror = RecordingMirror()
        val (h, c) = snoozedGroup(mirror, throwing)
        assertEquals(3, h.ledger.all().size)
        assertEquals(listOf("ring-1", "split-1", "split-2"), h.armed.map { it.second })
        assertEquals(tenOhFive, h.store.state.first()!!.snoozedUntil)
        assertEquals(listOf("ring-1", "split-1", "split-2"), h.notifier.snoozed.map { it.first })
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), h.notifier.snoozedLabels)
        c.dismiss("split-1", RingOrigin.LOCAL)
        assertEquals(setOf("ring-1", "split-2"), h.ledger.all().map { it.ringId }.toSet())
        assertEquals(Triple(MirrorKind.DISMISS, listOf(bMember), null as Instant?), mirror.calls.last())
        c.dismiss("ring-1", RingOrigin.LOCAL)
        assertNull(h.store.state.first())
        assertEquals(listOf("split-2"), h.ledger.all().map { it.ringId })
        assertEquals(Triple(MirrorKind.DISMISS, listOf(RingMember("a", occurrenceX)), null as Instant?), mirror.calls.last())
    }

    @Test fun aGroupWhoseMembersAreAllGoneIsIgnoredAndPrunedWithItsNotification() = runTest {
        val h = H(listOf(alarm("a", emptySet())), null)
        h.groups.put("ring-gone", listOf("ring-gone", "split-9")) // the scheduler dropped both long-past entries
        val c = h.controller()
        c.dismiss("ring-gone", RingOrigin.LOCAL)
        assertEquals(0, h.scheduler.rescheduleCount)
        assertEquals(listOf("ring-gone"), h.notifier.cancelledSnoozed)
        h.groups.put("ring-old", listOf("ring-old", "split-8"))
        c.clearStale(notLive)
        assertTrue(h.groups.all().isEmpty())
        assertEquals("ring-old", h.notifier.cancelledSnoozed.last())
    }
}
