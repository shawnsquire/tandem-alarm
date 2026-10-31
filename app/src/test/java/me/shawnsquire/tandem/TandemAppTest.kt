package me.shawnsquire.tandem

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.alarm.AlarmEngine
import me.shawnsquire.tandem.alarm.SwitchableAlarmRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class TandemAppTest {
    @Test
    fun applicationBootsAndBuildsContainer() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(app is TandemApp)
        // Robolectric's user starts unlocked, so onCreate ran full startup itself.
        assertNotNull((app as TandemApp).container.alarmRepository)
        assertEquals(1, app.fullStartupRuns)
        app.ensureStarted()
        assertEquals(1, app.fullStartupRuns)
        val alarms = AlarmEngine.get(app).alarmRepository as SwitchableAlarmRepository
        withTimeoutOrNull(5.seconds) { while (!alarms.isAttached) delay(10) }
        assertTrue("engine attached", alarms.isAttached)
    }
}
