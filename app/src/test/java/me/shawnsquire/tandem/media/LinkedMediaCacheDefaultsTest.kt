package me.shawnsquire.tandem.media

import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeLinkedMediaCache
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.WallpaperFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class LinkedMediaCacheDefaultsTest {
    private val ownFrame = WallpaperFrame(2f, 0.3f, 0.6f)
    private val overrideFrame = WallpaperFrame(1.5f, 0.7f, 0.2f)
    private val joinerFrame = WallpaperFrame(3f, 0.4f, 0.4f)
    private val joiner = RingWallpaper("/w/joiner.jpg", joinerFrame)

    private fun alarm(label: String = "Wake", ringtone: String? = "content://own") =
        Alarm("a", label, 6, 30, setOf(DayOfWeek.MONDAY), true, null, ringtone, null)

    @Test fun defaultResolveUsesTheAlarmThenJoinerDefaults() = runTest {
        val media = DefaultLinkedMediaCache.resolve(alarm(), "content://joiner", joiner)
        assertEquals(RingMedia("Wake", listOf("content://own", "content://joiner"), listOf(joiner)), media)
    }

    @Test fun defaultResolveSkipsAbsentRingtonesAndBlankWallpapers() = runTest {
        val media = DefaultLinkedMediaCache.resolve(alarm(ringtone = null), null, RingWallpaper(" ", null))
        assertEquals(RingMedia("Wake", emptyList(), emptyList()), media)
    }

    private fun linked(label: String?, ring: String?, wall: String?) = alarm().copy(
        wallpaperPath = "/w/own.jpg",
        wallpaperFrame = ownFrame,
        link = AlarmLink("owner", "oa", LinkMode.RING, null, true, null, label, ring, wall, overrideWallpaperFrame = overrideFrame),
    )

    @Test fun linkedResolveKeepsTheJoinersOverrideFirst() = runTest {
        val media = DefaultLinkedMediaCache.resolve(linked("Mine", "content://override", "/w/override.jpg"), "content://joiner", joiner)
        assertEquals("Mine", media.label)
        assertEquals("content://override", media.soundCandidates.first())
        assertEquals(listOf("content://override", "content://own"), media.soundCandidates.take(2))
        assertEquals("content://joiner", media.soundCandidates.last())
        assertEquals(
            listOf(RingWallpaper("/w/override.jpg", overrideFrame), RingWallpaper("/w/own.jpg", ownFrame), joiner),
            media.wallpapers,
        )
    }

    @Test fun linkedRowOrderIsOverrideAlarmSystemJoiner() {
        val media = DefaultLinkedMediaCache.resolveFromRow(linked("Mine", "content://override", null), "content://joiner", null, "content://system")
        assertEquals(listOf("content://override", "content://own", "content://system", "content://joiner"), media.soundCandidates)
    }

    @Test fun linkedRowWithoutOverridesFallsToTheAlarm() {
        val media = DefaultLinkedMediaCache.resolveFromRow(linked(null, null, null), null, null, "content://system")
        assertEquals(RingMedia("Wake", listOf("content://own", "content://system"), listOf(RingWallpaper("/w/own.jpg", ownFrame))), media)
    }

    @Test fun linkedRowDropsDuplicatesAndBlanksAndABlankLabelFallsToTheAlarm() {
        val media = DefaultLinkedMediaCache.resolveFromRow(linked(" ", "content://own", " "), "content://system", null, "content://system")
        assertEquals(listOf("content://own", "content://system"), media.soundCandidates)
        assertEquals("Wake", media.label)
        assertEquals(listOf(RingWallpaper("/w/own.jpg", ownFrame)), media.wallpapers)
    }

    @Test fun defaultOtherMembersAreInert() {
        DefaultLinkedMediaCache.update("u", emptyMap(), null)
        assertNull(DefaultLinkedMediaCache.pathFor("sha"))
        assertTrue(DefaultLinkedMediaCache.inUse().isEmpty())
    }

    @Test fun fakeResolveMatchesTheDefault() = runTest {
        assertEquals(
            DefaultLinkedMediaCache.resolve(alarm(), "r", RingWallpaper("w", null)),
            FakeLinkedMediaCache().resolve(alarm(), "r", RingWallpaper("w", null)),
        )
    }

    @Test fun switchableStartsOnTheDefault() = runTest {
        val cache = SwitchableLinkedMediaCache()
        assertSame(DefaultLinkedMediaCache, cache.delegate)
        assertEquals(DefaultLinkedMediaCache.resolve(alarm(), "r", null), cache.resolve(alarm(), "r", null))
        assertNull(cache.pathFor("x"))
    }

    @Test fun switchableForwardsEveryMemberToTheDelegate() = runTest {
        val cache = SwitchableLinkedMediaCache()
        val fake = FakeLinkedMediaCache().apply {
            paths["sha"] = "/linked/sha"
            inUseHashes += "sha"
        }
        cache.delegate = fake
        val media = mapOf("o1" to AlarmMedia(null, MediaRef("sha", 3)))
        cache.update("owner", media, null)
        assertEquals(listOf(FakeLinkedMediaCache.Update("owner", media, null)), fake.updates)
        assertEquals("/linked/sha", cache.pathFor("sha"))
        assertEquals(setOf("sha"), cache.inUse())
        assertEquals(fake.resolve(alarm(), "r", RingWallpaper("w", null)), cache.resolve(alarm(), "r", RingWallpaper("w", null)))
    }
}
