package me.shawnsquire.tandem.sync

import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeLinkedMediaCache
import me.shawnsquire.tandem.fakes.FakeRingingController
import me.shawnsquire.tandem.media.AlarmMedia
import me.shawnsquire.tandem.media.MediaRef
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.ParkedSnooze
import me.shawnsquire.tandem.schedule.PendingBoth
import me.shawnsquire.tandem.schedule.RingContext
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.WallpaperFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LinkSyncImplTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)
    private val hh = Household("h1", "me", "them")
    private val monday = Instant.parse("2026-03-02T05:00:00Z") // Monday, before 6:30
    private var storeCount = 0

    private fun row(
        id: String = "row1",
        ownerAlarmId: String = "o1",
        days: Set<DayOfWeek> = weekdays,
        enabled: Boolean = true,
        hour: Int = 6,
        minute: Int = 30,
        joinerEnabled: Boolean = true,
        pendingBoth: PendingBoth? = null,
        ownerUid: String = "them",
        ownerSkip: Instant? = null,
        skip: Instant? = null,
    ) = Alarm(
        id, "Wake", hour, minute, days, enabled, skip, "content://mine", "/mine.jpg",
        link = AlarmLink(
            ownerUid, ownerAlarmId, LinkMode.NOTIFY, ownerSkip, joinerEnabled, pendingBoth,
            "my label", "content://override", "/override.jpg",
        ),
        soloSkippedOccurrenceAt = Instant.parse("2026-03-09T06:30:00Z"),
    )

    private fun owner(
        id: String = "o1",
        days: Set<DayOfWeek> = weekdays,
        enabled: Boolean = true,
        hour: Int = 6,
        minute: Int = 30,
        label: String = "Shawn wake",
        skip: Instant? = null,
        media: AlarmMedia? = null,
    ) = PartnerAlarm(id, label, hour, minute, days, enabled, null, skip, media)

    private fun state(
        vararg alarms: PartnerAlarm, name: String = "Shawn", wallpaper: MediaRef? = null, seen: Instant? = null,
        wallpaperFrame: WallpaperFrame? = null,
    ) = PartnerState(name, alarms.toList(), null, seen, defaultWallpaper = wallpaper, defaultWallpaperFrame = wallpaperFrame)

    private class Env(
        val test: LinkSyncImplTest,
        scope: TestScope,
        now: Instant,
        initialRows: List<Alarm>,
        repo: ((FakeAlarmRepository) -> AlarmRepository)? = null,
        val removals: LinkRemovals = LinkRemovals(
            createSyncDataStore(
                File(test.tmp.root, "sync-${test.storeCount++}.preferences_pb"), scope.backgroundScope,
            ),
        ),
        household: Household? = test.hh,
        partner: PartnerState? = null,
        partnerFlow: Flow<PartnerState?>? = null,
    ) {
        val fake = FakeAlarmRepository(initialRows)
        val alarms: AlarmRepository = repo?.invoke(fake) ?: fake
        val partner = MutableStateFlow(partner)
        val household = MutableStateFlow(household)
        val ring = MutableStateFlow<RingState?>(null)
        val ringing = FakeRingingController()
        val scheduler = FakeAlarmScheduler()
        val media = FakeLinkedMediaCache()
        val notifier = me.shawnsquire.tandem.sync.FakeCommandNotifier()
        val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
        var check: HouseholdCheck = HouseholdCheck.Unpaired
        var checkFailure: Exception? = null
        var checkCalls = 0
        val sync = LinkSyncImpl(
            partnerFlow ?: this.partner, this.household, alarms, ringing, ring, scheduler, media, notifier, removals, clock,
            scope.backgroundScope,
            confirmHousehold = { checkCalls++; checkFailure?.let { throw it }; check },
            unpairSettle = 30.seconds, confirmRetry = 5.minutes,
        )

        suspend fun rows(): List<Alarm> = fake.linkedAlarms.first()
        suspend fun row(id: String = "row1"): Alarm? = fake.get(id)
    }

    private fun TestScope.env(
        now: Instant = monday,
        rows: List<Alarm> = listOf(row()),
        partner: PartnerState? = null,
        household: Household? = hh,
        repo: ((FakeAlarmRepository) -> AlarmRepository)? = null,
        partnerFlow: Flow<PartnerState?>? = null,
    ) = Env(this@LinkSyncImplTest, this, now, rows, repo, household = household, partner = partner, partnerFlow = partnerFlow)

    private suspend fun TestScope.started(env: Env): Env {
        env.sync.start()
        runCurrent()
        return env
    }

    // --- copy ---

    @Test fun `copies the owner's vibration and switches and keeps the joiner's overrides`() = runTest {
        val overrides = row().link!!.copy(
            overrideVibration = VibrationPattern.RAPID, overrideVibrationOn = false, overrideSoundOn = true,
        )
        val e = started(env(partner = null, rows = listOf(row().copy(link = overrides))))
        e.partner.value = state(owner().copy(vibration = VibrationPattern.SLOW_WAVE, vibrationOn = false, soundOn = false))
        runCurrent()
        val r = e.row()!!
        assertEquals(VibrationPattern.SLOW_WAVE, r.vibration)
        assertFalse(r.vibrationOn)
        assertFalse(r.soundOn)
        assertEquals(VibrationPattern.RAPID, r.link!!.overrideVibration)
        assertEquals(false, r.link!!.overrideVibrationOn)
        assertEquals(true, r.link!!.overrideSoundOn)
    }

    @Test fun `copies the owner's frame and text position, follows changes and keeps the joiner's overrides`() = runTest {
        val overrides = row().link!!.copy(
            overrideWallpaperFrame = WallpaperFrame(3f, 0.1f, 0.2f), overrideTextPosition = TextPosition.TOP,
        )
        val e = started(env(partner = null, rows = listOf(row().copy(link = overrides))))
        val f1 = WallpaperFrame(2f, 0.3f, 0.7f)
        e.partner.value = state(owner().copy(wallpaperFrame = f1, textPosition = TextPosition.BOTTOM))
        runCurrent()
        assertEquals(f1, e.row()!!.wallpaperFrame)
        assertEquals(TextPosition.BOTTOM, e.row()!!.textPosition)
        assertEquals(overrides, e.row()!!.link!!.copy(ownerSkippedOccurrenceAt = null))
        // Only the frame changes: the row must be rewritten.
        val f2 = WallpaperFrame(1.5f, 0.6f, 0.4f)
        e.partner.value = state(owner().copy(wallpaperFrame = f2, textPosition = TextPosition.BOTTOM))
        runCurrent()
        assertEquals(f2, e.row()!!.wallpaperFrame)
        e.partner.value = state(owner().copy(wallpaperFrame = f2, textPosition = TextPosition.TOP))
        runCurrent()
        assertEquals(TextPosition.TOP, e.row()!!.textPosition)
        e.partner.value = state(owner())
        runCurrent()
        assertNull(e.row()!!.wallpaperFrame)
        assertEquals(TextPosition.MIDDLE, e.row()!!.textPosition)
        assertEquals(WallpaperFrame(3f, 0.1f, 0.2f), e.row()!!.link!!.overrideWallpaperFrame)
        assertEquals(TextPosition.TOP, e.row()!!.link!!.overrideTextPosition)
    }

    @Test fun `copies the owner's late ring and a later doc without it clears it`() = runTest {
        val e = started(env(partner = null))
        val x = Instant.parse("2026-03-03T06:30:00Z")
        e.partner.value = state(owner().copy(lateRing = me.shawnsquire.tandem.schedule.LateRing(x, false)))
        runCurrent()
        assertEquals(me.shawnsquire.tandem.schedule.LateRing(x, false), e.row()!!.lateRing)
        e.partner.value = state(owner().copy(lateRing = me.shawnsquire.tandem.schedule.LateRing(x, true)))
        runCurrent()
        assertEquals(me.shawnsquire.tandem.schedule.LateRing(x, true), e.row()!!.lateRing)
        e.partner.value = state(owner())
        runCurrent()
        assertNull(e.row()!!.lateRing)
    }

    @Test fun `copies owner fields and never touches the joiner's own fields`() = runTest {
        val skip = Instant.parse("2026-03-03T06:45:00Z")
        val e = started(env(partner = null))
        e.partner.value = state(owner(hour = 7, minute = 45, days = setOf(DayOfWeek.FRIDAY), enabled = false, label = "New", skip = skip))
        runCurrent()
        val r = e.row()!!
        assertEquals(7, r.hour)
        assertEquals(45, r.minute)
        assertEquals(setOf(DayOfWeek.FRIDAY), r.days)
        assertFalse(r.enabled)
        assertEquals("New", r.label)
        assertEquals(skip, r.link!!.ownerSkippedOccurrenceAt)
        // Joiner-owned fields are untouched.
        assertTrue(r.link!!.joinerEnabled)
        assertEquals(LinkMode.NOTIFY, r.link!!.mode)
        assertEquals("my label", r.link!!.overrideLabel)
        assertEquals("content://override", r.link!!.overrideRingtoneUri)
        assertEquals("/override.jpg", r.link!!.overrideWallpaperPath)
        assertEquals("content://mine", r.ringtoneUri)
        assertEquals("/mine.jpg", r.wallpaperPath)
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"), r.soloSkippedOccurrenceAt)
        assertNull(r.skippedOccurrenceAt)
    }

    @Test fun `reschedules only when a pass changed something`() = runTest {
        val e = started(env())
        e.partner.value = state(owner(hour = 7))
        runCurrent()
        assertEquals(1, e.scheduler.rescheduleCount)
        e.partner.value = state(owner(hour = 7), owner(id = "other"))
        runCurrent()
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test fun `one failing row does not stop the others`() = runTest {
        val rows = listOf(row("bad", ownerAlarmId = "ob"), row("good", ownerAlarmId = "og"))
        val e = started(env(rows = rows, repo = { fake ->
            object : AlarmRepository by fake {
                override suspend fun upsertLinked(alarm: Alarm) {
                    if (alarm.id == "bad") throw IllegalStateException("collision")
                    fake.upsertLinked(alarm)
                }
            }
        }))
        e.partner.value = state(owner("ob", hour = 8), owner("og", hour = 9))
        runCurrent()
        assertEquals(6, e.row("bad")!!.hour)
        assertEquals(9, e.row("good")!!.hour)
    }

    // --- turn-off notification ---

    private suspend fun TestScope.turnOff(
        row: Alarm,
        now: Instant = monday,
        ctx: RingContext = RingContext(null, emptyList(), emptyList(), null),
    ): Env {
        val e = env(now = now, rows = listOf(row))
        e.ringing.ringContextResult = ctx
        started(e)
        e.partner.value = state(owner(enabled = false, days = row.days, hour = row.hour, minute = row.minute))
        runCurrent()
        return e
    }

    @Test fun `repeating alarm turned off notifies`() = runTest {
        val e = turnOff(row())
        assertEquals(1, e.notifier.ownerTurnedOff.size)
        assertEquals("Shawn", e.notifier.ownerTurnedOff[0].partnerName)
        assertEquals("row1", e.notifier.ownerTurnedOff[0].alarm.id)
    }

    @Test fun `no turn-off notification when joinerEnabled is false`() = runTest {
        val e = turnOff(row(joinerEnabled = false))
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
        assertFalse(e.row()!!.enabled)
    }

    @Test fun `no turn-off notification when a both-off is pending, even though it is cleared in the same pass`() = runTest {
        val e = turnOff(row(pendingBoth = PendingBoth(CommandType.DISABLE, null, "c1")))
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
        assertNull(e.row()!!.link!!.pendingBoth)
    }

    @Test fun `no turn-off notification for a pending both-skip on a one-shot, which is cleared`() = runTest {
        val x = Instant.parse("2026-03-02T06:30:00Z")
        val e = turnOff(row(days = emptySet(), pendingBoth = PendingBoth(CommandType.SKIP_NEXT, x, "c1")))
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
        assertNull(e.row()!!.link!!.pendingBoth)
    }

    @Test fun `one-shot turned off before it rings notifies`() = runTest {
        val e = turnOff(row(days = emptySet()))
        assertEquals(1, e.notifier.ownerTurnedOff.size)
    }

    @Test fun `one-shot that rang and was dismissed by the owner does not notify while the copy is snoozed`() = runTest {
        val now = Instant.parse("2026-03-02T06:35:00Z")
        val snoozed = RingContext(
            null, emptyList(), listOf(ParkedSnooze("row1", Instant.parse("2026-03-02T06:30:00Z"), now.plusSeconds(300))),
            Instant.parse("2026-03-02T06:30:00Z"),
        )
        val e = turnOff(row(days = emptySet()), now, snoozed)
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
    }

    @Test fun `one-shot that already rang on this phone does not notify`() = runTest {
        val now = Instant.parse("2026-03-02T06:35:00Z")
        val rang = RingContext(null, emptyList(), emptyList(), Instant.parse("2026-03-02T06:30:00Z"))
        val e = turnOff(row(days = emptySet()), now, rang)
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
    }

    @Test fun `one-shot just past its time within the grace does not notify`() = runTest {
        val e = turnOff(row(days = emptySet()), Instant.parse("2026-03-02T06:31:00Z"))
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
    }

    @Test fun `one-shot whose time passed long ago without ringing here is treated as still future`() = runTest {
        // Owner skipped-then-turned off before it ever rang here: nothing rang, so the turn-off is news.
        val e = turnOff(row(days = emptySet()), Instant.parse("2026-03-02T09:00:00Z"))
        assertEquals(1, e.notifier.ownerTurnedOff.size)
    }

    @Test fun `one-shot ringing right now or merged does not notify`() = runTest {
        val now = Instant.parse("2026-03-02T09:00:00Z")
        val ring = RingState("row1", "r1", now, null, Instant.parse("2026-03-02T06:30:00Z"))
        val e = turnOff(row(days = emptySet()), now, RingContext(ring, emptyList(), emptyList(), null))
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
        val merged = RingContext(
            ring.copy(alarmId = "x"), listOf(RingMember("row1", ring.occurrenceAt!!)), emptyList(), null,
        )
        val e2 = turnOff(row(days = emptySet()), now, merged)
        assertTrue(e2.notifier.ownerTurnedOff.isEmpty())
    }

    // A solo-skipped one-shot: the owner skipped only its own ring of X, so the joiner rang. At
    // X + FIRE_GRACE skip expiry turns the owner's one-shot off and it publishes enabled = false (the
    // solo skip itself was dropped at X). That is the one-shot finishing, not a turn-off.
    private val soloX = Instant.parse("2026-03-02T06:30:00Z")
    private val pastSoloGrace = Instant.parse("2026-03-02T06:32:30Z")

    @Test fun `solo-skipped one-shot expiring after the joiner rang and dismissed does not notify`() = runTest {
        val rang = RingContext(null, emptyList(), emptyList(), soloX)
        val e = turnOff(row(days = emptySet(), joinerEnabled = false), pastSoloGrace, rang)
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
        assertFalse(e.row()!!.enabled)
        assertFalse(e.row()!!.link!!.joinerEnabled)
    }

    @Test fun `solo-skipped one-shot expiring after the joiner rang does not notify before its dismiss lands`() = runTest {
        val rang = RingContext(null, emptyList(), emptyList(), soloX)
        val e = turnOff(row(days = emptySet()), pastSoloGrace, rang)
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
    }

    @Test fun `solo-skipped one-shot expiring while the joiner is snoozed does not notify or touch the snooze`() = runTest {
        val snoozed = RingContext(
            null, emptyList(), listOf(ParkedSnooze("row1", soloX, pastSoloGrace.plusSeconds(300))), soloX,
        )
        val e = turnOff(row(days = emptySet()), pastSoloGrace, snoozed)
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
        assertTrue(e.ringing.dismissed.isEmpty())
        assertEquals(0, e.ringing.snoozeCount)
        assertTrue(e.row()!!.link!!.joinerEnabled) // the snooze's own dismiss turns the copy off later
    }

    @Test fun `notifies once not again on the next identical pass`() = runTest {
        val e = turnOff(row())
        e.partner.value = state(owner(enabled = false), owner("other"))
        runCurrent()
        assertEquals(1, e.notifier.ownerTurnedOff.size)
    }

    // --- O9 ---

    @Test fun `re-enabled one-shot resets joinerEnabled`() = runTest {
        val e = started(env(rows = listOf(row(days = emptySet(), enabled = false, joinerEnabled = false))))
        e.partner.value = state(owner(days = emptySet(), enabled = true))
        runCurrent()
        assertTrue(e.row()!!.enabled)
        assertTrue(e.row()!!.link!!.joinerEnabled)
    }

    @Test fun `re-enabled repeating alarm keeps joinerEnabled`() = runTest {
        val e = started(env(rows = listOf(row(enabled = false, joinerEnabled = false))))
        e.partner.value = state(owner(enabled = true))
        runCurrent()
        assertTrue(e.row()!!.enabled)
        assertFalse(e.row()!!.link!!.joinerEnabled)
    }

    // --- pendingBoth ---

    private suspend fun TestScope.pendingAfter(row: Alarm, doc: PartnerAlarm): PendingBoth? {
        val e = started(env(rows = listOf(row)))
        e.partner.value = state(doc)
        runCurrent()
        return e.row()!!.link!!.pendingBoth
    }

    private val x = Instant.parse("2026-03-02T06:30:00Z")

    @Test fun `pending skip clears when the skip is copied and not before`() = runTest {
        val pb = PendingBoth(CommandType.SKIP_NEXT, x, "c1")
        assertEquals(pb, pendingAfter(row(pendingBoth = pb), owner()))
        assertNull(pendingAfter(row(pendingBoth = pb), owner(skip = x)))
        assertEquals(pb, pendingAfter(row(pendingBoth = pb), owner(skip = x.plusSeconds(86400))))
    }

    @Test fun `pending skip on a one-shot clears when enabled goes false`() = runTest {
        val pb = PendingBoth(CommandType.SKIP_NEXT, x, "c1")
        val r = row(days = emptySet(), pendingBoth = pb)
        assertEquals(pb, pendingAfter(r, owner(days = emptySet())))
        assertNull(pendingAfter(r, owner(days = emptySet(), enabled = false)))
    }

    @Test fun `pending unskip clears when the skip is gone`() = runTest {
        val pb = PendingBoth(CommandType.UNSKIP, x, "c1")
        val r = row(pendingBoth = pb, ownerSkip = x)
        assertEquals(pb, pendingAfter(r, owner(skip = x)))
        assertNull(pendingAfter(r, owner()))
    }

    @Test fun `pending disable and enable clear when enabled matches`() = runTest {
        val off = PendingBoth(CommandType.DISABLE, x, "c1")
        assertEquals(off, pendingAfter(row(pendingBoth = off), owner(enabled = true)))
        assertNull(pendingAfter(row(pendingBoth = off), owner(enabled = false)))
        val on = PendingBoth(CommandType.ENABLE, x, "c2")
        assertEquals(on, pendingAfter(row(enabled = false, pendingBoth = on), owner(enabled = false)))
        assertNull(pendingAfter(row(enabled = false, pendingBoth = on), owner(enabled = true)))
    }

    // --- owner deletes ---

    @Test fun `owner alarm gone removes the row, notifies and reschedules`() = runTest {
        val e = started(env())
        e.partner.value = state(owner("other"))
        runCurrent()
        assertTrue(e.rows().isEmpty())
        assertEquals(1, e.notifier.ownerDeleted.size)
        assertEquals("row1", e.notifier.ownerDeleted[0].alarm.id)
        assertEquals("Shawn", e.notifier.ownerDeleted[0].partnerName)
        assertEquals(1, e.scheduler.rescheduleCount)
        assertTrue(e.removals.all().isEmpty())
    }

    @Test fun `blank partner name falls back`() = runTest {
        val e = started(env())
        e.partner.value = state(owner("other"), name = " ")
        runCurrent()
        assertEquals("Your partner", e.notifier.ownerDeleted[0].partnerName)
    }

    @Test fun `owner alarm gone while ringing waits until the ring ends`() = runTest {
        val e = started(env())
        e.ringing.ringContextResult = RingContext(
            RingState("row1", "r1", monday, null, x), emptyList(), emptyList(), null,
        )
        e.partner.value = state(owner("other"))
        runCurrent()
        assertEquals(1, e.rows().size)
        assertTrue(e.notifier.ownerDeleted.isEmpty())
        assertEquals(setOf("row1"), e.removals.pendingIds.first())

        e.ringing.ringContextResult = RingContext(null, emptyList(), emptyList(), null)
        e.ring.value = RingState("row1", "r1", monday, null, x)
        runCurrent()
        e.ring.value = null
        runCurrent()
        assertTrue(e.rows().isEmpty())
        assertEquals(1, e.notifier.ownerDeleted.size)
        assertTrue(e.removals.all().isEmpty())

        e.ring.value = RingState("zzz", "r2", monday, null, x)
        runCurrent()
        assertEquals(1, e.notifier.ownerDeleted.size)
    }

    @Test fun `a snoozed row also counts as ringing`() = runTest {
        val e = started(env())
        e.ringing.ringContextResult = RingContext(
            null, emptyList(), listOf(ParkedSnooze("row1", x, monday.plusSeconds(60))), null,
        )
        e.partner.value = state(owner("other"))
        runCurrent()
        assertEquals(1, e.rows().size)
    }

    @Test fun `pending removal survives a restart and is completed once`() = runTest {
        val e = env()
        e.removals.add("row1", RemovalReason.OWNER_DELETED)
        started(e)
        assertTrue(e.rows().isEmpty())
        assertEquals(1, e.notifier.ownerDeleted.size)
    }

    @Test fun `requested leave removes after the ring and never notifies`() = runTest {
        val e = started(env())
        e.ringing.ringContextResult = RingContext(
            RingState("row1", "r1", monday, null, x), emptyList(), emptyList(), null,
        )
        e.sync.requestRemoval("row1", RemovalReason.LEFT)
        assertEquals(1, e.rows().size)
        e.ringing.ringContextResult = RingContext(null, emptyList(), emptyList(), null)
        e.ring.value = RingState("row1", "r1", monday, null, x)
        runCurrent()
        e.ring.value = null
        runCurrent()
        assertTrue(e.rows().isEmpty())
        assertTrue(e.notifier.ownerDeleted.isEmpty())
    }

    // --- unpair ---

    @Test fun `partner null while the household is known keeps every row`() = runTest {
        val e = started(env(rows = listOf(row(), row("row2", "o2")), partner = state(owner(), owner("o2"))))
        e.partner.value = null
        runCurrent()
        advanceTimeBy(120.seconds)
        assertEquals(2, e.rows().size)
        assertTrue(e.notifier.ownerDeleted.isEmpty())
    }

    @Test fun `settled null household removes every row without notifying`() = runTest {
        val e = started(env(rows = listOf(row(), row("row2", "o2")), partner = state(owner(), owner("o2"))))
        val before = e.scheduler.rescheduleCount
        e.household.value = null
        runCurrent()
        advanceTimeBy(29.seconds)
        assertEquals(2, e.rows().size)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(e.rows().isEmpty())
        assertTrue(e.notifier.ownerDeleted.isEmpty())
        assertEquals(before + 1, e.scheduler.rescheduleCount)
    }

    @Test fun `household blip that returns within the window removes nothing`() = runTest {
        val e = started(env(partner = state(owner())))
        e.household.value = null
        runCurrent()
        advanceTimeBy(10.seconds)
        e.household.value = hh
        runCurrent()
        advanceTimeBy(120.seconds)
        assertEquals(1, e.rows().size)
    }

    @Test fun `null household never preceded by a household in this process removes nothing`() = runTest {
        val e = started(env(household = null))
        advanceTimeBy(120.seconds)
        assertEquals(1, e.rows().size)
    }

    @Test fun `re-pair to someone else removes rows of the old partner`() = runTest {
        val e = started(env(rows = listOf(row(), row("row2", "o2")), partner = state(owner(), owner("o2"))))
        e.household.value = Household("h2", "me", "new")
        runCurrent()
        assertTrue(e.rows().isEmpty())
        // Not an owner delete, so no notification.
        assertTrue(e.notifier.ownerDeleted.isEmpty())
    }

    @Test fun `owner alarm absent in a stale doc of another partner is not treated as deleted`() = runTest {
        val e = started(env(rows = listOf(row(ownerUid = "them")), household = Household("h1", "me", "them")))
        // Doc arrives, but for rows whose owner is not this household's partner nothing is deleted as 'gone'.
        e.household.value = Household("h2", "me", "other")
        e.partner.value = state(owner("zzz"))
        runCurrent()
        assertTrue(e.notifier.ownerDeleted.isEmpty())
    }

    @Test fun `removeAll removes every row now, deferring a ringing one`() = runTest {
        val e = started(env(rows = listOf(row(), row("row2", "o2"))))
        e.ringing.ringContextResult = RingContext(
            RingState("row2", "r1", monday, null, x), emptyList(), emptyList(), null,
        )
        e.sync.removeAll()
        assertEquals(listOf("row2"), e.rows().map { it.id })
        e.ringing.ringContextResult = RingContext(null, emptyList(), emptyList(), null)
        e.ring.value = RingState("row2", "r1", monday, null, x)
        runCurrent()
        assertTrue(e.rows().isEmpty())
        assertTrue(e.notifier.ownerDeleted.isEmpty())
    }

    // --- apply ---

    @Test fun `apply runs the same pass for one doc read`() = runTest {
        val e = started(env())
        e.sync.apply(state(owner(hour = 9, enabled = false)))
        assertEquals(9, e.row()!!.hour)
        assertEquals(1, e.notifier.ownerTurnedOff.size)
    }

    @Test fun `apply with no household does nothing`() = runTest {
        val e = started(env(household = null))
        e.sync.apply(state(owner("other")))
        assertEquals(1, e.rows().size)
        assertTrue(e.notifier.ownerDeleted.isEmpty())
    }

    // --- media ---

    @Test fun `passes owner media for linked alarms and skips unchanged arguments`() = runTest {
        val m = AlarmMedia(MediaRef("sha1", 10), null)
        val wp = MediaRef("sha2", 20)
        val e = started(env())
        e.partner.value = state(owner(media = m), owner("unlinked", media = AlarmMedia(MediaRef("sha3", 5), null)), wallpaper = wp)
        runCurrent()
        assertEquals(listOf(FakeLinkedMediaCache.Update("them", mapOf("o1" to m), wp)), e.media.updates)
        e.partner.value = state(owner(media = m), owner("unlinked"), wallpaper = wp)
        runCurrent()
        assertEquals(1, e.media.updates.size)
    }

    @Test fun `passes the owner's default wallpaper frame and re-passes it when only the frame changes`() = runTest {
        val wp = MediaRef("sha2", 20)
        val f1 = WallpaperFrame(2f, 0.3f, 0.7f)
        val e = started(env())
        e.partner.value = state(owner(), wallpaper = wp, wallpaperFrame = f1)
        runCurrent()
        assertEquals(listOf(FakeLinkedMediaCache.Update("them", emptyMap(), wp, f1)), e.media.updates)
        val f2 = WallpaperFrame(3f, 0.5f, 0.5f)
        e.partner.value = state(owner(), wallpaper = wp, wallpaperFrame = f2)
        runCurrent()
        assertEquals(f2, e.media.updates.last().defaultWallpaperFrame)
        assertEquals(2, e.media.updates.size)
        e.partner.value = state(owner(), wallpaper = wp, wallpaperFrame = f2, seen = Instant.parse("2026-03-02T05:01:00Z"))
        runCurrent()
        assertEquals(2, e.media.updates.size)
    }

    @Test fun `a failing media update keeps the row's frame but still copies its schedule, and the next pass catches up`() = runTest {
        val e = started(env(partner = null))
        val old = WallpaperFrame(2f, 0.3f, 0.7f)
        val m1 = AlarmMedia(MediaRef("sha1", 10), null)
        e.partner.value = state(owner(media = m1).copy(wallpaperFrame = old))
        runCurrent()
        assertEquals(old, e.row()!!.wallpaperFrame)
        e.media.updateFailure = IllegalStateException("disk")
        val next = WallpaperFrame(1.5f, 0.6f, 0.4f)
        val m2 = AlarmMedia(MediaRef("sha2", 20), null)
        e.partner.value = state(owner(media = m2, hour = 7).copy(wallpaperFrame = next))
        runCurrent()
        assertEquals(7, e.row()!!.hour) // I1: the schedule is never held back
        assertEquals(old, e.row()!!.wallpaperFrame) // the new frame would sit over the old cached image
        e.media.updateFailure = null
        e.partner.value = state(owner(media = m2, hour = 7).copy(wallpaperFrame = next), seen = Instant.parse("2026-03-02T05:01:00Z"))
        runCurrent()
        assertEquals(next, e.row()!!.wallpaperFrame)
        assertEquals(m2, e.media.updates.last().alarms["o1"])
    }

    @Test fun `no media update while the partner state is unknown`() = runTest {
        val e = started(env())
        runCurrent()
        assertTrue(e.media.updates.isEmpty())
    }

    @Test fun `start is idempotent`() = runTest {
        val e = env()
        e.sync.start()
        e.sync.start()
        runCurrent()
        e.partner.value = state(owner(enabled = false))
        runCurrent()
        assertEquals(1, e.notifier.ownerTurnedOff.size)
        assertNotNull(e.row())
    }

    // --- hardening ---

    @Test fun `a crash between the O9 reset and the copy converges on the next pass`() = runTest {
        var failOnce = true
        val e = started(env(rows = listOf(row(days = emptySet(), enabled = false, joinerEnabled = false)), repo = { fake ->
            object : AlarmRepository by fake {
                override suspend fun upsertLinked(alarm: Alarm) {
                    if (failOnce) { failOnce = false; throw IllegalStateException("died") }
                    fake.upsertLinked(alarm)
                }
            }
        }))
        e.partner.value = state(owner(days = emptySet(), enabled = true))
        runCurrent()
        // Reset already written, copy not yet: enabled is still the old value, so a retry re-derives O9.
        assertFalse(e.row()!!.enabled)
        assertTrue(e.row()!!.link!!.joinerEnabled)
        e.partner.value = state(owner(days = emptySet(), enabled = true, label = "again"))
        runCurrent()
        assertTrue(e.row()!!.enabled)
        assertTrue(e.row()!!.link!!.joinerEnabled)
    }

    @Test fun `a crash between the turn-off notification and the copy re-posts and keeps a pending both-off`() = runTest {
        var failOnce = true
        val e = started(env(rows = listOf(row(joinerEnabled = true)), repo = { fake ->
            object : AlarmRepository by fake {
                override suspend fun upsertLinked(alarm: Alarm) {
                    if (failOnce) { failOnce = false; throw IllegalStateException("died") }
                    fake.upsertLinked(alarm)
                }
            }
        }))
        e.partner.value = state(owner(enabled = false))
        runCurrent()
        assertEquals(1, e.notifier.ownerTurnedOff.size)
        e.partner.value = state(owner(enabled = false, label = "again"))
        runCurrent()
        assertFalse(e.row()!!.enabled)
        assertEquals(2, e.notifier.ownerTurnedOff.size)
        e.partner.value = state(owner(enabled = false, label = "third"))
        runCurrent()
        assertEquals(2, e.notifier.ownerTurnedOff.size)
    }

    @Test fun `a pending both-off survives a crash before the copy so the retry stays silent`() = runTest {
        var failOnce = true
        val pb = PendingBoth(CommandType.DISABLE, null, "c1")
        val e = started(env(rows = listOf(row(pendingBoth = pb)), repo = { fake ->
            object : AlarmRepository by fake {
                override suspend fun upsertLinked(alarm: Alarm) {
                    if (failOnce) { failOnce = false; throw IllegalStateException("died") }
                    fake.upsertLinked(alarm)
                }
            }
        }))
        e.partner.value = state(owner(enabled = false))
        runCurrent()
        e.partner.value = state(owner(enabled = false, label = "again"))
        runCurrent()
        assertFalse(e.row()!!.enabled)
        assertNull(e.row()!!.link!!.pendingBoth)
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
    }

    @Test fun `a stale doc is never paired with a new household`() = runTest {
        val cold = MutableSharedFlow<PartnerState?>()
        val e = env(rows = emptyList(), household = Household("h0", "me", "old"), partnerFlow = cold)
        started(e)
        cold.emit(state(owner("zzz"))) // the old partner's doc, lacking o1
        runCurrent()
        e.household.value = hh // re-paired to "them"
        runCurrent()
        e.fake.upsert(row())
        runCurrent()
        advanceTimeBy(60.seconds)
        assertEquals(1, e.rows().size)
        assertTrue(e.notifier.ownerDeleted.isEmpty())
        // A doc received under this household is trusted.
        cold.emit(state(owner("zzz")))
        runCurrent()
        assertTrue(e.rows().isEmpty())
        assertEquals(1, e.notifier.ownerDeleted.size)
    }

    @Test fun `no media update when no linked row belongs to the partner`() = runTest {
        val e = started(env(rows = emptyList()))
        e.partner.value = state(owner(media = AlarmMedia(MediaRef("sha1", 10), null)))
        runCurrent()
        assertTrue(e.media.updates.isEmpty())
    }

    @Test fun `unpair overrides a pending owner-deleted so nothing is posted`() = runTest {
        val e = started(env())
        e.ringing.ringContextResult = RingContext(
            RingState("row1", "r1", monday, null, x), emptyList(), emptyList(), null,
        )
        e.partner.value = state(owner("other"))
        runCurrent()
        e.sync.removeAll()
        e.ringing.ringContextResult = RingContext(null, emptyList(), emptyList(), null)
        e.ring.value = RingState("row1", "r1", monday, null, x)
        runCurrent()
        assertTrue(e.rows().isEmpty())
        assertTrue(e.notifier.ownerDeleted.isEmpty())
    }

    @Test fun `a pending both with a newer command id is not cleared`() = runTest {
        val pb = PendingBoth(CommandType.DISABLE, null, "c1")
        val e = started(env(rows = listOf(row(pendingBoth = pb)), repo = { fake ->
            object : AlarmRepository by fake {
                override suspend fun updateLink(id: String, transform: (AlarmLink) -> AlarmLink): Boolean {
                    // A newer "both" lands between evaluation and write.
                    fake.updateLink(id) { it.copy(pendingBoth = PendingBoth(CommandType.ENABLE, null, "c2")) }
                    return fake.updateLink(id, transform)
                }
            }
        }))
        e.partner.value = state(owner(enabled = false))
        runCurrent()
        assertEquals("c2", e.row()!!.link!!.pendingBoth?.commandId)
    }

    @Test fun `removal waits while the row is only a merged member`() = runTest {
        val ring = RingState("other", "r1", monday, null, x, merged = listOf(RingMember("row1", x)))
        val viaRing = started(env())
        viaRing.ringing.ringContextResult = RingContext(ring, emptyList(), emptyList(), null)
        viaRing.partner.value = state(owner("gone"))
        runCurrent()
        assertEquals(1, viaRing.rows().size)

        val viaCtx = started(env())
        viaCtx.ringing.ringContextResult = RingContext(
            ring.copy(merged = emptyList()), listOf(RingMember("row1", x)), emptyList(), null,
        )
        viaCtx.partner.value = state(owner("gone"))
        runCurrent()
        assertEquals(1, viaCtx.rows().size)
    }

    @Test fun `one-shot pending skip for another occurrence still suppresses the turn-off notice`() = runTest {
        val other = Instant.parse("2026-03-03T06:30:00Z")
        val e = turnOff(row(days = emptySet(), pendingBoth = PendingBoth(CommandType.SKIP_NEXT, other, "c1")))
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
    }

    // --- server-confirmed unpair ---

    @Test fun `settled null household with an unknown server answer keeps rows and retries`() = runTest {
        val e = started(env(rows = listOf(row(), row("row2", "o2"))))
        e.check = HouseholdCheck.Unknown
        e.household.value = null
        runCurrent()
        advanceTimeBy(31.seconds)
        assertEquals(1, e.checkCalls)
        assertEquals(2, e.rows().size)
        advanceTimeBy(5.minutes)
        assertEquals(2, e.checkCalls)
        assertEquals(2, e.rows().size)
        e.check = HouseholdCheck.Unpaired
        advanceTimeBy(5.minutes)
        runCurrent()
        assertTrue(e.rows().isEmpty())
    }

    @Test fun `a failing check counts as unknown`() = runTest {
        val e = started(env())
        e.checkFailure = IllegalStateException("offline")
        e.household.value = null
        runCurrent()
        advanceTimeBy(31.seconds)
        assertEquals(1, e.rows().size)
        assertEquals(1, e.checkCalls)
    }

    @Test fun `settled null with the server saying paired to someone else removes only the old partner's rows`() = runTest {
        val e = started(env(rows = listOf(row())))
        e.household.value = null
        runCurrent()
        e.fake.upsert(row("row2", "o2", ownerUid = "new"))
        runCurrent()
        e.check = HouseholdCheck.Paired(Household("h2", "me", "new"))
        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(listOf("row2"), e.rows().map { it.id })
        // Checked once, not on a timer.
        advanceTimeBy(20.minutes)
        assertEquals(1, e.checkCalls)
    }

    @Test fun `server says paired to the same partner keeps every row`() = runTest {
        val e = started(env())
        e.check = HouseholdCheck.Paired(hh)
        e.household.value = null
        runCurrent()
        advanceTimeBy(31.seconds)
        assertEquals(1, e.rows().size)
    }

    @Test fun `household returning during the retry cancels it`() = runTest {
        val e = started(env())
        e.check = HouseholdCheck.Unknown
        e.household.value = null
        runCurrent()
        advanceTimeBy(31.seconds)
        e.household.value = hh
        runCurrent()
        e.check = HouseholdCheck.Unpaired
        advanceTimeBy(20.minutes)
        assertEquals(1, e.checkCalls)
        assertEquals(1, e.rows().size)
    }

    // --- minors ---

    @Test fun `media refs are cleared once the last linked row of the partner is gone`() = runTest {
        val m = AlarmMedia(MediaRef("sha1", 10), null)
        val e = started(env())
        e.partner.value = state(owner(media = m), wallpaper = MediaRef("sha2", 20))
        runCurrent()
        assertEquals(1, e.media.updates.size)
        e.partner.value = state(owner("other"))
        runCurrent()
        assertTrue(e.rows().isEmpty())
        assertEquals(FakeLinkedMediaCache.Update("them", emptyMap(), null), e.media.updates.last())
        val n = e.media.updates.size
        e.partner.value = state(owner("other"), owner("another"))
        runCurrent()
        assertEquals(n, e.media.updates.size)
    }

    @Test fun `media refs are cleared after an unpair`() = runTest {
        val e = started(env())
        e.partner.value = state(owner(media = AlarmMedia(MediaRef("sha1", 10), null)))
        runCurrent()
        e.household.value = null
        runCurrent()
        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(FakeLinkedMediaCache.Update("them", emptyMap(), null), e.media.updates.last())
    }

    @Test fun `cancelRemoval keeps a row whose leave was pending while ringing`() = runTest {
        val e = started(env())
        e.ringing.ringContextResult = RingContext(
            RingState("row1", "r1", monday, null, x), emptyList(), emptyList(), null,
        )
        e.sync.requestRemoval("row1", RemovalReason.LEFT)
        assertTrue(e.sync.cancelRemoval("row1"))
        e.ringing.ringContextResult = RingContext(null, emptyList(), emptyList(), null)
        e.ring.value = RingState("row1", "r1", monday, null, x)
        runCurrent()
        e.ring.value = null
        runCurrent()
        assertEquals(1, e.rows().size)
        assertTrue(e.removals.all().isEmpty())
        assertFalse(e.sync.cancelRemoval("missing"))
    }

    @Test fun `requestRemoval surfaces a failed durable write`() = runTest {
        val broken = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flow<androidx.datastore.preferences.core.Preferences> {
                throw java.io.IOException("disk")
            }
            override suspend fun updateData(
                transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
            ): androidx.datastore.preferences.core.Preferences = throw java.io.IOException("disk")
        }
        val e = Env(this@LinkSyncImplTest, this, monday, listOf(row()), removals = LinkRemovals(broken))
        var thrown: Exception? = null
        try {
            e.sync.requestRemoval("row1", RemovalReason.LEFT)
        } catch (x: java.io.IOException) {
            thrown = x
        }
        assertNotNull(thrown)
        assertEquals(1, e.rows().size)
    }

    // --- applied both-of-us commands ---

    private val t1 = Instant.parse("2026-03-02T04:59:00Z")
    private val t2 = Instant.parse("2026-03-02T05:00:01Z")

    @Test fun `applied both-off stays pending on the cached doc and clears silently on the owner's new doc`() = runTest {
        val pb = PendingBoth(CommandType.DISABLE, null, "c1")
        val e = started(env(rows = listOf(row(pendingBoth = pb))))
        e.partner.value = state(owner(), seen = t1)
        runCurrent()
        e.sync.noteApplied("row1", "c1")
        e.partner.value = state(owner(label = "cached again"), seen = t1)
        runCurrent()
        assertEquals(pb, e.row()!!.link!!.pendingBoth)
        e.partner.value = state(owner(enabled = false), seen = t2)
        runCurrent()
        assertNull(e.row()!!.link!!.pendingBoth)
        assertFalse(e.row()!!.enabled)
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
    }

    @Test fun `applied both-off stays pending against the cached doc even when the cached doc turns the copy off`() = runTest {
        // The doc that finally shows enabled=false must not post a notice for the joiner's own command.
        val pb = PendingBoth(CommandType.DISABLE, null, "c1")
        val e = started(env(rows = listOf(row(pendingBoth = pb))))
        e.sync.noteApplied("row1", "c1")
        e.partner.value = state(owner(enabled = false), seen = t2)
        runCurrent()
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
        assertNull(e.row()!!.link!!.pendingBoth)
    }

    @Test fun `applied then reversed by the owner clears pending, re-arms and does not notify`() = runTest {
        val pb = PendingBoth(CommandType.DISABLE, null, "c1")
        val e = started(env(rows = listOf(row(pendingBoth = pb))))
        e.partner.value = state(owner(), seen = t1)
        runCurrent()
        e.sync.noteApplied("row1", "c1")
        e.partner.value = state(owner(enabled = true), seen = t2)
        runCurrent()
        assertNull(e.row()!!.link!!.pendingBoth)
        assertTrue(e.row()!!.enabled)
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
    }

    @Test fun `applied one-shot both-skip clears on a newer doc without a notice`() = runTest {
        val pb = PendingBoth(CommandType.SKIP_NEXT, x, "c1")
        val e = started(env(rows = listOf(row(days = emptySet(), pendingBoth = pb))))
        e.partner.value = state(owner(days = emptySet()), seen = t1)
        runCurrent()
        e.sync.noteApplied("row1", "c1")
        e.partner.value = state(owner(days = emptySet(), enabled = false), seen = t2)
        runCurrent()
        assertNull(e.row()!!.link!!.pendingBoth)
        assertTrue(e.notifier.ownerTurnedOff.isEmpty())
    }

    @Test fun `a newer command id is untouched by an applied older one`() = runTest {
        val pb = PendingBoth(CommandType.ENABLE, null, "c2")
        val e = started(env(rows = listOf(row(enabled = false, pendingBoth = pb))))
        e.partner.value = state(owner(enabled = false), seen = t1)
        runCurrent()
        e.sync.noteApplied("row1", "c1")
        e.partner.value = state(owner(enabled = false, label = "n"), seen = t2)
        runCurrent()
        assertEquals(pb, e.row()!!.link!!.pendingBoth)
    }

    @Test fun `stale persisted media refs are cleared once when no row remains`() = runTest {
        val e = env(rows = emptyList())
        e.media.inUseHashes += "sha1"
        started(e)
        assertEquals(listOf(FakeLinkedMediaCache.Update("them", emptyMap(), null)), e.media.updates)
        e.partner.value = state(owner())
        runCurrent()
        assertEquals(1, e.media.updates.size)
    }

    @Test fun `with no baseline a stale cached doc does not clear a pending enable, a newer one does`() = runTest {
        val pb = PendingBoth(CommandType.ENABLE, null, "c1")
        val e = started(env(rows = listOf(row(enabled = false, pendingBoth = pb))))
        e.sync.noteApplied("row1", "c1") // no doc processed yet: null baseline
        e.partner.value = state(owner(enabled = false), seen = t1) // stale, pre-apply
        runCurrent()
        assertEquals(pb, e.row()!!.link!!.pendingBoth)
        assertFalse(e.sync.appliedSettled("c1"))
        e.partner.value = state(owner(enabled = false, label = "n"), seen = t2)
        runCurrent()
        assertNull(e.row()!!.link!!.pendingBoth)
        assertTrue(e.sync.appliedSettled("c1"))
    }

    @Test fun `appliedSettled is false until a doc newer than the baseline is processed`() = runTest {
        val pb = PendingBoth(CommandType.DISABLE, null, "c1")
        val e = started(env(rows = listOf(row(pendingBoth = pb))))
        e.partner.value = state(owner(), seen = t1)
        runCurrent()
        e.sync.noteApplied("row1", "c1")
        assertFalse(e.sync.appliedSettled("c1"))
        e.partner.value = state(owner(label = "same doc"), seen = t1)
        runCurrent()
        assertFalse(e.sync.appliedSettled("c1"))
    }
}
