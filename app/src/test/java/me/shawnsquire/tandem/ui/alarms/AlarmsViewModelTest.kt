package me.shawnsquire.tandem.ui.alarms

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.ui.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

class AlarmsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    // Monday 2026-11-02 05:00 UTC.
    private val now = Instant.parse("2026-11-02T05:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val weekdays = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    private fun alarm(
        id: String, hour: Int = 6, minute: Int = 30, label: String = id,
        days: Set<DayOfWeek> = weekdays, enabled: Boolean = true, skip: Instant? = null,
    ) = Alarm(id, label, hour, minute, days, enabled, skip, "file:///ring-$id", "/wall-$id")

    private class Env(initial: List<Alarm>, clock: Clock) {
        val repo = FakeAlarmRepository(initial)
        val scheduler = FakeAlarmScheduler()
        val purged = mutableListOf<Alarm>()
        val ticker = MutableStateFlow(Unit)
        val vm = AlarmsViewModel(repo, AlarmEdits(repo, scheduler), clock, ticker) { purged += it }
    }

    private fun env(vararg alarms: Alarm) = Env(alarms.toList(), clock)

    @Test fun skipNextPinsExactNextOccurrence() = runTest {
        val e = env(alarm("w"))
        e.vm.skipNext("w")
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), e.repo.get("w")!!.skippedOccurrenceAt)
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test fun skipNextShowsSkippedRowWithFollowingOccurrence() = runTest {
        val e = env(alarm("w"))
        e.vm.state.test {
            awaitItem() // initial / first computed
            e.vm.skipNext("w")
            val row = expectMostRecentItem().rows.single()
            assertTrue(row.skipped)
            assertEquals(Instant.parse("2026-11-03T06:30:00Z"), row.nextRing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun unskipClearsAndReschedules() = runTest {
        val e = env(alarm("w", skip = Instant.parse("2026-11-02T06:30:00Z")))
        e.vm.unskip("w")
        assertNull(e.repo.get("w")!!.skippedOccurrenceAt)
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test fun skipOnOneShotTurnsItOff() = runTest {
        val e = env(alarm("o", days = emptySet()))
        e.vm.skipNext("o")
        val a = e.repo.get("o")!!
        assertFalse(a.enabled)
        assertNull(a.skippedOccurrenceAt)
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test fun skipOnDisabledDoesNothing() = runTest {
        val e = env(alarm("w", enabled = false))
        e.vm.skipNext("w")
        assertNull(e.repo.get("w")!!.skippedOccurrenceAt)
        assertEquals(0, e.scheduler.rescheduleCount)
    }

    @Test fun toggleEnabledReschedules() = runTest {
        val e = env(alarm("w"))
        e.vm.setEnabled("w", false)
        assertFalse(e.repo.get("w")!!.enabled)
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test fun rowsSortedByTimeThenLabel() = runTest {
        val e = env(
            alarm("c", hour = 9, minute = 0, label = "Zed"),
            alarm("b", hour = 6, minute = 45, label = "B"),
            alarm("a2", hour = 6, minute = 30, label = "beta"),
            alarm("a1", hour = 6, minute = 30, label = "Alpha"),
        )
        e.vm.state.test {
            val rows = expectMostRecentItem().rows
            assertEquals(listOf("a1", "a2", "b", "c"), rows.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun rowContentAndDisabledHasNoNextRing() = runTest {
        val e = env(alarm("w"), alarm("off", enabled = false, hour = 7))
        e.vm.state.test {
            val rows = expectMostRecentItem().rows
            val w = rows.first { it.id == "w" }
            assertEquals("Mon–Fri", w.daysText)
            assertEquals("Rings in 1 h 30 min", w.ringsInText)
            val off = rows.first { it.id == "off" }
            assertNull(off.nextRing)
            assertNull(off.ringsInText)
            assertFalse(off.skipped)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun staleSkipIsNotShown() = runTest {
        val e = env(alarm("w", skip = Instant.parse("2026-10-30T06:30:00Z")))
        e.vm.state.test {
            val row = expectMostRecentItem().rows.single()
            assertFalse(row.skipped)
            assertEquals(Instant.parse("2026-11-02T06:30:00Z"), row.nextRing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun deleteThenUndoRestoresAndDoesNotPurge() = runTest {
        val w = alarm("w")
        val e = env(w)
        e.vm.pendingDeleteId.test {
            assertNull(awaitItem())
            e.vm.delete("w")
            assertEquals("w", awaitItem())
            assertNull(e.repo.get("w"))
            e.vm.undoDelete("w")
            assertNull(awaitItem())
            assertEquals(w, e.repo.get("w"))
            e.vm.onDeleteSnackbarDismissed("w") // dismissal after undo must not purge
            assertTrue(e.purged.isEmpty())
            assertEquals(2, e.scheduler.rescheduleCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun deleteThenDismissPurgesOnce() = runTest {
        val w = alarm("w")
        val e = env(w)
        e.vm.delete("w")
        assertEquals(1, e.scheduler.rescheduleCount)
        e.vm.onDeleteSnackbarDismissed("w")
        e.vm.onDeleteSnackbarDismissed("w")
        assertEquals(listOf(w), e.purged)
        assertNull(e.repo.get("w"))
    }

    @Test fun secondDeletePurgesFirstAndStaleUndoIsNoOp() = runTest {
        val e = env(alarm("a"), alarm("b"))
        e.vm.delete("a")
        e.vm.delete("b")
        assertEquals(listOf("a"), e.purged.map { it.id })
        e.vm.undoDelete("a") // stale Undo from the first snackbar
        assertNull(e.repo.get("a"))
        assertNull(e.repo.get("b"))
        e.vm.onDeleteSnackbarDismissed("a")
        assertEquals(1, e.purged.size)
        e.vm.onDeleteSnackbarDismissed("b")
        assertEquals(listOf("a", "b"), e.purged.map { it.id })
    }

    @Test fun pendingDeleteSurvivesUntilReportedAndClearsOnDismiss() = runTest {
        val e = env(alarm("w"))
        assertNull(e.vm.pendingDeleteId.value)
        e.vm.delete("w")
        // A screen that left composition never reports; the state must still be there for the next one.
        assertEquals("w", e.vm.pendingDeleteId.value)
        assertTrue(e.purged.isEmpty())
        e.vm.onDeleteSnackbarDismissed("w")
        assertNull(e.vm.pendingDeleteId.value)
        assertEquals(1, e.purged.size)
    }

    @Test fun secondDeleteMovesPendingIdAndStaleUndoKeepsIt() = runTest {
        val e = env(alarm("a"), alarm("b"))
        e.vm.delete("a")
        e.vm.delete("b")
        assertEquals("b", e.vm.pendingDeleteId.value)
        e.vm.undoDelete("a")
        e.vm.onDeleteSnackbarDismissed("a")
        assertEquals("b", e.vm.pendingDeleteId.value)
    }
}
