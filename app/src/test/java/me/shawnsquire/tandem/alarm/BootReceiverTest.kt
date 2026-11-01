package me.shawnsquire.tandem.alarm

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun rescheduleAsyncSwallowsErrors() = runTest {
        val failing = object : AlarmScheduler {
            var calls = 0
            override suspend fun rescheduleAll() {
                calls++
                throw NoClassDefFoundError("boom")
            }
        }
        val scope = TestScope(testScheduler)

        val job = rescheduleAsync(null, failing, scope)
        scope.advanceUntilIdle()

        assertEquals(1, failing.calls)
        assertFalse(job.isCancelled)
    }

    @Test
    fun unrelatedActionDoesNotTouchSchedulerOrStartService() {
        // An unrelated action must return before goAsync() (which is unavailable outside a real
        // broadcast dispatch) and before touching the container.
        BootReceiver().onReceive(context, Intent("some.other.ACTION"))
        BootReceiver().onReceive(context, Intent())

        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    private class OrderedSlot(val events: MutableList<String>, val fail: Boolean = false) : RingSlot {
        var reschedule: Boolean? = null
        override suspend fun claimFire(newRing: me.shawnsquire.tandem.schedule.RingState, stillDue: suspend () -> Boolean) = SlotClaim.Claimed
        override suspend fun claimSnoozeResume(ringId: String, alarmId: String, onMerged: (me.shawnsquire.tandem.schedule.RingMember) -> Unit) = SlotClaim.NotDue
        override suspend fun autoSilence(ringId: String, expected: List<me.shawnsquire.tandem.schedule.RingMember>) = true
        override suspend fun clearStale(isLive: (String) -> Boolean, reschedule: Boolean) {
            events += "clearStale"
            this.reschedule = reschedule
            if (fail) error("boom")
        }
    }

    @Test
    fun rescheduleAsyncClearsStaleRingStateBeforeRescheduling() = runTest {
        val events = mutableListOf<String>()
        val scheduler = object : AlarmScheduler {
            override suspend fun rescheduleAll() { events += "reschedule" }
        }
        val slot = OrderedSlot(events)
        val scope = TestScope(testScheduler)

        rescheduleAsync(null, scheduler, scope, ringSlot = slot)
        scope.advanceUntilIdle()

        assertEquals(listOf("clearStale", "reschedule"), events)
        assertEquals(false, slot.reschedule) // the boot reschedule follows and needs the old records
    }

    @Test
    fun rescheduleAsyncStillReschedulesWhenClearStaleFails() = runTest {
        val events = mutableListOf<String>()
        val scheduler = object : AlarmScheduler {
            override suspend fun rescheduleAll() { events += "reschedule" }
        }
        val scope = TestScope(testScheduler)

        rescheduleAsync(null, scheduler, scope, ringSlot = OrderedSlot(events, fail = true))
        scope.advanceUntilIdle()

        assertEquals(listOf("clearStale", "reschedule"), events)
    }
}
