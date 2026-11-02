package me.shawnsquire.tandem.fakes

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.alarm.MirrorKind
import me.shawnsquire.tandem.media.RingMedia
import me.shawnsquire.tandem.media.RingWallpaper
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.sync.PairingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class FakesTest {
    @Test
    fun alarmRepositoryEmitsSeededAlarms() = runTest {
        FakeAlarmRepository().alarms.test {
            assertEquals(listOf("Work", "Weekend"), awaitItem().map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setSkipUpdatesAndReEmits() = runTest {
        val repo = FakeAlarmRepository()
        val at = Instant.parse("2030-01-01T06:30:00Z")
        repo.alarms.test {
            awaitItem()
            repo.setSkip("alarm-work", at)
            assertEquals(at, awaitItem().first { it.id == "alarm-work" }.skippedOccurrenceAt)
            repo.setSkip("alarm-work", null)
            assertNull(awaitItem().first { it.id == "alarm-work" }.skippedOccurrenceAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setEnabledAndDelete() = runTest {
        val repo = FakeAlarmRepository()
        repo.setEnabled("alarm-work", false)
        assertEquals(false, repo.get("alarm-work")?.enabled)
        repo.delete("alarm-work")
        assertNull(repo.get("alarm-work"))
        assertEquals(1, repo.get("alarm-weekend")?.let { 1 })
    }

    @Test
    fun pairingJoinThenUnpair() = runTest {
        val repo = FakePairingRepository()
        repo.status.test {
            assertEquals(PairingStatus.Unpaired, awaitItem())
            repo.join(repo.createCode())
            assertEquals(true, awaitItem() is PairingStatus.Paired)
            repo.unpair()
            assertEquals(PairingStatus.Unpaired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun linkedMediaCacheResolveReproducesTodaysRing() = runTest {
        val base = FakeAlarmRepository().get("alarm-work")!!.copy(
            label = "Owner", ringtoneUri = "content://own", wallpaperPath = "/own.jpg", link = null,
        )
        val cache = FakeLinkedMediaCache()
        assertEquals(
            RingMedia("Owner", listOf("content://own", "content://def"), listOf(RingWallpaper("/own.jpg", null), RingWallpaper("/def.jpg", null))),
            cache.resolve(base, "content://def", RingWallpaper("/def.jpg", null)),
        )
        val linked = base.copy(
            ringtoneUri = null, wallpaperPath = null,
            link = AlarmLink("u", "o", LinkMode.RING, null, true, null, "Mine", null, null),
        )
        assertEquals(RingMedia("Mine", listOf("content://def"), listOf(RingWallpaper("/def.jpg", null))), cache.resolve(linked, "content://def", RingWallpaper("/def.jpg", null)))
    }

    @Test
    fun ringMirrorRecordsOnLocalAndFlushes() = runTest {
        val mirror = FakeRingMirror()
        val at = Instant.parse("2030-01-01T06:30:00Z")
        mirror.onLocal(MirrorKind.SNOOZE, emptyList(), at)
        mirror.flushOutbox()
        assertEquals(listOf(FakeRingMirror.Local(MirrorKind.SNOOZE, emptyList(), at)), mirror.locals)
        assertEquals(1, mirror.flushes)
    }

    @Test
    fun preFireCheckRecordsLinkedOwnerAlarmId() = runTest {
        val check = FakePreFireCheck()
        check.pullAndApply(3.seconds)
        check.pullAndApply(3.seconds, "owner-1")
        assertEquals(listOf(null, "owner-1"), check.linkedOwnerAlarmIds)
    }

    @Test
    fun commandSenderReportsEachCreatedId() {
        val sender = FakeCommandSender()
        val ids = mutableListOf<String>()
        sender.send(CommandType.SKIP_NEXT, "a", null, null, onCreated = { ids += it })
        sender.send(CommandType.STOP_RINGING, "a", null, "r", onCreated = { ids += it })
        assertEquals(2, ids.distinct().size)
    }
}
