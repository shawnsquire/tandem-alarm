package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeLinkedMediaCache
import me.shawnsquire.tandem.media.LinkedMediaCache
import me.shawnsquire.tandem.media.RingMedia
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RingScreenContentTest {
    private val occ = Instant.parse("2026-03-02T06:30:00Z")

    private fun alarm(id: String, label: String, wallpaper: String? = null) =
        Alarm(id, label, 6, 30, emptySet(), true, null, null, wallpaper)

    private fun joined(id: String, label: String, override: String? = null) = alarm(id, label).copy(
        link = AlarmLink("owner", "owner-$id", LinkMode.RING, null, true, null, override, null, null),
    )

    private fun ring(primary: String, vararg merged: String) =
        RingState(primary, "X", Instant.EPOCH, null, occ, merged.map { RingMember(it, occ) })

    /** A cache that throws, so a linked member falls back to its own label (override first). */
    private object FailingCache : LinkedMediaCache by FakeLinkedMediaCache() {
        override suspend fun resolve(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: String?): RingMedia =
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
        defaultWallpaper = "/default.jpg",
        partnerName = partnerName,
    )

    @Test fun mergedRingListsEveryLabelPrimaryFirstSkippingBlanks() = runTest {
        val alarms = mapOf(
            "a" to alarm("a", "Wake up", wallpaper = "/a.jpg"),
            "b" to alarm("b", " "),
            "c" to joined("c", "Their label", override = "Gym"),
        )
        val c = content(ring("a", "b", "c"), alarms)
        assertEquals(listOf("Wake up", "Gym"), c.labels)
        assertEquals(listOf("/a.jpg", "/default.jpg"), c.wallpaperPaths) // the primary's
    }

    @Test fun anUnreadableMemberShowsNothingForItself() = runTest {
        val alarms = mapOf("a" to alarm("a", "Wake up"), "b" to alarm("b", "Pills"))
        val c = content(ring("a", "b"), alarms, unreadable = setOf("a"))
        assertEquals(listOf("Pills"), c.labels)
        assertEquals(listOf("/default.jpg"), c.wallpaperPaths)
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
