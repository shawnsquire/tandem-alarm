package me.shawnsquire.tandem.sync

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class ResubscribeTest {
    @Test fun `passes values through and ends when upstream completes`() = runTest {
        flowOf(1, 2).resubscribeOnError(-1).test {
            assertEquals(1, awaitItem()); assertEquals(2, awaitItem()); awaitComplete()
        }
    }

    @Test fun `error emits the stand-in, waits, resubscribes and doubles the wait up to the cap`() = runTest {
        val times = mutableListOf<Long>()
        val src = flow<Int> { times += testScheduler.currentTime; throw IllegalStateException("x") }
        src.resubscribeOnError(-1, initial = 2.seconds, max = 5.seconds).test {
            repeat(4) { assertEquals(-1, awaitItem()) }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(0L, 2_000L, 6_000L, 11_000L), times)
    }

    @Test fun `a subscription that stayed up for the wait resets it`() = runTest {
        var n = 0
        val times = mutableListOf<Long>()
        val src = flow {
            times += testScheduler.currentTime
            emit(7)
            if (++n == 3) delay(5.seconds) // up for longer than the 4 s wait it was started with
            throw IllegalStateException("x")
        }
        src.resubscribeOnError(-1, initial = 1.seconds, max = 60.seconds).test {
            repeat(4) { assertEquals(7, awaitItem()); assertEquals(-1, awaitItem()) }
            cancelAndIgnoreRemainingEvents()
        }
        // 0, +1s, +2s; the third stays up 5 s and fails at 8 s, then the wait starts over at 1 s.
        assertEquals(listOf(0L, 1_000L, 3_000L, 9_000L), times.take(4))
    }

    @Test fun `a value right before each failure doesn't reset the wait`() = runTest {
        // A cached snapshot raised before the server rejects the listen (PERMISSION_DENIED).
        val times = mutableListOf<Long>()
        val src = flow { times += testScheduler.currentTime; emit(7); throw IllegalStateException("denied") }
        src.resubscribeOnError(-1, initial = 2.seconds, max = 5.seconds).test {
            repeat(4) { assertEquals(7, awaitItem()); assertEquals(-1, awaitItem()) }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(0L, 2_000L, 6_000L, 11_000L), times)
    }

    @Test fun `an error is logged before resubscribing`() = runTest {
        ShadowLog.clear()
        flow<Int> { throw IllegalStateException("denied") }.resubscribeOnError(-1).test {
            assertEquals(-1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        val log = ShadowLog.getLogsForTag("Resubscribe").single()
        assertEquals(android.util.Log.WARN, log.type)
        assertEquals("denied", log.throwable?.message)
    }
}
