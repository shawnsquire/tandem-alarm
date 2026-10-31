package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.flow.first
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
    var cancelSnoozedCount = 0
    override fun showRinging(alarm: Alarm?, ringId: String) { ringing += ringId }
    override fun showSnoozed(ringId: String, until: Instant) { snoozed += ringId to until }
    override fun cancelSnoozed() { cancelSnoozedCount++ }
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
    }

    private fun H.controller() = RingingControllerImpl(repo, store, settings, scheduler, notifier) { now }

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

    @Test fun snoozeRescheduleFailureRestoresRinging() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val bad = object : AlarmScheduler {
            override suspend fun rescheduleAll() { throw IllegalStateException("alarm manager") }
        }
        val c = RingingControllerImpl(h.repo, h.store, h.settings, bad, h.notifier) { now }
        var thrown = false
        try { c.snooze() } catch (e: IllegalStateException) { thrown = true }
        assertTrue(thrown)
        assertEquals(ringing, h.store.state.first()) // snoozedUntil cleared again
        assertTrue(h.notifier.snoozed.isEmpty())
    }

    @Test fun dismissRunsRemainingStepsWhenAlarmWriteFails() = runTest {
        val h = H(listOf(alarm("a", emptySet())), ringing)
        val failingRepo = object : me.shawnsquire.tandem.data.AlarmRepository by h.repo {
            override suspend fun setEnabled(id: String, enabled: Boolean) { throw IllegalStateException("db") }
        }
        val c = RingingControllerImpl(failingRepo, h.store, h.settings, h.scheduler, h.notifier) { now }
        var thrown = false
        try { c.dismiss("ring-1") } catch (e: IllegalStateException) { thrown = true }
        assertTrue(thrown)
        assertNull(h.store.state.first())
        assertEquals(1, h.notifier.cancelSnoozedCount)
        assertEquals(1, h.scheduler.rescheduleCount)
    }
}
