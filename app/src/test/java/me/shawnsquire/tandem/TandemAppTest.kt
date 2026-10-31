package me.shawnsquire.tandem

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TandemAppTest {
    @Test
    fun applicationBootsAndBuildsContainer() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(app is TandemApp)
        assertNotNull((app as TandemApp).container.alarmRepository)
    }
}
