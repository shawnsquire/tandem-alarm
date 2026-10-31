package me.shawnsquire.tandem.sync

import android.Manifest
import android.app.NotificationManager
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidCommandNotifierTest {
    private val app = RuntimeEnvironment.getApplication()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val alarm = Alarm("a1", "Work", 6, 30, setOf(DayOfWeek.MONDAY), true, null, null, null)
    private val now = Instant.parse("2026-03-02T05:00:00Z")

    private fun cmd(type: CommandType) =
        Command("c1", "them", "me", type, "a1", now, null, now, now.plusSeconds(60))

    @Test fun `posts text with partner name on its own channel`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AndroidCommandNotifier(app).notifyApplied(cmd(CommandType.SKIP_NEXT), alarm, "Sam", turnedOff = false)
        assertNotNull(nm.getNotificationChannel("partner_commands"))
        val n = shadowOf(nm).allNotifications.single()
        val text = n.extras.getCharSequence("android.text").toString()
        assertTrue(text, text.startsWith("Sam skipped your "))
        assertEquals("partner_commands", n.channelId)
    }

    @Test fun `no permission posts nothing`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AndroidCommandNotifier(app).notifyApplied(cmd(CommandType.STOP_RINGING), null, "Sam", turnedOff = false)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }
}
