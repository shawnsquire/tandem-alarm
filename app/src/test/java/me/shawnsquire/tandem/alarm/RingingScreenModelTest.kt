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
        assertTrue(m.onState(null))
    }

    @Test fun differentRingIdFinishes() {
        val m = RingingScreenModel()
        m.onState(ring("X"))
        assertFalse(m.onState(ring("X")))
        assertTrue(m.onState(ring("Y")))
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
        assertTrue(done)
    }

    @Test fun wallpaperFallbackOrder() {
        assertEquals(listOf("a", "d"), chooseWallpaperPaths("a", "d"))
        assertEquals(listOf("d"), chooseWallpaperPaths(null, "d"))
        assertEquals(listOf("d"), chooseWallpaperPaths(" ", "d"))
        assertEquals(emptyList<String>(), chooseWallpaperPaths(null, null))
    }
}
