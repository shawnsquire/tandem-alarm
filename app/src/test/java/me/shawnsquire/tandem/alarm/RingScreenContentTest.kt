package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeLinkedMediaCache
import me.shawnsquire.tandem.media.LinkedMediaCache
import me.shawnsquire.tandem.media.RingMedia
import me.shawnsquire.tandem.media.RingWallpaper
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.WallpaperFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RingScreenContentTest {
    private val occ = Instant.parse("2026-03-02T06:30:00Z")

    private val aFrame = WallpaperFrame(2f, 0.3f, 0.6f)
    private val defaultFrame = WallpaperFrame(3f, 0.4f, 0.4f)

    private fun alarm(id: String, label: String, wallpaper: String? = null) =
        Alarm(id, label, 6, 30, emptySet(), true, null, null, wallpaper, wallpaperFrame = wallpaper?.let { aFrame })

    private fun joined(id: String, label: String, override: String? = null) = alarm(id, label).copy(
        link = AlarmLink("owner", "owner-$id", LinkMode.RING, null, true, null, override, null, null),
    )

    private fun ring(primary: String, vararg merged: String) =
        RingState(primary, "X", Instant.EPOCH, null, occ, merged.map { RingMember(it, occ) })

    /** A cache that throws, so a linked member falls back to its own label (override first). */
    private object FailingCache : LinkedMediaCache by FakeLinkedMediaCache() {
        override suspend fun resolve(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: RingWallpaper?): RingMedia =
            throw IllegalStateException("cache")
    }

    private suspend fun content(
        ring: RingState,
        alarms: Map<String, Alarm>,
        partnerName: String? = "Shawn",
        unreadable: Set<String> = emptySet(),
        cache: LinkedMediaCache = FailingCache,
    ) = ringScreenContent(
        ring,
        readAlarm = { id -> if (id in unreadable) throw IllegalStateException("db") else alarms[id] },
        cache = cache,
        defaultWallpaper = RingWallpaper("/default.jpg", defaultFrame),
        partnerName = partnerName,
        // In place: a resolve on real IO would race runTest's virtual clock to the media timeout.
        dispatcher = Dispatchers.Unconfined,
    )

    @Test fun mergedRingListsEveryLabelPrimaryFirstSkippingBlanks() = runTest {
        val alarms = mapOf(
            "a" to alarm("a", "Wake up", wallpaper = "/a.jpg"),
            "b" to alarm("b", " "),
            "c" to joined("c", "Their label", override = "Gym"),
        )
        val c = content(ring("a", "b", "c"), alarms)
        assertEquals(listOf("Wake up", "Gym"), c.labels)
        assertEquals(listOf(RingWallpaper("/a.jpg", aFrame), RingWallpaper("/default.jpg", defaultFrame)), c.wallpapers) // the primary's
    }

    @Test fun anUnreadableMemberShowsNothingForItself() = runTest {
        val alarms = mapOf("a" to alarm("a", "Wake up"), "b" to alarm("b", "Pills"))
        val c = content(ring("a", "b"), alarms, unreadable = setOf("a"))
        assertEquals(listOf("Pills"), c.labels)
        assertEquals(listOf(RingWallpaper("/default.jpg", defaultFrame)), c.wallpapers)
        assertEquals(TextPosition.MIDDLE, c.textPosition) // the primary can't be read
    }

    @Test fun textPositionIsThePrimarysEffectiveOne() = runTest {
        val alarms = mapOf(
            "a" to alarm("a", "Wake up").copy(textPosition = TextPosition.TOP),
            "b" to alarm("b", "Pills").copy(textPosition = TextPosition.BOTTOM),
        )
        assertEquals(TextPosition.TOP, content(ring("a", "b"), alarms).textPosition)
        assertEquals(TextPosition.BOTTOM, content(ring("b", "a"), alarms).textPosition)
        val overridden = joined("a", "Theirs").copy(textPosition = TextPosition.TOP).let {
            it.copy(link = it.link!!.copy(overrideTextPosition = TextPosition.BOTTOM))
        }
        assertEquals(TextPosition.BOTTOM, content(ring("a"), mapOf("a" to overridden)).textPosition)
    }

    @Test fun aLinkedMemberUsesTheCacheLabelWhenItResolves() = runTest {
        val alarms = mapOf("a" to joined("a", "Theirs", override = "Mine"))
        assertEquals(listOf("Mine"), content(ring("a"), alarms, cache = FakeLinkedMediaCache()).labels)
    }

    @Test fun withPartnerOnlyForAJoinedMemberAndAKnownName() = runTest {
        val own = mapOf("a" to alarm("a", "Wake up"), "b" to alarm("b", "Pills"))
        val withJoined = own + ("b" to joined("b", "Pills"))
        assertNull(content(ring("a", "b"), own).withPartner)
        assertEquals("Shawn", content(ring("a", "b"), withJoined).withPartner)
        assertNull(content(ring("a", "b"), withJoined, partnerName = null).withPartner)
        assertNull(content(ring("a", "b"), withJoined, partnerName = " ").withPartner)
    }
}
