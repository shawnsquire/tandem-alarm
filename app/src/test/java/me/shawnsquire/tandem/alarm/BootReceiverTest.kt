package me.shawnsquire.tandem.alarm

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun rescheduleAsyncCallsSchedulerOnce() = runTest {
        val scheduler = FakeAlarmScheduler()
        val scope = TestScope(testScheduler)

        rescheduleAsync(null, scheduler, scope)
        scope.advanceUntilIdle()

        assertEquals(1, scheduler.rescheduleCount)
    }

    @Test
    fun rescheduleAsyncSwallowsSchedulerFailure() = runTest {
        val failing = object : AlarmScheduler {
            var calls = 0
            override suspend fun rescheduleAll() {
                calls++
                error("boom")
            }
        }
        val scope = TestScope(testScheduler)

        rescheduleAsync(null, failing, scope)
        scope.advanceUntilIdle()

        assertEquals(1, failing.calls)
    }

    @Test
    fun unrelatedActionDoesNotTouchSchedulerOrStartService() {
        // An unrelated action must return before goAsync() (which is unavailable outside a real
        // broadcast dispatch) and before touching the container.
        BootReceiver().onReceive(context, Intent("some.other.ACTION"))
        BootReceiver().onReceive(context, Intent())

        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }
}
