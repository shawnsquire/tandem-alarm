package me.shawnsquire.tandem.alarm

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AlarmReceiverTest {
    private val PowerManagerTag = "tandem:fire"
    private val context: Context = RuntimeEnvironment.getApplication()

    @org.junit.Before
    fun resetFireLock() = FireWakeLock.resetForTest()

    @Test
    fun fireStartsRingingServiceWithActionAndExtras() {
        val intent = Intent(AlarmIntents.ACTION_FIRE)
            .putExtra(AlarmIntents.EXTRA_ALARM_ID, "a1")
            .putExtra(AlarmIntents.EXTRA_OCCURRENCE, 1234L)

        AlarmReceiver().onReceive(context, intent)

        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNotNull(started)
        assertEquals(RingingService::class.java.name, started.component?.className)
        assertEquals(AlarmIntents.ACTION_FIRE, started.action)
        assertEquals("a1", started.getStringExtra(AlarmIntents.EXTRA_ALARM_ID))
        assertEquals(1234L, started.getLongExtra(AlarmIntents.EXTRA_OCCURRENCE, -1L))
    }

    @Test
    fun fireAndSnoozeFireAcquireTheFireWakeLock() {
        FireWakeLock.release()
        AlarmReceiver().onReceive(context, Intent(AlarmIntents.ACTION_FIRE).putExtra(AlarmIntents.EXTRA_ALARM_ID, "a1"))
        val lock = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        assertEquals(PowerManagerTag, shadowOf(lock).tag)
        FireWakeLock.release()
        assertFalse(lock.isHeld)

        AlarmReceiver().onReceive(context, Intent(AlarmIntents.ACTION_SNOOZE_FIRE).putExtra(AlarmIntents.EXTRA_ALARM_ID, "a1"))
        assertTrue(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
        FireWakeLock.release()
    }

    @Test
    fun releaseWithoutHoldingIsSafe() {
        FireWakeLock.release()
        FireWakeLock.release()
    }

    @Test
    fun snoozeFireCarriesRingIdAndIsSnooze() {
        val intent = Intent(AlarmIntents.ACTION_SNOOZE_FIRE)
            .putExtra(AlarmIntents.EXTRA_ALARM_ID, "a1")
            .putExtra(AlarmIntents.EXTRA_RING_ID, "r1")
            .putExtra(AlarmIntents.EXTRA_IS_SNOOZE, true)
            .putExtra(AlarmIntents.EXTRA_OCCURRENCE, 99L)

        AlarmReceiver().onReceive(context, intent)

        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNotNull(started)
        assertEquals(RingingService::class.java.name, started.component?.className)
        assertEquals(AlarmIntents.ACTION_SNOOZE_FIRE, started.action)
        assertEquals("a1", started.getStringExtra(AlarmIntents.EXTRA_ALARM_ID))
        assertEquals("r1", started.getStringExtra(AlarmIntents.EXTRA_RING_ID))
        assertTrue(started.getBooleanExtra(AlarmIntents.EXTRA_IS_SNOOZE, false))
        assertEquals(99L, started.getLongExtra(AlarmIntents.EXTRA_OCCURRENCE, -1L))
    }

    @Test
    fun controlAsyncSwallowsErrorsAndRunsAction() = runTest {
        val scope = TestScope(testScheduler)
        var ran = 0
        val job = controlAsync(null, scope) {
            ran++
            throw NoClassDefFoundError("boom")
        }
        scope.advanceUntilIdle()
        assertEquals(1, ran)
        assertFalse(job.isCancelled)
    }

    @Test
    fun unknownActionStartsNothing() {
        AlarmReceiver().onReceive(context, Intent("some.other.ACTION"))
        AlarmReceiver().onReceive(context, Intent())

        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }
}
