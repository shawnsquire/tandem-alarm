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
import kotlinx.coroutines.flow.flowOf
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.media.MediaRef
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.FIRE_GRACE
import kotlin.time.toJavaDuration
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
    private var liveZone: ZoneId = ZoneOffset.UTC
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

        val awaited = mutableListOf<Pair<Household, Map<String, Any?>>>()
        var failAwait = false
        override suspend fun writeAndAwait(household: Household, data: Map<String, Any?>) {
            if (failAwait) error("offline")
            awaited += household to data
        }
    }

    private class Env(
        val alarms: FakeAlarmRepository,
        val ring: FakeRingStateStore,
        val household: MutableStateFlow<Household?>,
        val writer: RecordingWriter,
        val publisher: StatePublisherImpl,
    )

    private fun TestScope.env(
        paired: Boolean = true,
        writer: RecordingWriter = RecordingWriter(),
        fcmToken: kotlinx.coroutines.flow.Flow<String?> = MutableStateFlow("tok"),
        links: kotlinx.coroutines.flow.Flow<List<PublishedLink>> = flowOf(emptyList()),
        media: kotlinx.coroutines.flow.Flow<PublishedMedia?> = flowOf(null),
        defaultFrame: kotlinx.coroutines.flow.Flow<me.shawnsquire.tandem.schedule.WallpaperFrame?> = flowOf(null),
        initial: List<Alarm> = listOf(weekly),
        pairedHousehold: MutableStateFlow<Household?>? = null,
    ): Env {
        val clock = object : Clock() {
            override fun getZone(): ZoneId = liveZone
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
        }
        val alarms = FakeAlarmRepository(initial)
        val ring = FakeRingStateStore()
        val household = pairedHousehold ?: MutableStateFlow(if (paired) hh else null)
        val publisher = StatePublisherImpl(
            alarms, ring, household, MutableStateFlow("Pixel"), fcmToken, links, media, defaultFrame, writer, clock, backgroundScope,
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
    fun `a ringing linked row is published under the owner's alarm id`() = runTest {
        val e = env(initial = listOf(weekly, linkedRow))
        e.publisher.start()
        e.ring.set(RingState("l1", "r1", start, null))
        advance(2_000)
        val ringing = e.writer.writes.last().second["ringing"] as Map<*, *>
        assertEquals("oa1", ringing["alarmId"])
        assertEquals("r1", ringing["ringId"])
        // The heartbeat must not merge-write the row id back.
        assertTrue(e.publisher.publishNow())
        assertEquals("oa1", (e.writer.awaited.single().second["ringing"] as Map<*, *>)["alarmId"])
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
    fun `unpairing within the debounce window cancels the pending write and repairing writes again`() = runTest {
        val e = env()
        e.publisher.start()
        advance(2_000)
        assertEquals(1, e.writer.writes.size)
        e.alarms.setEnabled("a1", false)
        advance(500)
        e.household.value = null
        advance(5_000)
        assertEquals(1, e.writer.writes.size)
        e.household.value = hh
        advance(1_100)
        assertEquals(2, e.writer.writes.size)
        e.household.value = null
        advance(2_000)
        e.household.value = hh
        advance(1_100)
        assertEquals(3, e.writer.writes.size)
    }

    @Test
    fun `an upstream failure is retried and later writes still happen`() = runTest {
        var attempts = 0
        val token = kotlinx.coroutines.flow.flow<String?> {
            if (attempts++ == 0) throw java.io.IOException("datastore")
            emit("tok")
        }
        val e = env(fcmToken = token)
        e.publisher.start()
        advance(500)
        assertTrue(e.writer.writes.isEmpty())
        advance(3_000)
        assertEquals(1, e.writer.writes.size)
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

    @Test
    fun `publishNow writes again even when nothing changed since the pipeline write`() = runTest {
        val e = env()
        e.publisher.start()
        advance(2_000)
        assertEquals(1, e.writer.writes.size)
        assertTrue(e.publisher.publishNow())
        assertTrue(e.publisher.publishNow())
        assertEquals(2, e.writer.awaited.size)
        val (h, data) = e.writer.awaited.last()
        assertEquals(hh, h)
        assertTrue(data["updatedAt"] is com.google.firebase.firestore.FieldValue)
        assertEquals("tok", data["fcmToken"])
        // Pipeline write count is untouched: the heartbeat is its own single write.
        assertEquals(1, e.writer.writes.size)
    }

    @Test
    fun `publishNow returns false and does not write while unpaired`() = runTest {
        val e = env(paired = false)
        assertFalse(e.publisher.publishNow())
        assertTrue(e.writer.awaited.isEmpty())
        assertTrue(e.writer.writes.isEmpty())
    }

    @Test
    fun `publishNow returns false when the write fails`() = runTest {
        val e = env()
        e.writer.failAwait = true
        assertFalse(e.publisher.publishNow())
    }

    @Test
    fun `publishNow recomputes nextOccurrenceAt from the live zone and instant`() = runTest {
        val e = env()
        assertTrue(e.publisher.publishNow())
        assertEquals(
            Timestamp(Instant.parse("2026-03-02T06:30:00Z").epochSecond, 0),
            e.writer.awaited.last().second.firstAlarm()["nextOccurrenceAt"],
        )
        liveZone = ZoneId.of("America/New_York") // 06:30 EST = 11:30Z
        assertTrue(e.publisher.publishNow())
        assertEquals(
            Timestamp(Instant.parse("2026-03-02T11:30:00Z").epochSecond, 0),
            e.writer.awaited.last().second.firstAlarm()["nextOccurrenceAt"],
        )
    }

    @Test
    fun `runSyncOnce with no state change still heartbeats with updatedAt each run`() = runTest {
        val e = env()
        e.publisher.start()
        advance(2_000)
        var pulls = 0
        repeat(2) { runSyncOnce(pull = { pulls++; true }, publishNow = e.publisher::publishNow) }
        assertEquals(2, pulls)
        assertEquals(2, e.writer.awaited.size)
        assertTrue(e.writer.awaited.all { it.second.containsKey("updatedAt") })
    }

    @Test
    fun `runSyncOnce heartbeats even when the pull throws and survives a hung heartbeat`() = runTest {
        var beats = 0
        runSyncOnce(pull = { error("net") }, publishNow = { beats++; true })
        assertEquals(1, beats)
        runSyncOnce(pull = { true }, publishNow = { kotlinx.coroutines.awaitCancellation() })
        assertEquals(10_000, testScheduler.currentTime)
    }

    private val linkedRow = Alarm(
        "l1", "Linked", 7, 0, setOf(DayOfWeek.MONDAY), true, null, null, null,
        link = me.shawnsquire.tandem.schedule.AlarmLink("them", "oa1", me.shawnsquire.tandem.schedule.LinkMode.RING, null, true, null, null, null, null),
    )

    @Test
    fun `linked rows are excluded from published alarms in both paths`() = runTest {
        val e = env(initial = listOf(weekly, linkedRow))
        e.publisher.start()
        advance(2_000)
        assertEquals(listOf("a1"), (e.writer.writes.single().second["alarms"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertTrue(e.publisher.publishNow())
        assertEquals(listOf("a1"), (e.writer.awaited.single().second["alarms"] as List<*>).map { (it as Map<*, *>)["id"] })
    }

    @Test
    fun `links are published by the pipeline and the heartbeat, empty list when none`() = runTest {
        val link = PublishedLink("oa1", me.shawnsquire.tandem.schedule.LinkMode.RING)
        val e = env(links = flowOf(listOf(link)))
        e.publisher.start()
        advance(2_000)
        val expected = listOf(mapOf("ownerAlarmId" to "oa1", "mode" to "RING"))
        assertEquals(expected, e.writer.writes.single().second["links"])
        assertTrue(e.publisher.publishNow())
        assertEquals(expected, e.writer.awaited.single().second["links"])
        val none = env()
        none.publisher.start()
        advance(2_000)
        assertEquals(emptyList<Any>(), none.writer.writes.single().second["links"])
    }

    @Test
    fun `setLateRing on an own alarm republishes it`() = runTest {
        val e = env()
        e.publisher.start()
        advance(2_000)
        assertFalse(e.writer.writes.last().second.firstAlarm().containsKey("lateRing"))
        val x = start.plusSeconds(3600)
        e.alarms.setLateRing("a1", me.shawnsquire.tandem.schedule.LateRing(x, false))
        advance(2_000)
        assertEquals(2, e.writer.writes.size)
        val l = e.writer.writes.last().second.firstAlarm()["lateRing"] as Map<*, *>
        assertEquals(false, l["ring"])
        assertEquals(com.google.firebase.Timestamp(x.epochSecond, 0), l["occurrenceAt"])
        e.alarms.setLateRing("a1", me.shawnsquire.tandem.schedule.LateRing(x, true))
        advance(2_000)
        assertEquals(true, (e.writer.writes.last().second.firstAlarm()["lateRing"] as Map<*, *>)["ring"])
    }

    @Test
    fun `boundary includes the late ring expiry`() {
        val x = Instant.parse("2026-03-02T06:00:00Z")
        val a = weekly.copy(days = emptySet(), enabled = false, lateRing = me.shawnsquire.tandem.schedule.LateRing(x, true))
        val after = x.plusSeconds(1)
        assertEquals(x.plus(FIRE_GRACE.toJavaDuration()), nextPublishBoundary(listOf(a), after, zone))
    }

    private fun media(hash: String) = PublishedMedia(MediaRef(hash, 10), emptyMap())

    @Test
    fun `a frame or text position edit alone republishes the alarm`() = runTest {
        val e = env()
        e.publisher.start()
        advance(2_000)
        assertEquals(1, e.writer.writes.size)
        e.alarms.upsert(weekly.copy(wallpaperFrame = me.shawnsquire.tandem.schedule.WallpaperFrame(2f, 0.2f, 0.8f)))
        advance(2_000)
        assertEquals(2, e.writer.writes.size)
        assertEquals(mapOf("zoom" to 2.0, "x" to 0.2f.toDouble(), "y" to 0.8f.toDouble()), e.writer.writes.last().second.firstAlarm()["wallpaperFrame"])
        e.alarms.upsert(weekly.copy(textPosition = me.shawnsquire.tandem.schedule.TextPosition.TOP))
        advance(2_000)
        assertEquals(3, e.writer.writes.size)
        assertEquals("TOP", e.writer.writes.last().second.firstAlarm()["textPosition"])
    }

    @Test
    fun `a default frame change alone republishes beside the default wallpaper and rides the heartbeat`() = runTest {
        val frame = MutableStateFlow<me.shawnsquire.tandem.schedule.WallpaperFrame?>(null)
        val e = env(media = flowOf(media("m1")), defaultFrame = frame)
        e.publisher.start()
        advance(2_000)
        assertNull(e.writer.writes.single().second["defaultWallpaperFrame"])
        frame.value = me.shawnsquire.tandem.schedule.WallpaperFrame(3f, 0.1f, 0.9f)
        advance(2_000)
        assertEquals(2, e.writer.writes.size)
        val written = e.writer.writes.last().second
        assertEquals("m1", (written["defaultWallpaper"] as Map<*, *>)["sha"])
        assertEquals(mapOf("zoom" to 3.0, "x" to 0.1f.toDouble(), "y" to 0.9f.toDouble()), written["defaultWallpaperFrame"])
        assertTrue(e.publisher.publishNow())
        assertEquals(0.1f.toDouble(), (e.writer.awaited.single().second["defaultWallpaperFrame"] as Map<*, *>)["x"])
    }

    @Test
    fun `media is published by the pipeline and the heartbeat`() = runTest {
        val e = env(media = flowOf(media("m1")))
        e.publisher.start()
        advance(2_000)
        assertEquals("m1", (e.writer.writes.single().second["defaultWallpaper"] as Map<*, *>)["sha"])
        assertTrue(e.publisher.publishNow())
        assertEquals("m1", (e.writer.awaited.single().second["defaultWallpaper"] as Map<*, *>)["sha"])
    }

    @Test
    fun `a transient old household media value right after pairing is never written`() = runTest {
        val household = MutableStateFlow<Household?>(null)
        val mediaFlow = MutableStateFlow<PublishedMedia?>(media("old"))
        val e = env(media = mediaFlow, pairedHousehold = household)
        e.publisher.start()
        advance(3_000)
        household.value = hh
        advance(200)
        mediaFlow.value = PublishedMedia(null, emptyMap())
        advance(3_000)
        val w = e.writer.writes.single().second
        assertNull(w["defaultWallpaper"])
        assertFalse(w.toString().contains("old"))
    }

    @Test
    fun `heartbeat does not hang when links and media never emit`() = runTest {
        val e = env(links = kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }, media = kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() })
        assertTrue(e.publisher.publishNow())
        val data = e.writer.awaited.single().second
        assertFalse(data.containsKey("links"))
    }

    @Test
    fun `boundary includes the solo skip and its expiry`() {
        val now = Instant.parse("2026-03-02T05:00:00Z")
        val x = Instant.parse("2026-03-02T06:30:00Z")
        val oneShot = weekly.copy(days = emptySet(), soloSkippedOccurrenceAt = x)
        // nextOccurrence of a solo-skipped one-shot is null, so the boundary is X itself.
        assertEquals(x, nextPublishBoundary(listOf(oneShot), now, zone))
        val after = x.plusSeconds(1)
        assertEquals(x.plus(FIRE_GRACE.toJavaDuration()), nextPublishBoundary(listOf(oneShot), after, zone))
    }

    @Test
    fun `a solo skipped one-shot is republished as disabled once its skip expires`() = runTest {
        val x = start.plusSeconds(3600)
        val oneShot = weekly.copy(days = emptySet(), hour = 6, minute = 0, soloSkippedOccurrenceAt = x)
        val e = env(initial = listOf(oneShot))
        e.publisher.start()
        advance(2_000)
        assertEquals(true, e.writer.writes.last().second.firstAlarm()["enabled"])
        advance(3600_000 + FIRE_GRACE.inWholeMilliseconds + 5_000)
        assertEquals(false, e.writer.writes.last().second.firstAlarm()["enabled"])
    }

    @Test
    fun `a heartbeat never writes the transient old household media`() = runTest {
        val real = kotlinx.coroutines.flow.flow<PublishedMedia?> {
            emit(media("old"))
            kotlinx.coroutines.delay(300)
            emit(PublishedMedia(null, emptyMap()))
            kotlinx.coroutines.awaitCancellation()
        }
        val e = env(media = real)
        assertTrue(e.publisher.publishNow())
        val w = e.writer.awaited.single().second
        assertNull(w["defaultWallpaper"])
        assertFalse(w.toString().contains("old"))
    }

    @Test
    fun `a heartbeat with steady media finishes within the settle window`() = runTest {
        val e = env(media = MutableStateFlow(media("m1")))
        assertTrue(e.publisher.publishNow())
        assertTrue(testScheduler.currentTime <= 1_000)
        assertEquals("m1", (e.writer.awaited.single().second["defaultWallpaper"] as Map<*, *>)["sha"])
    }
}
