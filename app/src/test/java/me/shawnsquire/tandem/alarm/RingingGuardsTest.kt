package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingingGuardsTest {
    @Test fun throwingStateFlowIsReportedNotThrown() = runTest {
        val failures = mutableListOf<Throwable>()
        val bad = flow<RingState?> { throw java.io.IOException("corrupt ring_state") }
        guardedRingEnd({ awaitRingEnd(bad) }, onFailure = { failures += it })
        assertEquals(1, failures.size)
        assertTrue(failures.single() is java.io.IOException)
    }

    @Test fun cancellationIsRethrown() = runTest {
        var reported = false
        var rethrown = false
        try {
            guardedRingEnd({ throw CancellationException("x") }, onFailure = { reported = true })
        } catch (e: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertFalse(reported)
    }

    @Test fun duplicateSnoozeStartIsIgnored() {
        assertTrue(shouldIgnoreSnoozeStart(true, "r1", listOf("r1")))
        assertFalse(shouldIgnoreSnoozeStart(true, "r2", listOf("r1")))
        assertFalse(shouldIgnoreSnoozeStart(true, null, listOf("r1")))
        assertFalse(shouldIgnoreSnoozeStart(false, "r1", listOf("r1")))
        assertFalse(shouldIgnoreSnoozeStart(true, "r1", emptyList()))
    }
}

class RingingServiceHelpersTest {
    private class RecordingSlot(private val hang: Boolean) : RingSlot {
        val clearStaleCalls = mutableListOf<Boolean>()
        override suspend fun claimFire(newRing: RingState, stillDue: suspend () -> Boolean) = SlotClaim.Claimed
        override suspend fun claimSnoozeResume(ringId: String, alarmId: String, onMerged: (me.shawnsquire.tandem.schedule.RingMember) -> Unit) = SlotClaim.Claimed
        override suspend fun autoSilence(ringId: String, expected: List<me.shawnsquire.tandem.schedule.RingMember>) = true
        override suspend fun clearStale(isLive: (String) -> Boolean, reschedule: Boolean) {
            clearStaleCalls += reschedule
            if (hang) kotlinx.coroutines.awaitCancellation() else error("boom")
        }
    }

    @Test fun serviceClearsStaleWithoutRescheduleAndWithoutWaiting() = runTest {
        val slot = RecordingSlot(hang = true)
        val job = launchClearStale(backgroundScope, slot, { false }) { _, _ -> }
        testScheduler.runCurrent()
        assertEquals(listOf(false), slot.clearStaleCalls)
        assertTrue(job.isActive) // still stuck on the lock, and the caller was not held up
    }

    @Test fun clearStaleFailureIsLogged() = runTest {
        val logged = mutableListOf<String>()
        val job = launchClearStale(this, RecordingSlot(hang = false), { false }) { msg, _ -> logged += msg }
        job.join()
        assertEquals(1, logged.size)
    }

    @Test fun releaseRingsUnregistersAndCancelsEveryRing() {
        val registry = SyncRingRegistry().apply { register("r1"); register("r2") }
        val cancelled = mutableListOf<String>()
        releaseRings(listOf("r1", "r2"), registry) { cancelled += it; if (it == "r1") error("nm") }
        assertFalse(registry.isLive("r1"))
        assertFalse(registry.isLive("r2"))
        assertEquals(listOf("r1", "r2"), cancelled)
    }
}
