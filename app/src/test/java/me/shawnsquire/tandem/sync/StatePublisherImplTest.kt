package me.shawnsquire.tandem.sync

import com.google.firebase.Timestamp
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatePublisherImplTest {
    private val zone = ZoneOffset.UTC
    private val start = Instant.parse("2026-03-02T05:00:00Z") // Monday
    private val hh = Household("h1", "me", "them")

    private val weekly = Alarm(
        "a1", "Work", 6, 30, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), true, null,
        "content://secret/ringtone", "/data/secret/wall.jpg",
    )

    private class RecordingWriter(var failFirst: Boolean = false) : DeviceDocWriter {
        val writes = mutableListOf<Pair<Household, Map<String, Any?>>>()
        override fun write(household: Household, data: Map<String, Any?>) {
            writes += household to data
            if (failFirst) {
                failFirst = false
                error("boom")
            }
        }
    }

    private class Env(
        val alarms: FakeAlarmRepository,
        val ring: FakeRingStateStore,
        val household: MutableStateFlow<Household?>,
        val writer: RecordingWriter,
        val publisher: StatePublisherImpl,
    )

    private fun TestScope.env(paired: Boolean = true, writer: RecordingWriter = RecordingWriter()): Env {
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
        }
        val alarms = FakeAlarmRepository(listOf(weekly))
        val ring = FakeRingStateStore()
        val household = MutableStateFlow(if (paired) hh else null)
        val publisher = StatePublisherImpl(
            alarms, ring, household, MutableStateFlow("Pixel"), MutableStateFlow("tok"), writer, clock, backgroundScope,
        )
        return Env(alarms, ring, household, writer, publisher)
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun Map<String, Any?>.firstAlarm() = (this["alarms"] as List<*>).first() as Map<*, *>

    @Test
    fun `nothing is written while unpaired`() = runTest {
        val e = env(paired = false)
        e.publisher.start()
        advance(10_000)
        e.alarms.setEnabled("a1", false)
        advance(10_000)
        assertTrue(e.writer.writes.isEmpty())
    }

    @Test
    fun `writes once paired with the household`() = runTest {
        val e = env(paired = false)
        e.publisher.start()
        advance(5_000)
        e.household.value = hh
        advance(500)
        assertTrue(e.writer.writes.isEmpty())
        advance(600)
        val (h, data) = e.writer.writes.single()
        assertEquals(hh, h)
        assertEquals("Pixel", data["displayName"])
        assertEquals("tok", data["fcmToken"])
        assertEquals("a1", data.firstAlarm()["id"])
    }

    @Test
    fun `rapid alarm changes coalesce into one write`() = runTest {
        val e = env()
        e.publisher.start()
        advance(2_000)
        e.writer.writes.clear()
        e.alarms.setEnabled("a1", false)
        advance(300)
        e.alarms.setEnabled("a1", true)
        advance(300)
        e.alarms.setEnabled("a1", false)
        advance(2_000)
        val (_, data) = e.writer.writes.single()
        assertEquals(false, data.firstAlarm()["enabled"])
    }

    @Test
    fun `republishes with a new nextOccurrenceAt after an occurrence passes`() = runTest {
        val e = env()
        e.publisher.start()
        advance(1_100)
        val first = e.writer.writes.single().second.firstAlarm()["nextOccurrenceAt"]
        assertEquals(Timestamp(Instant.parse("2026-03-02T06:30:00Z").epochSecond, 0), first)
        // 05:00 -> 06:31 is past the 06:30 occurrence plus the 1 s margin and the debounce.
        advance(91 * 60_000L)
        assertEquals(2, e.writer.writes.size)
        val second = e.writer.writes.last().second.firstAlarm()["nextOccurrenceAt"]
        assertEquals(Timestamp(Instant.parse("2026-03-03T06:30:00Z").epochSecond, 0), second)
    }

    @Test
    fun `ring state change triggers a write`() = runTest {
        val e = env()
        e.publisher.start()
        advance(2_000)
        assertNull(e.writer.writes.single().second["ringing"])
        e.ring.set(RingState("a1", "r1", start, null))
        advance(1_100)
        assertEquals(2, e.writer.writes.size)
        assertEquals("r1", (e.writer.writes.last().second["ringing"] as Map<*, *>)["ringId"])
    }

    @Test
    fun `written maps never contain ringtone or wallpaper`() = runTest {
        val e = env()
        e.publisher.start()
        advance(2_000)
        val s = e.writer.writes.single().second.toString()
        assertFalse(s.contains("ringtone", ignoreCase = true))
        assertFalse(s.contains("wallpaper", ignoreCase = true))
        assertFalse(s.contains("secret"))
    }

    @Test
    fun `starting twice runs one collector`() = runTest {
        val e = env()
        e.publisher.start()
        e.publisher.start()
        advance(2_000)
        assertEquals(1, e.writer.writes.size)
    }

    @Test
    fun `a throwing writer does not stop later writes`() = runTest {
        val e = env(writer = RecordingWriter(failFirst = true))
        e.publisher.start()
        advance(2_000)
        assertEquals(1, e.writer.writes.size)
        e.alarms.setEnabled("a1", false)
        advance(2_000)
        assertEquals(2, e.writer.writes.size)
    }

    @Test
    fun `boundary is the earliest next occurrence or future skip`() {
        val now = Instant.parse("2026-03-02T05:00:00Z")
        val early = weekly.copy(id = "a2", hour = 5, minute = 30)
        assertEquals(
            Instant.parse("2026-03-02T05:30:00Z"),
            nextPublishBoundary(listOf(weekly, early), now, zone),
        )
        val skipped = weekly.copy(skippedOccurrenceAt = Instant.parse("2026-03-02T06:30:00Z"))
        assertEquals(Instant.parse("2026-03-02T06:30:00Z"), nextPublishBoundary(listOf(skipped), now, zone))
        val pastSkip = weekly.copy(enabled = false, skippedOccurrenceAt = now.minusSeconds(5))
        assertNull(nextPublishBoundary(listOf(pastSkip), now, zone))
        assertNull(nextPublishBoundary(emptyList(), now, zone))
    }
}
