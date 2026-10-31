package me.shawnsquire.tandem.alarm

import android.app.AlarmManager
import android.content.Context
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.nextOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.Shadows.shadowOf
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class AlarmSchedulerImplTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val zone = ZoneId.of("America/New_York")

    // 2026-03-02 (a Monday) 05:00 local
    private val now: Instant = ZonedDateTime.of(2026, 3, 2, 5, 0, 0, 0, zone).toInstant()
    private var clock: Instant = now

    private fun alarm(
        id: String,
        hour: Int = 6,
        minute: Int = 30,
        days: Set<DayOfWeek> = emptySet(),
        enabled: Boolean = true,
    ) = Alarm(id, id, hour, minute, days, enabled, null, null, null)

    private fun at(day: Int, hour: Int, minute: Int): Instant =
        ZonedDateTime.of(2026, 3, day, hour, minute, 0, 0, zone).toInstant()

    private fun scheduler(
        repo: FakeAlarmRepository,
        ring: FakeRingStateStore = FakeRingStateStore(),
        next: (Alarm, Instant, ZoneId) -> Instant? = ::nextOccurrence,
    ) = AlarmSchedulerImpl(context, repo, ring, now = { clock }, zone = { zone }, next = next, io = UnconfinedTestDispatcher())

    private val scheduled get() = shadowOf(alarmManager).scheduledAlarms

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun enabledAlarmsAreScheduledAtNextOccurrence() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 6, 30), alarm("b", 7, 0)))
        scheduler(repo).rescheduleAll()

        assertEquals(2, scheduled.size)
        val byTime = scheduled.associateBy { it.triggerAtMs }
        val a = byTime.getValue(at(2, 6, 30).toEpochMilli())
        val b = byTime.getValue(at(2, 7, 0).toEpochMilli())
        val intentA = shadowOf(a.operation!!).savedIntent
        assertEquals("a", intentA.getStringExtra(AlarmIntents.EXTRA_ALARM_ID))
        assertEquals(at(2, 6, 30).toEpochMilli(), intentA.getLongExtra(AlarmIntents.EXTRA_OCCURRENCE, -1))
        assertEquals("b", shadowOf(b.operation!!).savedIntent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID))
        assertNotNull(a.showIntent)
    }

    @Test
    fun disabledAlarmThatWasScheduledIsCancelled() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a")))
        val s = scheduler(repo)
        s.rescheduleAll()
        assertEquals(1, scheduled.size)

        repo.setEnabled("a", false)
        s.rescheduleAll()
        assertTrue(scheduled.isEmpty())
        assertNull(AlarmIntents.existingFirePendingIntent(context, "a"))
    }

    @Test
    fun disabledAlarmIsNotScheduledEvenIfHelperReturnsATime() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", enabled = false)))
        scheduler(repo, next = { _, n, _ -> n.plusSeconds(60) }).rescheduleAll()
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun deletedAlarmIsCancelled() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a"), alarm("b", 7, 0)))
        val s = scheduler(repo)
        s.rescheduleAll()
        assertEquals(2, scheduled.size)

        repo.delete("a")
        // a fresh scheduler instance must still remember "a" (persisted ids)
        scheduler(repo).rescheduleAll()
        assertEquals(1, scheduled.size)
        assertEquals("b", shadowOf(scheduled.single().operation!!).savedIntent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID))
        assertNull(AlarmIntents.existingFirePendingIntent(context, "a"))
        assertNotNull(AlarmIntents.existingFirePendingIntent(context, "b"))
    }

    @Test
    fun alarmWithNoNextOccurrenceIsCancelled() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a")))
        scheduler(repo).rescheduleAll()
        assertEquals(1, scheduled.size)

        scheduler(repo, next = { _, _, _ -> null }).rescheduleAll()
        assertTrue(scheduled.isEmpty())
        assertNull(AlarmIntents.existingFirePendingIntent(context, "a"))
    }

    @Test
    fun rescheduleAfterTimeChangeUsesCurrentClock() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 6, 30)))
        val s = scheduler(repo)
        s.rescheduleAll()
        assertEquals(at(2, 6, 30).toEpochMilli(), scheduled.single().triggerAtMs)

        clock = at(2, 7, 0)
        s.rescheduleAll()
        assertEquals(1, scheduled.size)
        assertEquals(at(3, 6, 30).toEpochMilli(), scheduled.single().triggerAtMs)
    }

    @Test
    fun futureSnoozeIsScheduledAndClearedWhenRingStateCleared() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val until = at(2, 5, 10)
        val ring = FakeRingStateStore(RingState("a", "ring-1", at(2, 5, 0), until))
        val s = scheduler(repo, ring)
        s.rescheduleAll()

        assertEquals(1, scheduled.size)
        val snooze = scheduled.single()
        assertEquals(until.toEpochMilli(), snooze.triggerAtMs)
        val intent = shadowOf(snooze.operation!!).savedIntent
        assertEquals("a", intent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID))
        assertEquals("ring-1", intent.getStringExtra(AlarmIntents.EXTRA_RING_ID))
        assertTrue(intent.getBooleanExtra(AlarmIntents.EXTRA_IS_SNOOZE, false))
        assertNotNull(snooze.showIntent)

        ring.set(null)
        s.rescheduleAll()
        assertTrue(scheduled.isEmpty())
        assertNull(AlarmIntents.existingSnoozePendingIntent(context))
    }

    @Test
    fun snoozeDueWhilePhoneWasOffIsRearmedShortlyAfterNowOnBoot() = runTest {
        // snoozedUntil 5 minutes ago: inside the 10 minute window.
        val ring = FakeRingStateStore(RingState("a", "ring-1", at(2, 4, 50), at(2, 4, 55)))
        scheduler(FakeAlarmRepository(emptyList()), ring).rescheduleAll(rearmOverdueSnooze = true)
        val snooze = scheduled.single()
        assertEquals(now.plusSeconds(5).toEpochMilli(), snooze.triggerAtMs)
        val intent = shadowOf(snooze.operation!!).savedIntent
        assertEquals("ring-1", intent.getStringExtra(AlarmIntents.EXTRA_RING_ID))
        assertTrue(intent.getBooleanExtra(AlarmIntents.EXTRA_IS_SNOOZE, false))
    }

    @Test
    fun overdueSnoozeIsNotRearmedByPlainReschedule() = runTest {
        val ring = FakeRingStateStore(RingState("a", "ring-1", at(2, 4, 50), at(2, 4, 55)))
        scheduler(FakeAlarmRepository(emptyList()), ring).rescheduleAll()
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun overdueSnoozeIsLeftUntouchedByPlainReschedule() = runTest {
        val ring = FakeRingStateStore(RingState("a", "ring-1", at(2, 4, 50), at(2, 4, 55)))
        val s = scheduler(FakeAlarmRepository(emptyList()), ring)
        s.armSnooze("a", "ring-1", at(2, 4, 55))
        val before = scheduled.single().triggerAtMs
        s.rescheduleAll()
        assertEquals(before, scheduled.single().triggerAtMs)
        assertNotNull(AlarmIntents.existingSnoozePendingIntent(context))
    }

    @Test
    fun armSnoozeRegistersSnoozeAlarm() = runTest {
        val s = scheduler(FakeAlarmRepository(emptyList()))
        s.armSnooze("a", "ring-1", at(2, 5, 10))
        val snooze = scheduled.single()
        assertEquals(at(2, 5, 10).toEpochMilli(), snooze.triggerAtMs)
        assertEquals("ring-1", shadowOf(snooze.operation!!).savedIntent.getStringExtra(AlarmIntents.EXTRA_RING_ID))
    }

    @Test
    fun snoozeWellPastIsNotScheduled() = runTest {
        val ring = FakeRingStateStore(RingState("a", "ring-1", at(2, 4, 0), at(2, 4, 10)))
        scheduler(FakeAlarmRepository(emptyList()), ring).rescheduleAll(rearmOverdueSnooze = true)
        assertTrue(scheduled.isEmpty())
        assertNull(AlarmIntents.existingSnoozePendingIntent(context))
    }

    @Test
    fun failingAlarmDoesNotBlockOthersOrSnooze() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 6, 30), alarm("b", 7, 0)))
        val ring = FakeRingStateStore(RingState("z", "ring-1", at(2, 4, 59), at(2, 5, 10)))
        val throwingForA: (Alarm, Instant, ZoneId) -> Instant? = { alarm, n, z ->
            if (alarm.id == "a") error("bad alarm") else nextOccurrence(alarm, n, z)
        }
        scheduler(repo, ring, throwingForA).rescheduleAll()

        assertEquals(
            setOf(at(2, 7, 0).toEpochMilli(), at(2, 5, 10).toEpochMilli()),
            scheduled.map { it.triggerAtMs }.toSet(),
        )
        assertNotNull(AlarmIntents.existingFirePendingIntent(context, "b"))
        assertNotNull(AlarmIntents.existingSnoozePendingIntent(context))
    }

    @Test
    fun failingAlarmKeepsItsPreviousPendingIntent() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 6, 30), alarm("b", 7, 0)))
        scheduler(repo).rescheduleAll()
        assertNotNull(AlarmIntents.existingFirePendingIntent(context, "a"))

        val throwingForA: (Alarm, Instant, ZoneId) -> Instant? = { alarm, n, z ->
            if (alarm.id == "a") throw IllegalStateException("bad") else nextOccurrence(alarm, n, z)
        }
        scheduler(repo, next = throwingForA).rescheduleAll()
        assertNotNull(AlarmIntents.existingFirePendingIntent(context, "a"))
        assertNotNull(AlarmIntents.existingFirePendingIntent(context, "b"))
        assertEquals(2, scheduled.size)
    }

    @Test
    fun pastOrNowTriggerIsSkipped() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 6, 30), alarm("b", 7, 0)))
        val badA: (Alarm, Instant, ZoneId) -> Instant? = { alarm, n, z ->
            if (alarm.id == "a") n else nextOccurrence(alarm, n, z)
        }
        scheduler(repo, next = badA).rescheduleAll()
        assertEquals(listOf(at(2, 7, 0).toEpochMilli()), scheduled.map { it.triggerAtMs })
        assertNull(AlarmIntents.existingFirePendingIntent(context, "a"))
    }

    @Test
    fun oneShotWithSkipHasNoNextOccurrence() = runTest {
        val skipped = alarm("a", 6, 30).copy(skippedOccurrenceAt = at(2, 6, 30))
        scheduler(FakeAlarmRepository(listOf(skipped))).rescheduleAll()
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun idsArePersistedBeforeFinalCommit() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 6, 30), alarm("b", 7, 0)))
        val prefs = context.getSharedPreferences("alarm_scheduler", Context.MODE_PRIVATE)
        var idsWhenComputingB: Set<String>? = null
        val probe: (Alarm, Instant, ZoneId) -> Instant? = { alarm, n, z ->
            if (alarm.id == "b") idsWhenComputingB = prefs.getStringSet("scheduled_ids", null)
            nextOccurrence(alarm, n, z)
        }
        scheduler(repo, next = probe).rescheduleAll()
        // Mid-run (before the final write) the registered id is already durable.
        assertEquals(setOf("a"), idsWhenComputingB)
        assertEquals(setOf("a", "b"), prefs.getStringSet("scheduled_ids", null))
    }

    @Test
    fun snoozeAndAlarmWithSameIdCoexist() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 6, 30)))
        val ring = FakeRingStateStore(RingState("a", "ring-1", at(2, 5, 0), at(2, 5, 10)))
        scheduler(repo, ring).rescheduleAll()

        assertEquals(2, scheduled.size)
        assertEquals(
            setOf(at(2, 6, 30).toEpochMilli(), at(2, 5, 10).toEpochMilli()),
            scheduled.map { it.triggerAtMs }.toSet(),
        )
        assertNotNull(AlarmIntents.existingFirePendingIntent(context, "a"))
        assertNotNull(AlarmIntents.existingSnoozePendingIntent(context))
    }
}
