package me.shawnsquire.tandem.alarm

import android.app.AlarmManager
import android.content.Context
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingState
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
        next: (Alarm, Instant, ZoneId) -> Instant? = ::testNext,
    ) = AlarmSchedulerImpl(context, repo, ring, now = { clock }, zone = { zone }, next = next)

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
    fun pastSnoozeIsNotScheduled() = runTest {
        val ring = FakeRingStateStore(RingState("a", "ring-1", at(2, 4, 0), at(2, 4, 10)))
        scheduler(FakeAlarmRepository(emptyList()), ring).rescheduleAll()
        assertTrue(scheduled.isEmpty())
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

/** Local stand-in for U1's nextOccurrence: first hour:minute strictly after now matching days. */
private fun testNext(alarm: Alarm, now: Instant, zone: ZoneId): Instant? {
    if (!alarm.enabled) return null
    val start = ZonedDateTime.ofInstant(now, zone)
    for (offset in 0L..14L) {
        val candidate = start.toLocalDate().plusDays(offset)
            .atTime(alarm.hour, alarm.minute).atZone(zone).toInstant()
        if (!candidate.isAfter(now)) continue
        val day = candidate.atZone(zone).dayOfWeek
        if (alarm.days.isNotEmpty() && day !in alarm.days) continue
        if (candidate == alarm.skippedOccurrenceAt) continue
        return candidate
    }
    return null
}
