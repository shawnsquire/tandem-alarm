package me.shawnsquire.tandem.ui.settings.health

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthIntentsTest {
    @Test
    fun batteryIntentOpensTheSettingsListWithoutRequestingTheExemption() {
        val intent = batteryIntent()
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intent.action)
        assertNull(intent.data)
    }
}
