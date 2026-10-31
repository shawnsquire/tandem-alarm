package me.shawnsquire.tandem.data

import androidx.room.Room
import app.cash.turbine.test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlin.time.toJavaDuration
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.FIRE_GRACE
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
    fun daysBitmaskRoundTripsAllSevenAndEmpty() {
        val all = DayOfWeek.entries.toSet()
        assertEquals(0b1111111, alarm(days = all).toEntity().days)
        assertEquals(all, alarm(days = all).toEntity().toAlarm().days)
        assertEquals(0, alarm(days = emptySet()).toEntity().days)
        assertEquals(emptySet<DayOfWeek>(), alarm(days = emptySet()).toEntity().toAlarm().days)
        assertEquals(1, alarm(days = setOf(DayOfWeek.MONDAY)).toEntity().days)
        assertEquals(64, alarm(days = setOf(DayOfWeek.SUNDAY)).toEntity().days)
    }
}
