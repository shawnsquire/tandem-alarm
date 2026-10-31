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
