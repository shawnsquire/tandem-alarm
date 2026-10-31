package me.shawnsquire.tandem.alarm

import android.app.AlarmManager
import android.content.Context
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.awaitTandemStartup
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingState
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
        repo: FakeAlarmRepository,
        ring: FakeRingStateStore = FakeRingStateStore(),
        next: (Alarm, Instant, ZoneId) -> Instant? = ::nextOccurrence,
        ledger: SnoozeLedger = InMemorySnoozeLedger(),
        onScheduled: suspend (List<Alarm>, Map<String, Long>) -> Unit = { _, _ -> },
        ctx: Context = context,
    ) = AlarmSchedulerImpl(ctx, repo, ring, ledger, now = { clock }, zone = { zone }, next = next, io = UnconfinedTestDispatcher(), onScheduled = onScheduled)

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

    @Test
    fun noReplayForSkippedOccurrence() = runTest {
        val repo = FakeAlarmRepository(listOf(alarm("a", 5, 0)))
        val s = armedThenLater(repo, at(2, 5, 0).plusSeconds(180))
        repo.setSkip("a", at(2, 5, 0))
        s.rescheduleAll(rearmOverdueSnooze = true)
        assertTrue(replays.isEmpty())
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
