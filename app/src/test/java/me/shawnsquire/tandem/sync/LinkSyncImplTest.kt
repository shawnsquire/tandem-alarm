package me.shawnsquire.tandem.sync

import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
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

    private fun state(vararg alarms: PartnerAlarm, name: String = "Shawn", wallpaper: MediaRef? = null) =
        PartnerState(name, alarms.toList(), null, null, defaultWallpaper = wallpaper)

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
        val sync = LinkSyncImpl(
            partnerFlow ?: this.partner, this.household, alarms, ringing, ring, scheduler, media, notifier, removals, clock,
            scope.backgroundScope, unpairSettle = 30.seconds,
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
}
