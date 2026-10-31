package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
