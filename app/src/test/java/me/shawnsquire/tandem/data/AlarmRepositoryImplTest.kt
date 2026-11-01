package me.shawnsquire.tandem.data

import androidx.room.Room
import app.cash.turbine.test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.toJavaDuration
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth
import me.shawnsquire.tandem.schedule.FIRE_GRACE
import me.shawnsquire.tandem.schedule.LateRing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/** Delegates to a real DAO but fails the expired-skip cleanup write. */
private class FailingCleanupDao(private val real: AlarmDao) : AlarmDao by real {
    override suspend fun clearExpiredSkips(cutoffMillis: Long) = throw IllegalStateException("disk full")
}

@RunWith(RobolectricTestRunner::class)
class AlarmRepositoryImplTest {
    private lateinit var db: TandemDatabase
    private lateinit var dao: AlarmDao
    private val clock = MutableClock(Instant.ofEpochMilli(1_700_000_000_000))
    private lateinit var repo: AlarmRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TandemDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.alarmDao()
        repo = AlarmRepositoryImpl(dao, clock)
    }

    @After
    fun tearDown() = db.close()

    private fun alarm(
        id: String = "a",
        hour: Int = 7,
        minute: Int = 30,
        days: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
        skip: Instant? = null,
    ) = Alarm(
        id = id,
        label = "Label $id",
        hour = hour,
        minute = minute,
        days = days,
        enabled = true,
        skippedOccurrenceAt = skip,
        ringtoneUri = "content://ring/1",
        wallpaperPath = "/wall/1.jpg",
    )

    @Test
    fun upsertThenGetRoundTripsAllFields() = runTest {
        val a = alarm(skip = clock.now.plusSeconds(3600))
        repo.upsert(a)
        assertEquals(a, repo.get("a"))
    }

    @Test
    fun upsertReplacesById() = runTest {
        repo.upsert(alarm())
        repo.upsert(alarm(hour = 9))
        repo.alarms.test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(9, list.single().hour)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getMissingReturnsNull() = runTest {
        assertNull(repo.get("nope"))
    }

    @Test
    fun deleteRemoves() = runTest {
        repo.upsert(alarm())
        repo.delete("a")
        assertNull(repo.get("a"))
        assertNull(dao.get("a"))
    }

    @Test
    fun alarmsOrderedByHourMinuteId() = runTest {
        repo.upsert(alarm(id = "c", hour = 8, minute = 0))
        repo.upsert(alarm(id = "b", hour = 7, minute = 30))
        repo.upsert(alarm(id = "a", hour = 7, minute = 30))
        repo.upsert(alarm(id = "d", hour = 7, minute = 10))
        repo.alarms.test {
            assertEquals(listOf("d", "a", "b", "c"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun alarmsEmitsOnChange() = runTest {
        repo.alarms.test {
            assertTrue(awaitItem().isEmpty())
            repo.upsert(alarm())
            assertEquals(listOf("a"), awaitItem().map { it.id })
            repo.delete("a")
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setEnabledUpdatesAndEmits() = runTest {
        repo.upsert(alarm())
        repo.alarms.test {
            assertTrue(awaitItem().single().enabled)
            repo.setEnabled("a", false)
            assertFalse(awaitItem().single().enabled)
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(repo.get("a")!!.enabled)
    }

    @Test
    fun setSkipSetsAndClears() = runTest {
        repo.upsert(alarm())
        val skip = clock.now.plusSeconds(3600)
        repo.setSkip("a", skip)
        assertEquals(skip, repo.get("a")!!.skippedOccurrenceAt)
        repo.setSkip("a", null)
        assertNull(repo.get("a")!!.skippedOccurrenceAt)
    }

    @Test
    fun expiredSkipIsMappedToNullAndClearedInDbViaAlarms() = runTest {
        repo.upsert(alarm(skip = clock.now.plusSeconds(60)))
        clock.now = clock.now.plusSeconds(61).plus(FIRE_GRACE.toJavaDuration())
        repo.alarms.test {
            assertNull(awaitItem().single().skippedOccurrenceAt)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(dao.get("a")!!.skippedOccurrenceAt)
    }

    private suspend fun upsertSolo(id: String, days: Set<DayOfWeek>, solo: Instant) =
        repo.upsert(alarm(id = id, days = days).copy(soloSkippedOccurrenceAt = solo))

    @Test
    fun expiredSoloSkipTurnsOffOwnOneShotInReadAndDb() = runTest {
        val x = clock.now.plusSeconds(60)
        upsertSolo("a", emptySet(), x)
        clock.now = x.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(1)
        repo.alarms.test {
            val read = awaitItem().single()
            assertFalse(read.enabled)
            assertNull(read.soloSkippedOccurrenceAt)
            cancelAndIgnoreRemainingEvents()
        }
        val row = dao.get("a")!!
        assertFalse(row.enabled)
        assertNull(row.soloSkippedOccurrenceAt)
    }

    @Test
    fun expiredSoloSkipKeepsRepeatingAlarmEnabledInDb() = runTest {
        val x = clock.now.plusSeconds(60)
        upsertSolo("a", setOf(DayOfWeek.MONDAY), x)
        clock.now = x.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(1)
        assertTrue(repo.get("a")!!.enabled)
        val row = dao.get("a")!!
        assertTrue(row.enabled)
        assertNull(row.soloSkippedOccurrenceAt)
    }

    @Test
    fun reEnablingAOneShotWithAnExpiredSoloSkipKeepsItOn() = runTest {
        val x = clock.now.plusSeconds(60)
        upsertSolo("a", emptySet(), x)
        clock.now = x.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(1)
        // Cleanup hasn't run: the row still says enabled with the expired solo skip.
        repo.setEnabled("a", false)
        repo.setEnabled("a", true)
        val read = repo.get("a")!!
        assertTrue(read.enabled)
        assertNull(read.soloSkippedOccurrenceAt)
        assertTrue(dao.get("a")!!.enabled)
    }

    @Test
    fun reEnablingAOneShotKeepsALiveSoloSkip() = runTest {
        val x = clock.now.plusSeconds(60)
        upsertSolo("a", emptySet(), x)
        repo.setEnabled("a", false)
        repo.setEnabled("a", true)
        assertEquals(x.toEpochMilli(), dao.get("a")!!.soloSkippedOccurrenceAt)
        assertTrue(repo.get("a")!!.enabled)
    }

    @Test
    fun liveSoloSkipLeavesOneShotEnabledInDb() = runTest {
        val x = clock.now.plusSeconds(60)
        upsertSolo("a", emptySet(), x)
        clock.now = x.plusSeconds(1)
        assertTrue(repo.get("a")!!.enabled)
        val row = dao.get("a")!!
        assertTrue(row.enabled)
        assertEquals(x.toEpochMilli(), row.soloSkippedOccurrenceAt)
    }

    @Test
    fun expiredSoloSkipOnLinkedOneShotIsClearedButStaysEnabledInDb() = runTest {
        val x = clock.now.plusSeconds(60)
        val link = AlarmLink("u", "o", LinkMode.RING, null, true, null, null, null, null)
        repo.upsert(alarm(days = emptySet()).copy(link = link, soloSkippedOccurrenceAt = x))
        clock.now = x.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(1)
        repo.get("a")
        val row = dao.get("a")!!
        assertTrue(row.enabled)
        assertNull(row.soloSkippedOccurrenceAt)
    }

    @Test
    fun expiredOwnerSkipIsClearedInDbAndRowUntouchedOtherwise() = runTest {
        val x = clock.now.plusSeconds(60)
        val link = AlarmLink("u", "o", LinkMode.RING, x, true, null, null, null, null)
        repo.upsert(alarm(days = emptySet()).copy(link = link))
        clock.now = x.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(1)
        assertNull(repo.get("a")!!.link!!.ownerSkippedOccurrenceAt)
        val row = dao.get("a")!!
        assertNull(row.linkOwnerSkippedOccurrenceAt)
        assertTrue(row.enabled)
    }

    @Test
    fun expiredSkipIsMappedToNullAndClearedInDbViaGet() = runTest {
        repo.upsert(alarm(skip = clock.now.plusSeconds(60)))
        assertEquals(clock.now.plusSeconds(60), repo.get("a")!!.skippedOccurrenceAt)
        clock.now = clock.now.plusSeconds(61).plus(FIRE_GRACE.toJavaDuration())
        assertNull(repo.get("a")!!.skippedOccurrenceAt)
        assertNull(dao.get("a")!!.skippedOccurrenceAt)
    }

    @Test
    fun skipEqualToNowIsKeptWithinGrace() = runTest {
        repo.upsert(alarm(skip = clock.now))
        assertEquals(clock.now, repo.get("a")!!.skippedOccurrenceAt)
    }

    @Test
    fun skipJustAfterFireIsKeptByGetAndAlarmsAndInDb() = runTest {
        val t = clock.now
        repo.upsert(alarm(skip = t))
        clock.now = t.plusSeconds(1)
        assertEquals(t, repo.get("a")!!.skippedOccurrenceAt)
        repo.alarms.test {
            assertEquals(t, awaitItem().single().skippedOccurrenceAt)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(t.toEpochMilli(), dao.get("a")!!.skippedOccurrenceAt)
    }

    @Test
    fun skipPastGraceIsNullViaGetAndClearedInDb() = runTest {
        val t = clock.now
        repo.upsert(alarm(skip = t))
        clock.now = t.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(1)
        assertNull(repo.get("a")!!.skippedOccurrenceAt)
        assertNull(dao.get("a")!!.skippedOccurrenceAt)
    }

    @Test
    fun skipPastGraceIsNullViaAlarmsAndClearedInDb() = runTest {
        val t = clock.now
        repo.upsert(alarm(skip = t))
        clock.now = t.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(1)
        repo.alarms.test {
            assertNull(awaitItem().single().skippedOccurrenceAt)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(dao.get("a")!!.skippedOccurrenceAt)
    }

    @Test
    fun skipExactlyAtGraceBoundaryIsExpired() = runTest {
        val t = clock.now
        repo.upsert(alarm(skip = t))
        clock.now = t.plus(FIRE_GRACE.toJavaDuration())
        assertNull(repo.get("a")!!.skippedOccurrenceAt)
    }

    @Test
    fun expiryUsesClockAtEmissionTime() = runTest {
        repo.upsert(alarm(skip = clock.now.plusSeconds(60)))
        repo.alarms.test {
            assertEquals(clock.now.plusSeconds(60), awaitItem().single().skippedOccurrenceAt)
            clock.now = clock.now.plusSeconds(120).plus(FIRE_GRACE.toJavaDuration())
            repo.setEnabled("a", false)
            assertNull(awaitItem().single().skippedOccurrenceAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getReturnsRowWhenCleanupFails() = runTest {
        repo.upsert(alarm())
        val failing = AlarmRepositoryImpl(FailingCleanupDao(dao), clock)
        assertEquals(alarm(), failing.get("a"))
    }

    @Test
    fun getMapsExpiredSkipToNullWhenCleanupFails() = runTest {
        val t = clock.now
        repo.upsert(alarm(skip = t))
        clock.now = t.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(1)
        val failing = AlarmRepositoryImpl(FailingCleanupDao(dao), clock)
        assertNull(failing.get("a")!!.skippedOccurrenceAt)
        assertEquals(t.toEpochMilli(), dao.get("a")!!.skippedOccurrenceAt)
    }

    @Test
    fun getKeepsLiveSkipWhenCleanupFails() = runTest {
        val t = clock.now
        repo.upsert(alarm(skip = t))
        clock.now = t.plusSeconds(1)
        val failing = AlarmRepositoryImpl(FailingCleanupDao(dao), clock)
        assertEquals(t, failing.get("a")!!.skippedOccurrenceAt)
    }

    @Test
    fun alarmsStillEmitsWithMappingWhenCleanupFails() = runTest {
        val t = clock.now
        repo.upsert(alarm(id = "expired", skip = t))
        repo.upsert(alarm(id = "live", skip = t.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(60)))
        repo.upsert(alarm(id = "none"))
        clock.now = t.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(1)
        val failing = AlarmRepositoryImpl(FailingCleanupDao(dao), clock)
        failing.alarms.test {
            val byId = awaitItem().associateBy { it.id }
            assertEquals(3, byId.size)
            assertNull(byId.getValue("expired").skippedOccurrenceAt)
            assertEquals(t.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(60), byId.getValue("live").skippedOccurrenceAt)
            assertNull(byId.getValue("none").skippedOccurrenceAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun daysBitmaskRoundTripsAllSevenAndEmpty() {
        val all = DayOfWeek.entries.toSet()
        assertEquals(0b1111111, alarm(days = all).toEntity().days)
        assertEquals(all, alarm(days = all).toEntity().toAlarm().days)
        assertEquals(0, alarm(days = emptySet()).toEntity().days)
        assertEquals(emptySet<DayOfWeek>(), alarm(days = emptySet()).toEntity().toAlarm().days)
        assertEquals(1, alarm(days = setOf(DayOfWeek.MONDAY)).toEntity().days)
        assertEquals(64, alarm(days = setOf(DayOfWeek.SUNDAY)).toEntity().days)
    }

    private fun link(
        mode: LinkMode = LinkMode.NOTIFY,
        ownerSkip: Instant? = null,
        joinerEnabled: Boolean = false,
        pending: PendingBoth? = PendingBoth(CommandType.SKIP_NEXT, Instant.ofEpochMilli(1_700_000_500_000), "cmd-1", Instant.ofEpochMilli(1_700_000_400_000)),
    ) = AlarmLink(
        ownerUid = "owner",
        ownerAlarmId = "owner-alarm",
        mode = mode,
        ownerSkippedOccurrenceAt = ownerSkip,
        joinerEnabled = joinerEnabled,
        pendingBoth = pending,
        overrideLabel = "Mine",
        overrideRingtoneUri = "content://ring/2",
        overrideWallpaperPath = "/wall/2.jpg",
    )

    private fun linked(id: String = "l", hour: Int = 8, link: AlarmLink = link()) = alarm(id = id, hour = hour).copy(link = link)

    @Test
    fun ownAndLinkedAlarmsSplitAndAlarmsReturnsBoth() = runTest {
        repo.upsert(alarm(id = "own", hour = 6))
        repo.upsert(linked(id = "l", hour = 7))
        assertEquals(listOf("own"), repo.ownAlarms.first().map { it.id })
        assertEquals(listOf("l"), repo.linkedAlarms.first().map { it.id })
        assertEquals(listOf("own", "l"), repo.alarms.first().map { it.id })
    }

    @Test
    fun alarmWithLinkAndPendingBothRoundTrips() = runTest {
        val a = linked().copy(soloSkippedOccurrenceAt = Instant.ofEpochMilli(1_700_000_900_000))
        repo.upsert(a)
        assertEquals(a, repo.get("l"))
        val noPending = linked(id = "m", link = link(pending = null, ownerSkip = Instant.ofEpochMilli(1_700_001_000_000)))
        repo.upsert(noPending)
        assertEquals(noPending, repo.get("m"))
    }

    @Test
    fun setSoloSkipSetsAndClears() = runTest {
        repo.upsert(alarm())
        val at = Instant.ofEpochMilli(1_700_000_900_000)
        repo.setSoloSkip("a", at)
        assertEquals(at, repo.get("a")!!.soloSkippedOccurrenceAt)
        repo.setSoloSkip("a", null)
        assertNull(repo.get("a")!!.soloSkippedOccurrenceAt)
    }

    @Test
    fun lateRingRoundTripsAndSetLateRingSetsClearsAndIgnoresMissingRows() = runTest {
        val late = LateRing(Instant.ofEpochMilli(1_700_000_900_000), false)
        val a = alarm().copy(lateRing = late)
        repo.upsert(a)
        assertEquals(a, repo.get("a"))
        repo.setLateRing("a", LateRing(late.occurrenceAt, true))
        assertEquals(LateRing(late.occurrenceAt, true), repo.get("a")!!.lateRing)
        repo.setLateRing("a", null)
        assertNull(repo.get("a")!!.lateRing)
        repo.setLateRing("missing", late)
        assertNull(repo.get("missing"))
    }

    @Test
    fun aHalfSetLateRingReadsAsNull() = runTest {
        val at = 1_700_000_900_000
        dao.upsert(alarm(id = "x").toEntity().copy(lateRingOccurrenceAt = at, lateRingRing = null))
        dao.upsert(alarm(id = "y").toEntity().copy(lateRingOccurrenceAt = null, lateRingRing = true))
        assertNull(repo.get("x")!!.lateRing)
        assertNull(repo.get("y")!!.lateRing)
    }

    @Test
    fun expiredLateRingIsClearedInReadAndDbAndLiveOneKept() = runTest {
        val x = clock.now.plusSeconds(60)
        repo.upsert(alarm(id = "old").copy(lateRing = LateRing(x, false)))
        repo.upsert(alarm(id = "live").copy(lateRing = LateRing(x.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(120), false)))
        clock.now = x.plus(FIRE_GRACE.toJavaDuration()).plusSeconds(1)
        assertNull(repo.get("old")!!.lateRing)
        val row = dao.get("old")!!
        assertNull(row.lateRingOccurrenceAt)
        assertNull(row.lateRingRing)
        assertEquals(false, repo.get("live")!!.lateRing!!.ring)
        assertEquals(false, dao.get("live")!!.lateRingRing)
    }

    @Test
    fun upsertLinkedCopiesTheOwnersLateRingAndClearsIt() = runTest {
        repo.upsert(linked())
        val late = LateRing(Instant.ofEpochMilli(1_700_000_900_000), false)
        repo.upsertLinked(linked().copy(lateRing = late))
        assertEquals(late, repo.get("l")!!.lateRing)
        repo.upsertLinked(linked())
        assertNull(repo.get("l")!!.lateRing)
    }

    @Test
    fun setJoinerEnabledWritesOnlyTheLinkField() = runTest {
        val a = linked()
        repo.upsertLinked(a)
        repo.setJoinerEnabled(a.id, false)
        val stored = repo.get(a.id)!!
        assertFalse(stored.link!!.joinerEnabled)
        assertEquals(a.enabled, stored.enabled)
        assertEquals(a.copy(link = a.link!!.copy(joinerEnabled = false)), stored)
        repo.setJoinerEnabled(a.id, true)
        assertTrue(repo.get(a.id)!!.link!!.joinerEnabled)
    }

    @Test
    fun setJoinerEnabledIsNoOpForOwnOrMissingRows() = runTest {
        repo.upsert(alarm())
        val before = repo.get("a")
        repo.setJoinerEnabled("a", false)
        repo.setJoinerEnabled("missing", false)
        assertEquals(before, repo.get("a"))
        assertNull(repo.get("missing"))
    }

    @Test
    fun upsertLinkedInsertsWhenAbsent() = runTest {
        val a = linked()
        repo.upsertLinked(a)
        assertEquals(a, repo.get("l"))
    }

    @Test
    fun upsertLinkedUpdatesOnlyOwnerCopiedFields() = runTest {
        val stored = linked(link = link(mode = LinkMode.RING, joinerEnabled = true)).copy(
            skippedOccurrenceAt = Instant.ofEpochMilli(1_700_000_700_000),
            soloSkippedOccurrenceAt = Instant.ofEpochMilli(1_700_000_800_000),
        )
        repo.upsert(stored)
        val ownerSkip = Instant.ofEpochMilli(1_700_001_000_000)
        val incoming = Alarm(
            id = "l", label = "New", hour = 9, minute = 15, days = setOf(DayOfWeek.TUESDAY), enabled = false,
            skippedOccurrenceAt = null, ringtoneUri = null, wallpaperPath = null,
            link = AlarmLink("owner", "owner-alarm", LinkMode.NOTIFY, ownerSkip, false, null, null, null, null),
        )
        repo.upsertLinked(incoming)
        val expected = stored.copy(
            label = "New", hour = 9, minute = 15, days = setOf(DayOfWeek.TUESDAY), enabled = false,
            link = stored.link!!.copy(ownerSkippedOccurrenceAt = ownerSkip),
        )
        assertEquals(expected, repo.get("l"))
    }

    @Test
    fun upsertLinkedRefusesToConvertAnOwnAlarm() = runTest {
        val own = alarm()
        repo.upsert(own)
        val incoming = linked().copy(id = own.id)
        val e = runCatching { repo.upsertLinked(incoming) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertEquals(own, repo.get(own.id))
    }

    @Test
    fun upsertLinkedRejectsAnAlarmWithoutALink() = runTest {
        val e = runCatching { repo.upsertLinked(alarm()) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertNull(repo.get("a"))
    }

    @Test
    fun updateLinkTransformsAndReportsMissingOrUnlinked() = runTest {
        repo.upsert(linked())
        repo.upsert(alarm(id = "own"))
        assertTrue(repo.updateLink("l") { it.copy(joinerEnabled = true, pendingBoth = null) })
        assertEquals(link(joinerEnabled = true, pending = null), repo.get("l")!!.link)
        assertFalse(repo.updateLink("missing") { it })
        assertFalse(repo.updateLink("own") { it })
        assertNull(repo.get("own")!!.link)
    }

    @Test
    fun malformedLinkedRowsReadWithSafeDefaults() = runTest {
        fun row(id: String, mode: String?, joinerEnabled: Boolean?, type: String?, commandId: String?) = alarm(id = id)
            .toEntity().copy(
                linkOwnerUid = "owner",
                linkOwnerAlarmId = "owner-alarm",
                linkMode = mode,
                linkJoinerEnabled = joinerEnabled,
                linkPendingBothType = type,
                linkPendingBothOccurrenceAt = 1_700_000_000_000,
                linkPendingBothCommandId = commandId,
            )

        dao.upsert(row("unknownMode", "BOGUS", false, "BOGUS", "c1"))
        dao.upsert(row("nullFields", null, null, "SKIP_NEXT", null))

        val unknown = repo.get("unknownMode")!!.link!!
        assertEquals(LinkMode.RING, unknown.mode)
        assertFalse(unknown.joinerEnabled)
        assertNull(unknown.pendingBoth)

        val nulls = repo.get("nullFields")!!.link!!
        assertEquals(LinkMode.RING, nulls.mode)
        assertTrue(nulls.joinerEnabled)
        assertNull(nulls.pendingBoth)
    }
}
