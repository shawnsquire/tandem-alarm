package me.shawnsquire.tandem.alarm

import android.app.AlarmManager
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.awaitTandemStartup
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.isArmed
import me.shawnsquire.tandem.schedule.nextOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        repo: AlarmRepository,
        ring: FakeRingStateStore = FakeRingStateStore(),
        next: (Alarm, Instant, ZoneId) -> Instant? = ::nextOccurrence,
        isArmed: (Alarm) -> Boolean = ::isArmed,
        ledger: SnoozeLedger = InMemorySnoozeLedger(),
        onScheduled: suspend (List<Alarm>, Map<String, Long>) -> Unit = { _, _ -> },
        ctx: Context = context,
    ) = AlarmSchedulerImpl(ctx, repo, ring, ledger, now = { clock }, zone = { zone }, next = next, isArmed = isArmed, io = UnconfinedTestDispatcher(), onScheduled = onScheduled)

    private fun ledgerOf(vararg entries: SnoozeEntry) = InMemorySnoozeLedger().apply {
        entries.forEach { put(it.ringId, it.alarmId, it.until) }
    }

    private val scheduled get() = shadowOf(alarmManager).scheduledAlarms

    @Before
    fun setUp() {
        // TandemApp's startup reschedule shares the scheduler records and AlarmManager.
        awaitTandemStartup()
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
    fun onScheduledGetsTheAlarmsAndTheArmedTriggers() = runTest {
        val alarms = listOf(alarm("a", 6, 30), alarm("b", 7, 0, enabled = false))
        var seen: Pair<List<Alarm>, Map<String, Long>>? = null
        scheduler(FakeAlarmRepository(alarms), onScheduled = { a, t -> seen = a to t }).rescheduleAll()
        assertEquals(alarms to mapOf("a" to at(2, 6, 30).toEpochMilli()), seen)
    }

    @Test
    fun throwingOnScheduledDoesNotFailReschedule() = runTest {
        scheduler(FakeAlarmRepository(listOf(alarm("a"))), onScheduled = { _, _ -> throw IllegalStateException("boom") }).rescheduleAll()
        assertEquals(1, scheduled.size)
    }

    @Test
    fun schedulerOnlyTouchesDeviceStorage() = runTest {
        val locked = LockedContext(context)
        scheduler(FakeAlarmRepository(listOf(alarm("a"))), ctx = locked).rescheduleAll()
        scheduler(FakeAlarmRepository(emptyList()), ctx = locked).rescheduleAll()
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun snoozeOfALinkedOneShotWhoseCopyTurnedOffIsStillArmed() = runTest {
        // The owner's solo-skipped one-shot expired (X + FIRE_GRACE) and LinkSync copied enabled = false
        // while the joiner was snoozed on X: the row arms nothing, but its snooze runs out.
        clock = at(2, 6, 33)
        val row = alarm("a", enabled = false).copy(
            link = AlarmLink("o", "oa", LinkMode.RING, null, true, null, null, null, null),
        )
        val until = at(2, 6, 40)
        scheduler(FakeAlarmRepository(listOf(row)), ledger = ledgerOf(SnoozeEntry("ring-1", "a", until, at(2, 6, 30))))
            .rescheduleAll()
        val armed = scheduled.single()
        assertEquals(until.toEpochMilli(), armed.triggerAtMs)
        assertTrue(shadowOf(armed.operation!!).savedIntent.getBooleanExtra(AlarmIntents.EXTRA_IS_SNOOZE, false))
    }

    @Test
    fun futureLedgerSnoozeIsScheduledAndCancelSnoozeClearsIt() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val until = at(2, 5, 10)
        val s = scheduler(repo, ledger = ledgerOf(SnoozeEntry("ring-1", "a", until)))
        s.rescheduleAll()

        assertEquals(1, scheduled.size)
        val snooze = scheduled.single()
        assertEquals(until.toEpochMilli(), snooze.triggerAtMs)
        val intent = shadowOf(snooze.operation!!).savedIntent
        assertEquals("a", intent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID))
        assertEquals("ring-1", intent.getStringExtra(AlarmIntents.EXTRA_RING_ID))
        assertTrue(intent.getBooleanExtra(AlarmIntents.EXTRA_IS_SNOOZE, false))
        assertNotNull(snooze.showIntent)
        assertEquals("tandem://snooze/ring-1", intent.data.toString())

        s.cancelSnooze("ring-1")
        assertTrue(scheduled.isEmpty())
        assertNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-1"))
    }

    @Test
    fun snoozeDueWhilePhoneWasOffIsRearmedShortlyAfterNowOnBoot() = runTest {
        // Snooze came due 5 minutes ago: inside the 10 minute window.
        val ledger = ledgerOf(SnoozeEntry("ring-1", "a", at(2, 4, 55)))
        scheduler(FakeAlarmRepository(emptyList()), ledger = ledger).rescheduleAll(rearmOverdueSnooze = true)
        val snooze = scheduled.single()
        assertEquals(now.plusSeconds(5).toEpochMilli(), snooze.triggerAtMs)
        val intent = shadowOf(snooze.operation!!).savedIntent
        assertEquals("ring-1", intent.getStringExtra(AlarmIntents.EXTRA_RING_ID))
        assertTrue(intent.getBooleanExtra(AlarmIntents.EXTRA_IS_SNOOZE, false))
        assertNotNull(ledger.get("ring-1"))
    }

    @Test
    fun overdueSnoozeIsNotRearmedByPlainReschedule() = runTest {
        val ledger = ledgerOf(SnoozeEntry("ring-1", "a", at(2, 4, 55)))
        scheduler(FakeAlarmRepository(emptyList()), ledger = ledger).rescheduleAll()
        assertTrue(scheduled.isEmpty())
        assertNotNull(ledger.get("ring-1")) // left alone, not dropped
    }

    @Test
    fun overdueSnoozeIsLeftUntouchedByPlainReschedule() = runTest {
        val ledger = ledgerOf(SnoozeEntry("ring-1", "a", at(2, 4, 55)))
        val s = scheduler(FakeAlarmRepository(emptyList()), ledger = ledger)
        s.armSnooze("a", "ring-1", at(2, 4, 55))
        val before = scheduled.single().triggerAtMs
        s.rescheduleAll()
        assertEquals(before, scheduled.single().triggerAtMs)
        assertNotNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-1"))
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
    fun snoozeWellPastIsCancelledAndDropped() = runTest {
        val ledger = ledgerOf(SnoozeEntry("ring-1", "a", at(2, 4, 10)))
        val s = scheduler(FakeAlarmRepository(emptyList()), ledger = ledger)
        s.armSnooze("a", "ring-1", at(2, 4, 10))
        s.rescheduleAll(rearmOverdueSnooze = true)
        assertTrue(scheduled.isEmpty())
        assertNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-1"))
        assertNull(ledger.get("ring-1"))
    }

    @Test
    fun snoozeWellPastIsDroppedByPlainRescheduleToo() = runTest {
        val ledger = ledgerOf(SnoozeEntry("ring-1", "a", at(2, 4, 49)))
        val s = scheduler(FakeAlarmRepository(emptyList()), ledger = ledger)
        s.armSnooze("a", "ring-1", at(2, 4, 49))
        s.rescheduleAll()
        assertNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-1"))
        assertNull(ledger.get("ring-1"))
    }

    @Test
    fun failingAlarmDoesNotBlockOthersOrSnooze() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 6, 30), alarm("b", 7, 0)))
        val ledger = ledgerOf(SnoozeEntry("ring-1", "z", at(2, 5, 10)))
        val throwingForA: (Alarm, Instant, ZoneId) -> Instant? = { alarm, n, z ->
            if (alarm.id == "a") error("bad alarm") else nextOccurrence(alarm, n, z)
        }
        scheduler(repo, next = throwingForA, ledger = ledger).rescheduleAll()

        assertEquals(
            setOf(at(2, 7, 0).toEpochMilli(), at(2, 5, 10).toEpochMilli()),
            scheduled.map { it.triggerAtMs }.toSet(),
        )
        assertNotNull(AlarmIntents.existingFirePendingIntent(context, "b"))
        assertNotNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-1"))
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
        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("alarm_scheduler", Context.MODE_PRIVATE)
        var idsWhenComputingB: Set<String>? = null
        val probe: (Alarm, Instant, ZoneId) -> Instant? = { alarm, n, z ->
            if (alarm.id == "b") idsWhenComputingB = prefs.getStringSet("scheduled_records", null)?.map { it.substringAfter('|') }?.toSet()
            nextOccurrence(alarm, n, z)
        }
        scheduler(repo, next = probe).rescheduleAll()
        // Mid-run (before the final write) the registered id is already durable.
        assertEquals(setOf("a"), idsWhenComputingB)
        assertEquals(
            setOf("${at(2, 6, 30).toEpochMilli()}|a", "${at(2, 7, 0).toEpochMilli()}|b"),
            prefs.getStringSet("scheduled_records", null),
        )
    }

    /** A locked repository whose mirror can't be read: its alarms flow throws. */
    private fun unreadable(repo: FakeAlarmRepository) = object : AlarmRepository by repo {
        override val alarms: Flow<List<Alarm>> = flow { throw IllegalStateException("alarm mirror unreadable") }
    }

    /** A reboot: AlarmManager and every PendingIntent it held are gone. */
    private fun reboot() {
        for (alarm in scheduled) {
            alarm.operation?.let { alarmManager.cancel(it); it.cancel() }
        }
        assertTrue(scheduled.isEmpty())
    }

    private val schedulerPrefs get() = context.createDeviceProtectedStorageContext().getSharedPreferences("alarm_scheduler", Context.MODE_PRIVATE)

    private val fires get() = scheduled.filter { !shadowOf(it.operation!!).savedIntent.data.toString().endsWith("/replay") }
        .filter { shadowOf(it.operation!!).savedIntent.action == AlarmIntents.ACTION_FIRE }

    private fun idAndOccurrence(alarm: ShadowAlarmManager.ScheduledAlarm): Pair<String?, Long> {
        val intent = shadowOf(alarm.operation!!).savedIntent
        return intent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID) to intent.getLongExtra(AlarmIntents.EXTRA_OCCURRENCE, -1)
    }

    @Test
    fun unreadableAlarmsAfterARebootRearmTheRecordsAndChangeNothingElse() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 6, 30), alarm("b", 7, 0)))
        scheduler(repo).rescheduleAll()
        val records = schedulerPrefs.getStringSet("scheduled_records", null)
        assertEquals(2, records?.size)
        reboot()
        val ledger = ledgerOf(SnoozeEntry("ring-1", "a", at(2, 5, 10)))
        var onScheduledCalls = 0
        val s = AlarmSchedulerImpl(
            context, unreadable(repo), FakeRingStateStore(), ledger, now = { clock }, zone = { zone },
            io = UnconfinedTestDispatcher(), onScheduled = { _, _ -> onScheduledCalls++ },
        )

        val e = runCatching { s.rescheduleAll(rearmOverdueSnooze = true) }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
        assertEquals(
            setOf("a" to at(2, 6, 30).toEpochMilli(), "b" to at(2, 7, 0).toEpochMilli()),
            fires.map(::idAndOccurrence).toSet(),
        )
        for (fire in fires) {
            assertEquals(idAndOccurrence(fire).second, fire.triggerAtMs)
            assertNotNull(fire.showIntent)
        }
        assertEquals(records, schedulerPrefs.getStringSet("scheduled_records", null))
        // Snoozes come from the ledger, not the alarms: still armed.
        assertNotNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-1"))
        assertEquals(0, onScheduledCalls)
    }

    @Test
    fun unreadableAlarmsAtBootReplayRecentRecordsAndSkipPastOnes() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0), alarm("b", 5, 2), alarm("c", 7, 0)))
        clock = at(2, 4, 55)
        scheduler(repo).rescheduleAll()
        // A plain run after a's 05:00 sets it aside as missed and records tomorrow's.
        clock = at(2, 5, 1)
        scheduler(repo).rescheduleAll()
        assertEquals(setOf("${at(2, 5, 0).toEpochMilli()}|a"), missed)
        val records = schedulerPrefs.getStringSet("scheduled_records", null)
        reboot()
        // Back up after b's 05:02 too.
        clock = at(2, 5, 3)

        val plain = runCatching { scheduler(unreadable(repo)).rescheduleAll() }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $plain", plain is IllegalStateException)
        assertTrue("a plain run never replays", replays.isEmpty())
        reboot()
        val e = runCatching { scheduler(unreadable(repo)).rescheduleAll(rearmOverdueSnooze = true) }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
        // Missed a and just-passed b replay shortly; their enabled and skip state can't be read (I1).
        assertEquals(
            setOf("a" to at(2, 5, 0).toEpochMilli(), "b" to at(2, 5, 2).toEpochMilli()),
            replays.map(::idAndOccurrence).toSet(),
        )
        assertTrue(replays.all { it.triggerAtMs == clock.plusSeconds(5).toEpochMilli() })
        // b's past record isn't armed as a fire; a's next and c are.
        assertEquals(
            setOf("a" to at(3, 5, 0).toEpochMilli(), "c" to at(2, 7, 0).toEpochMilli()),
            fires.map(::idAndOccurrence).toSet(),
        )
        assertEquals(records, schedulerPrefs.getStringSet("scheduled_records", null))
        assertEquals(setOf("${at(2, 5, 0).toEpochMilli()}|a"), missed)
    }

    @Test
    fun snoozeAndAlarmWithSameIdCoexist() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 6, 30)))
        scheduler(repo, ledger = ledgerOf(SnoozeEntry("ring-1", "a", at(2, 5, 10)))).rescheduleAll()

        assertEquals(2, scheduled.size)
        assertEquals(
            setOf(at(2, 6, 30).toEpochMilli(), at(2, 5, 10).toEpochMilli()),
            scheduled.map { it.triggerAtMs }.toSet(),
        )
        assertNotNull(AlarmIntents.existingFirePendingIntent(context, "a"))
        assertNotNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-1"))
    }

    // ---- D1: one snooze PendingIntent per ringId, armed from the ledger ----

    @Test
    fun snoozesForTwoRingIdsCoexist() = runTest {
        val s = scheduler(FakeAlarmRepository(emptyList()))
        s.armSnooze("a", "ring-A", at(2, 5, 10))
        s.armSnooze("b", "ring-B", at(2, 5, 20))
        assertEquals(setOf(at(2, 5, 10).toEpochMilli(), at(2, 5, 20).toEpochMilli()), scheduled.map { it.triggerAtMs }.toSet())
        assertNotNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-A"))
        assertNotNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-B"))

        s.cancelSnooze("ring-A")
        assertNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-A"))
        assertNotNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-B"))
        assertEquals("ring-B", shadowOf(scheduled.single().operation!!).savedIntent.getStringExtra(AlarmIntents.EXTRA_RING_ID))
    }

    @Test
    fun everyLedgerEntryIsArmed() = runTest {
        val ledger = ledgerOf(SnoozeEntry("ring-A", "a", at(2, 5, 10)), SnoozeEntry("ring-B", "b", at(2, 5, 20)))
        scheduler(FakeAlarmRepository(emptyList()), ledger = ledger).rescheduleAll()
        assertEquals(setOf(at(2, 5, 10).toEpochMilli(), at(2, 5, 20).toEpochMilli()), scheduled.map { it.triggerAtMs }.toSet())
    }

    @Test
    fun ringStateIsNoLongerTheSnoozeSource() = runTest {
        // A snoozed RingState with no ledger entry arms nothing; a ledger entry arms regardless of RingState.
        val ring = FakeRingStateStore(RingState("b", "ring-B", at(2, 4, 59), null))
        val ledger = ledgerOf(SnoozeEntry("ring-A", "a", at(2, 5, 10)))
        scheduler(FakeAlarmRepository(emptyList()), ring, ledger = ledger).rescheduleAll()
        assertEquals("ring-A", shadowOf(scheduled.single().operation!!).savedIntent.getStringExtra(AlarmIntents.EXTRA_RING_ID))
    }

    @Test
    fun managerScenario_parkedSnoozeStaysArmedThroughSupersedingRingsReschedule() = runTest {
        // A snoozed until 5:10 and parked; B (a one-shot) now owns the slot and is stopped, which reschedules.
        val until = at(2, 5, 10)
        val ledger = ledgerOf(SnoozeEntry("ring-A", "a", until))
        val ring = FakeRingStateStore(RingState("b", "ring-B", at(2, 5, 5), null))
        val s = scheduler(FakeAlarmRepository(listOf(alarm("a", 6, 30), alarm("b", 7, 0))), ring, ledger = ledger)
        s.armSnooze("a", "ring-A", until)
        ring.set(null)
        s.rescheduleAll()
        assertNotNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-A"))
        assertTrue(until.toEpochMilli() in scheduled.map { it.triggerAtMs })
    }

    @Test
    fun managerScenario_endToEndWithRealController() = runTest {
        clock = at(2, 5, 0)
        val repo = FakeAlarmRepository(listOf(alarm("a", 6, 30), alarm("b", 7, 0)))
        val ring = FakeRingStateStore(RingState("a", "ring-A", at(2, 5, 0), null))
        val ledger = InMemorySnoozeLedger()
        val s = scheduler(repo, ring, ledger = ledger)
        val settings = me.shawnsquire.tandem.fakes.FakeSettingsRepository().also { it.setSnoozeMinutes(10) }
        val controller = RingingControllerImpl(repo, ring, settings, s, s, RecordingNotifier(), ledger) { clock }

        controller.snooze() // A snoozed until 5:10
        clock = at(2, 5, 5)
        controller.claimFire(RingState("b", "ring-B", clock, null)) // B fires at 5:05
        controller.dismiss("ring-B") // user stops B: real rescheduleAll runs

        assertNotNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-A"))
        assertTrue(at(2, 5, 10).toEpochMilli() in scheduled.map { it.triggerAtMs })
        assertNotNull(ledger.get("ring-A"))
        assertTrue(repo.get("a")!!.enabled)
    }

    @Test
    fun entryRemovedWhileArmingIsCancelled() = runTest {
        // all() still lists it, but the controller dropped it before the re-check.
        val racing = object : SnoozeLedger by InMemorySnoozeLedger() {
            override fun all() = listOf(SnoozeEntry("ring-1", "a", at(2, 5, 10)))
            override fun get(ringId: String): SnoozeEntry? = null
        }
        scheduler(FakeAlarmRepository(emptyList()), ledger = racing).rescheduleAll()
        assertNull(AlarmIntents.existingSnoozePendingIntent(context, "ring-1"))
        assertTrue(scheduled.isEmpty())
    }

    // ---- missed-alarm replay ----

    private val replays get() = scheduled.filter { shadowOf(it.operation!!).savedIntent.data.toString().endsWith("/replay") }

    /** Arms alarm [a] for 05:00 at 04:55, then moves the clock to [later]. */
    private suspend fun armedThenLater(repo: FakeAlarmRepository, later: Instant): AlarmSchedulerImpl {
        clock = at(2, 4, 55)
        val s = scheduler(repo)
        s.rescheduleAll()
        clock = later
        return s
    }

    @Test
    fun bootArmsReplayForRecentlyMissedAlarm() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 0).plusSeconds(180))
        s.rescheduleAll(rearmOverdueSnooze = true)

        val replay = replays.single()
        assertEquals(clock.plusSeconds(5).toEpochMilli(), replay.triggerAtMs)
        val intent = shadowOf(replay.operation!!).savedIntent
        assertEquals(AlarmIntents.ACTION_FIRE, intent.action)
        assertEquals("a", intent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID))
        assertEquals(at(2, 5, 0).toEpochMilli(), intent.getLongExtra(AlarmIntents.EXTRA_OCCURRENCE, -1))
        assertEquals("tandem://alarm/a/replay", intent.data.toString())
    }

    @Test
    fun replayAndNormalNextFireCoexist() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 0).plusSeconds(180))
        s.rescheduleAll(rearmOverdueSnooze = true)

        assertEquals(2, scheduled.size)
        assertNotNull(AlarmIntents.existingFirePendingIntent(context, "a"))
        assertNotNull(AlarmIntents.existingReplayPendingIntent(context, "a"))
        val normal = scheduled.single { it !in replays }
        assertEquals(at(3, 5, 0).toEpochMilli(), normal.triggerAtMs)
    }

    @Test
    fun plainRescheduleNeverArmsReplay() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 0).plusSeconds(180))
        s.rescheduleAll()
        assertTrue(replays.isEmpty())
    }

    // A plain reschedule (full startup, T4's clearStale, the UI) can take the mutex before the
    // boot or time-change rearm run; the occurrence it moves past must still be replayed.

    private val missed: Set<String>
        get() = context.createDeviceProtectedStorageContext().getSharedPreferences("alarm_scheduler", Context.MODE_PRIVATE)
            .getStringSet("missed_records", emptySet()).orEmpty()

    @Test
    fun plainRescheduleBeforeRearmStillReplaysMissedAlarm() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 3))
        s.rescheduleAll()
        assertTrue(replays.isEmpty())
        assertEquals(setOf("${at(2, 5, 0).toEpochMilli()}|a"), missed)

        s.rescheduleAll(rearmOverdueSnooze = true)

        val intent = shadowOf(replays.single().operation!!).savedIntent
        assertEquals(at(2, 5, 0).toEpochMilli(), intent.getLongExtra(AlarmIntents.EXTRA_OCCURRENCE, -1))
        assertNotNull(AlarmIntents.existingFirePendingIntent(context, "a"))
        // Consumed by the rearm run.
        assertTrue(missed.isEmpty())
    }

    @Test
    fun missedOlderThanTheWindowIsPrunedAndNotReplayed() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 3))
        s.rescheduleAll()
        clock = at(2, 5, 11)
        s.rescheduleAll()
        assertTrue(missed.isEmpty())
        s.rescheduleAll(rearmOverdueSnooze = true)
        assertTrue(replays.isEmpty())
    }

    @Test
    fun missedButOutOfWindowAtRearmIsNotReplayed() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 3))
        s.rescheduleAll()
        clock = at(2, 5, 11)
        s.rescheduleAll(rearmOverdueSnooze = true)
        assertTrue(replays.isEmpty())
        assertTrue(missed.isEmpty())
    }

    @Test
    fun missedSkippedOccurrenceIsNotReplayed() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 3))
        s.rescheduleAll()
        repo.setSkip("a", at(2, 5, 0))
        s.rescheduleAll(rearmOverdueSnooze = true)
        assertTrue(replays.isEmpty())
        assertTrue(missed.isEmpty())
    }

    @Test
    fun missedSurvivesSeveralPlainReschedulesAndANewSchedulerInstance() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0), alarm("b", 5, 2)))
        val s = armedThenLater(repo, at(2, 5, 1))
        s.rescheduleAll() // a missed
        clock = at(2, 5, 3)
        s.rescheduleAll() // b missed; a kept
        assertEquals(setOf("${at(2, 5, 0).toEpochMilli()}|a", "${at(2, 5, 2).toEpochMilli()}|b"), missed)

        scheduler(repo).rescheduleAll(rearmOverdueSnooze = true)

        val occurrences = replays.associate {
            val intent = shadowOf(it.operation!!).savedIntent
            intent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID) to intent.getLongExtra(AlarmIntents.EXTRA_OCCURRENCE, -1)
        }
        assertEquals(mapOf("a" to at(2, 5, 0).toEpochMilli(), "b" to at(2, 5, 2).toEpochMilli()), occurrences)
        assertTrue(missed.isEmpty())
    }

    @Test
    fun noReplayForSkippedOccurrence() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 0).plusSeconds(180))
        repo.setSkip("a", at(2, 5, 0))
        s.rescheduleAll(rearmOverdueSnooze = true)
        assertTrue(replays.isEmpty())
    }

    @Test
    fun disarmedPerIsArmedIsNotScheduledEvenIfEnabled() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a")))
        scheduler(repo, isArmed = { false }).rescheduleAll()
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun armedPerIsArmedIsScheduledEvenIfNotEnabled() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", enabled = false)))
        scheduler(repo, next = { _, n, _ -> n.plusSeconds(60) }, isArmed = { true }).rescheduleAll()
        assertEquals(1, scheduled.size)
    }

    private val replayAt get() = at(2, 5, 0)

    private suspend fun replaysWith(stored: Alarm): List<ShadowAlarmManager.ScheduledAlarm> {
        armedThenLater(FakeAlarmRepository(listOf(alarm("a", 5, 0))), replayAt.plusSeconds(180))
        scheduler(FakeAlarmRepository(listOf(stored))).rescheduleAll(rearmOverdueSnooze = true)
        return replays
    }

    private val link = AlarmLink("o", "oa", LinkMode.RING, null, true, null, null, null, null)

    @Test
    fun noReplayForSoloSkippedOccurrence() = runTest {
        assertTrue(replaysWith(alarm("a", 5, 0).copy(soloSkippedOccurrenceAt = replayAt)).isEmpty())
    }

    @Test
    fun noReplayForOwnerSkippedOccurrence() = runTest {
        assertTrue(replaysWith(alarm("a", 5, 0).copy(link = link.copy(ownerSkippedOccurrenceAt = replayAt))).isEmpty())
    }

    @Test
    fun noReplayForPendingSkipNextOccurrence() = runTest {
        val pending = PendingBoth(CommandType.SKIP_NEXT, replayAt, "c1")
        assertTrue(replaysWith(alarm("a", 5, 0).copy(link = link.copy(pendingBoth = pending))).isEmpty())
    }

    @Test
    fun replayForOwnerSkipCancelledByPendingUnskip() = runTest {
        val stored = alarm("a", 5, 0, days = DayOfWeek.entries.toSet()).copy(
            link = link.copy(ownerSkippedOccurrenceAt = replayAt, pendingBoth = PendingBoth(CommandType.UNSKIP, replayAt, "c1")),
        )
        assertEquals(1, replaysWith(stored).size)
    }

    @Test
    fun replayForSoloSkipOnALinkedRow() = runTest {
        val stored = alarm("a", 5, 0, days = DayOfWeek.entries.toSet()).copy(soloSkippedOccurrenceAt = replayAt, link = link)
        assertEquals(1, replaysWith(stored).size)
    }

    @Test
    fun replayStillArmedWhenStoredSkipsAreForOtherInstants() = runTest {
        val other = replayAt.plusSeconds(86_400)
        // Repeating, so tomorrow's skipped occurrence still leaves a next fire: an armed alarm with
        // no next occurrence is not "scheduled" and would have its replay cancelled.
        val stored = alarm("a", 5, 0, days = DayOfWeek.entries.toSet()).copy(
            soloSkippedOccurrenceAt = other,
            link = link.copy(ownerSkippedOccurrenceAt = other, pendingBoth = PendingBoth(CommandType.SKIP_NEXT, other, "c1")),
        )
        assertEquals(1, replaysWith(stored).size)
    }

    @Test
    fun replayArmedThisRunSurvivesWhenTheAlarmHasNoNextOccurrence() = runTest {
        // An own one-shot solo-skipped for tomorrow has no next fire, but today's missed X still rings.
        val stored = alarm("a", 5, 0).copy(soloSkippedOccurrenceAt = replayAt.plusSeconds(86_400))
        assertEquals(1, replaysWith(stored).size)
        assertNotNull(AlarmIntents.existingReplayPendingIntent(context, "a"))
        assertNull(AlarmIntents.existingFirePendingIntent(context, "a")) // its old fire is still cancelled
    }

    @Test
    fun noReplayForDisabledOrDeletedAlarm() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0), alarm("b", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 0).plusSeconds(180))
        repo.setEnabled("a", false)
        repo.delete("b")
        s.rescheduleAll(rearmOverdueSnooze = true)
        assertTrue(replays.isEmpty())
    }

    @Test
    fun disablingWithinReplayWindowCancelsTheReplay() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 0).plusSeconds(180))
        s.rescheduleAll(rearmOverdueSnooze = true)
        assertNotNull(AlarmIntents.existingReplayPendingIntent(context, "a"))
        repo.setEnabled("a", false)
        s.rescheduleAll()
        assertNull(AlarmIntents.existingReplayPendingIntent(context, "a"))
        assertNull(AlarmIntents.existingFirePendingIntent(context, "a"))
    }

    @Test
    fun deletingWithinReplayWindowCancelsTheReplay() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 0).plusSeconds(180))
        s.rescheduleAll(rearmOverdueSnooze = true)
        repo.delete("a")
        s.rescheduleAll()
        assertNull(AlarmIntents.existingReplayPendingIntent(context, "a"))
    }

    @Test
    fun noReplayWhenRecordIsOlderThanTenMinutes() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 11))
        s.rescheduleAll(rearmOverdueSnooze = true)
        assertTrue(replays.isEmpty())
    }

    @Test
    fun legacyIdSetIsMigratedWithoutReplayAndStillCancelled() = runTest {
        context.createDeviceProtectedStorageContext().getSharedPreferences("alarm_scheduler", Context.MODE_PRIVATE).edit()
            .putStringSet("scheduled_ids", setOf("gone")).commit()
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        scheduler(repo).rescheduleAll(rearmOverdueSnooze = true)
        assertTrue(replays.isEmpty())
        assertEquals(1, scheduled.size)
        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("alarm_scheduler", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("scheduled_ids"))
        assertTrue(prefs.contains("scheduled_records"))
    }
}
