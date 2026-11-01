package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RingingScreenModelTest {
    private fun ring(id: String, snoozed: Boolean = false) = RingState(
        "a", id, Instant.EPOCH, if (snoozed) Instant.EPOCH.plusSeconds(60) else null,
    )

    @Test fun initialNullDoesNotFinish() {
        assertFalse(RingingScreenModel().onState(null))
    }

    @Test fun nullThenRingThenNullFinishes() {
        val m = RingingScreenModel()
        assertFalse(m.onState(null))
        assertFalse(m.onState(ring("X")))
        assertEquals("X", m.ringId)
        assertFalse(m.onState(null))
        assertTrue(m.endPending)
    }

    @Test fun differentRingIdIsAdopted() {
        val m = RingingScreenModel()
        m.onState(ring("X"))
        assertFalse(m.onState(ring("X")))
        assertFalse(m.onState(ring("Y")))
        assertEquals("Y", m.ringId)
        assertFalse(m.onState(null))
        assertTrue(m.endPending)
    }

    @Test fun adoptedRingSnoozeFinishes() {
        val m = RingingScreenModel()
        m.onState(ring("X"))
        m.onState(ring("Y"))
        assertTrue(m.onState(ring("Y", snoozed = true)))
    }

    @Test fun differentRingArrivingSnoozedFinishes() {
        val m = RingingScreenModel()
        m.onState(ring("X"))
        assertTrue(m.onState(ring("Y", snoozed = true)))
    }

    @Test fun awaitReportsAdoptedRings() = runTest {
        val flow = MutableStateFlow<RingState?>(null)
        val seen = mutableListOf<String>()
        var done = false
        launch { awaitRingEnd(flow, graceMs = 10_000, onRing = { seen += it.ringId }); done = true }
        flow.value = ring("X"); runCurrent()
        flow.value = ring("Y"); runCurrent()
        assertEquals(listOf("X", "Y"), seen)
        assertFalse(done)
        flow.value = null; runCurrent()
        assertFalse(done)
        advanceTimeBy(END_DEBOUNCE_MS + 1); runCurrent()
        assertTrue(done)
    }

    @Test fun supersedeWithinDebounceAdoptsAndDoesNotFinish() = runTest {
        val flow = MutableStateFlow<RingState?>(null)
        val seen = mutableListOf<String>()
        var done = false
        launch { awaitRingEnd(flow, onRing = { seen += it.ringId }); done = true }
        flow.value = ring("X"); runCurrent()
        flow.value = null; runCurrent()
        advanceTimeBy(2_000)
        flow.value = ring("Y"); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf("X", "Y"), seen)
        assertFalse(done)
        flow.value = null; runCurrent()
        advanceTimeBy(END_DEBOUNCE_MS + 1); runCurrent()
        assertTrue(done)
    }

    @Test fun nullWithNothingElseFinishesAtDebounce() = runTest {
        val flow = MutableStateFlow<RingState?>(null)
        var done = false
        launch { awaitRingEnd(flow); done = true }
        flow.value = ring("X"); runCurrent()
        flow.value = null; runCurrent()
        advanceTimeBy(END_DEBOUNCE_MS - 1); runCurrent()
        assertFalse(done)
        advanceTimeBy(2); runCurrent()
        assertTrue(done)
    }

    @Test fun awaitSnoozeFinishesImmediately() = runTest {
        val flow = MutableStateFlow<RingState?>(null)
        var done = false
        launch { awaitRingEnd(flow); done = true }
        flow.value = ring("X"); runCurrent()
        flow.value = ring("X", snoozed = true); runCurrent()
        assertTrue(done)
    }

    @Test fun userStopFinishesImmediately() = runTest {
        val flow = MutableStateFlow<RingState?>(null)
        val stop = kotlinx.coroutines.CompletableDeferred<Unit>()
        var done = false
        launch { awaitRingEnd(flow, userStopped = stop); done = true }
        flow.value = ring("X"); runCurrent()
        assertFalse(done)
        stop.complete(Unit); runCurrent()
        assertTrue(done)
    }

    @Test fun snoozeFinishes() {
        val m = RingingScreenModel()
        m.onState(ring("X"))
        assertTrue(m.onState(ring("X", snoozed = true)))
    }

    @Test fun snoozedAtLaunchIsNotSeen() {
        val m = RingingScreenModel()
        assertFalse(m.onState(ring("X", snoozed = true)))
        assertEquals(null, m.ringId)
        assertTrue(m.onGraceElapsed())
    }

    @Test fun graceFinishesOnlyWithoutRing() {
        val m = RingingScreenModel()
        assertTrue(m.onGraceElapsed())
        m.onState(ring("X"))
        assertFalse(m.onGraceElapsed())
    }

    @Test fun awaitFinishesAfterGraceWithNoRing() = runTest {
        val flow = MutableStateFlow<RingState?>(null)
        var done = false
        val job = launch { awaitRingEnd(flow, graceMs = 10_000); done = true }
        advanceTimeBy(9_999); runCurrent()
        assertFalse(done)
        advanceTimeBy(2); runCurrent()
        assertTrue(done)
        job.join()
    }

    @Test fun awaitDoesNotFinishOnGraceAfterRingButOnEnd() = runTest {
        val flow = MutableStateFlow<RingState?>(null)
        var done = false
        launch { awaitRingEnd(flow, graceMs = 10_000); done = true }
        advanceTimeBy(1_000); flow.value = ring("X"); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertFalse(done)
        flow.value = null; runCurrent()
        advanceTimeBy(END_DEBOUNCE_MS + 1); runCurrent()
        assertTrue(done)
    }

    @Test fun wallpaperFallbackOrder() {
        assertEquals(listOf("a", "d"), chooseWallpaperPaths("a", "d"))
        assertEquals(listOf("d"), chooseWallpaperPaths(null, "d"))
        assertEquals(listOf("d"), chooseWallpaperPaths(" ", "d"))
        assertEquals(emptyList<String>(), chooseWallpaperPaths(null, null))
    }

    @Test fun sampleSizeCapsPanoramaMemory() {
        val sample = sampleSizeFor(12000, 3000, 1440, 3120)
        assertEquals(4, sample)
        assertTrue((12000L / sample) * (3000L / sample) * 4 <= 2L * 1440 * 3120 * 4)
        assertEquals(1, sampleSizeFor(1080, 2400, 1080, 2400))
        assertEquals(2, sampleSizeFor(4000, 5000, 1080, 2400))
    }

    // ---- REMOTE ends: the notice ----

    private val occA = Instant.parse("2026-03-02T06:30:00Z")
    private val occB = Instant.parse("2026-03-02T06:35:00Z")
    private val snoozeEnd = Instant.parse("2026-03-02T06:40:00Z")
    private val merged = RingState("a", "X", Instant.EPOCH, null, occA, listOf(RingMember("b", occB)))

    /** Runs [awaitRingEnd] over [flow] and [notices], recording what it reports. */
    private class Screen(val flow: MutableStateFlow<RingState?>, val notices: RemoteRingNotices) {
        val rings = mutableListOf<RingState>()
        val shown = mutableListOf<RemoteRingEnd?>()
        var done = false
    }

    private fun kotlinx.coroutines.test.TestScope.screen(notices: RemoteRingNotices = RemoteRingNotices()): Screen {
        val s = Screen(MutableStateFlow(null), notices)
        backgroundScope.launch {
            awaitRingEnd(s.flow, remoteNotices = notices.latest, onRing = { s.rings += it }, onNotice = { s.shown += it })
            s.done = true
        }
        return s
    }

    @Test fun remoteDismissShowsTheNoticeThenFinishes() = runTest {
        val s = screen()
        s.flow.value = merged; runCurrent()
        s.notices.emit(RemoteRingEnd("X", RingMember("a", occA), MirrorKind.DISMISS, null, endsRing = true))
        s.flow.value = null; runCurrent()
        assertEquals(MirrorKind.DISMISS, s.shown.single()!!.kind)
        advanceTimeBy(END_DEBOUNCE_MS + 1); runCurrent()
        assertFalse(s.done) // not the null debounce: the notice stays up
        advanceTimeBy(REMOTE_NOTICE_MS - END_DEBOUNCE_MS); runCurrent()
        assertTrue(s.done)
    }

    @Test fun remoteSnoozeShowsTheNoticeThenFinishes() = runTest {
        val s = screen()
        s.flow.value = merged; runCurrent()
        s.notices.emit(RemoteRingEnd("X", RingMember("a", occA), MirrorKind.SNOOZE, snoozeEnd, endsRing = true))
        s.flow.value = merged.copy(snoozedUntil = snoozeEnd, merged = emptyList()); runCurrent()
        assertFalse(s.done) // not at once, as a LOCAL snooze would
        assertEquals(snoozeEnd, s.shown.single()!!.snoozedUntil)
        advanceTimeBy(REMOTE_NOTICE_MS - 1); runCurrent()
        assertFalse(s.done)
        advanceTimeBy(2); runCurrent()
        assertTrue(s.done)
    }

    @Test fun remoteRemovalOfOneMemberShowsTheNoticeAndKeepsTheScreen() = runTest {
        val s = screen()
        s.flow.value = merged; runCurrent()
        val notice = RemoteRingEnd("X", RingMember("b", occB), MirrorKind.DISMISS, null, endsRing = false)
        s.notices.emit(notice)
        s.flow.value = merged.copy(merged = emptyList()); runCurrent()
        assertEquals(notice.copy(seq = 1), s.shown.single())
        assertEquals(listOf(merged, merged.copy(merged = emptyList())), s.rings) // labels refresh
        advanceTimeBy(REMOTE_NOTICE_MS + 1); runCurrent()
        assertEquals(listOf(notice.copy(seq = 1), null), s.shown) // the notice goes away
        advanceTimeBy(60_000); runCurrent()
        assertFalse(s.done)
    }

    @Test fun localSnoozeStillClosesAtOnceDespiteAStaleNoticeForTheSameRing() = runTest {
        // A REMOTE snooze of X earlier; X came back from it under the same ringId (I3).
        val notices = RemoteRingNotices()
        notices.emit(RemoteRingEnd("X", RingMember("a", occA), MirrorKind.SNOOZE, snoozeEnd, endsRing = true))
        val s = screen(notices)
        s.flow.value = merged.copy(merged = emptyList()); runCurrent()
        s.flow.value = merged.copy(snoozedUntil = snoozeEnd, merged = emptyList()); runCurrent()
        assertTrue(s.done)
        assertTrue(s.shown.isEmpty())
    }

    @Test fun localSnoozeClosesAtOnce() = runTest {
        val s = screen()
        s.flow.value = merged; runCurrent()
        s.flow.value = merged.copy(snoozedUntil = snoozeEnd, merged = emptyList()); runCurrent()
        assertTrue(s.done)
    }

    @Test fun aSnoozeNoticeWhoseWriteNeverLandedDoesNotDelayALaterSnooze() = runTest {
        val s = screen()
        s.flow.value = merged; runCurrent()
        s.notices.emit(RemoteRingEnd("X", RingMember("a", occA), MirrorKind.SNOOZE, snoozeEnd, endsRing = true))
        // The REMOTE write threw; later the user snoozes to another time.
        s.flow.value = merged.copy(snoozedUntil = snoozeEnd.plusSeconds(60), merged = emptyList()); runCurrent()
        assertTrue(s.done)
        assertTrue(s.shown.isEmpty())
    }

    @Test fun aNoticeForAnotherRingIsIgnored() = runTest {
        val s = screen()
        s.flow.value = merged; runCurrent()
        s.notices.emit(RemoteRingEnd("Z", RingMember("z", occA), MirrorKind.DISMISS, null, endsRing = true))
        s.flow.value = null; runCurrent()
        assertTrue(s.shown.isEmpty())
        advanceTimeBy(END_DEBOUNCE_MS + 1); runCurrent()
        assertTrue(s.done)
    }

    @Test fun aNewRingDuringTheNoticeReplacesIt() = runTest {
        val s = screen()
        s.flow.value = merged; runCurrent()
        s.notices.emit(RemoteRingEnd("X", RingMember("a", occA), MirrorKind.DISMISS, null, endsRing = true))
        s.flow.value = null; runCurrent()
        val y = ring("Y")
        s.flow.value = y; runCurrent()
        assertNull(s.shown.last())
        assertEquals(y, s.rings.last())
        advanceTimeBy(REMOTE_NOTICE_MS + 1); runCurrent()
        assertFalse(s.done)
    }

    @Test fun promotionAndMergeRefreshTheShownRing() = runTest {
        val s = screen()
        val solo = RingState("a", "X", Instant.EPOCH, null, occA)
        s.flow.value = solo; runCurrent()
        val withB = solo.copy(merged = listOf(RingMember("b", occB)))
        s.flow.value = withB; runCurrent() // merge
        val promoted = RingState("b", "X", Instant.EPOCH, null, occB)
        s.flow.value = promoted; runCurrent() // a stopped: b is promoted under the same ringId
        s.flow.value = promoted; runCurrent() // unchanged: no refresh
        assertEquals(listOf(solo, withB, promoted), s.rings)
    }
}
