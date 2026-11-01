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

    private val linked = Alarm(
        "L1", "Work", 6, 30, setOf(DayOfWeek.MONDAY), true, null, null, null,
        link = me.shawnsquire.tandem.schedule.AlarmLink(
            "them", "o9", me.shawnsquire.tandem.schedule.LinkMode.RING, null, true, null, null, null, null,
        ),
    )
    private val notifier get() = AndroidCommandNotifier(app)

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun text(n: android.app.Notification) = n.extras.getCharSequence("android.text").toString()

    @Test fun `invite posts text with Join and Decline actions`() {
        grant()
        val pa = PartnerAlarm("o9", "Gym", 7, 15, setOf(DayOfWeek.MONDAY), true, null, null)
        notifier.notifyInvite(cmd(CommandType.INVITE_LINK), pa, "Sam")
        val n = shadowOf(nm).allNotifications.single()
        val t = text(n)
        assertTrue(t, t.startsWith("Sam invited you to join "))
        assertTrue(t, t.contains("Gym"))
        assertEquals("partner_commands", n.channelId)
        assertEquals(listOf("Join", "Decline"), n.actions.map { it.title.toString() })
        assertNotNull(n.contentIntent)
    }

    @Test fun `invite actions send explicit broadcasts with command and owner alarm ids`() {
        grant()
        notifier.notifyInvite(cmd(CommandType.INVITE_LINK).copy(alarmId = "o9"), null, "Sam")
        val n = shadowOf(nm).allNotifications.single()
        val intents = n.actions.map { shadowOf(it.actionIntent).savedIntent }
        assertEquals(
            listOf(InviteActionReceiver.ACTION_JOIN, InviteActionReceiver.ACTION_DECLINE),
            intents.map { it.action },
        )
        intents.forEach {
            assertEquals(InviteActionReceiver::class.java.name, it.component!!.className)
            assertEquals("c1", it.getStringExtra(InviteActionReceiver.EXTRA_COMMAND_ID))
            assertEquals("o9", it.getStringExtra(InviteActionReceiver.EXTRA_OWNER_ALARM_ID))
        }
        assertEquals(2, n.actions.map { shadowOf(it.actionIntent).requestCode }.toSet().size)
        n.actions.forEach {
            assertTrue(shadowOf(it.actionIntent).flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        }
    }

    @Test fun `invite id is stable per command and distinct across commands`() {
        grant()
        notifier.notifyInvite(cmd(CommandType.INVITE_LINK), null, "Sam")
        notifier.notifyInvite(cmd(CommandType.INVITE_LINK), null, "Sam")
        assertEquals(1, shadowOf(nm).allNotifications.size)
        notifier.notifyInvite(cmd(CommandType.INVITE_LINK).copy(id = "c2"), null, "Sam")
        assertEquals(2, shadowOf(nm).allNotifications.size)
    }

    @Test fun `owner deleted text`() {
        grant()
        notifier.notifyOwnerDeleted(linked, "Sam")
        val n = shadowOf(nm).allNotifications.single()
        assertEquals("partner_commands", n.channelId)
        val t = text(n)
        assertTrue(t, t.startsWith("Sam deleted the "))
        assertTrue(t, t.endsWith(" you joined, so you've left it"))
    }

    @Test fun `owner turned off text`() {
        grant()
        notifier.notifyOwnerTurnedOff(linked, "Sam")
        val t = text(shadowOf(nm).allNotifications.single())
        assertTrue(t, t.startsWith("Sam turned off the "))
        assertTrue(t, t.endsWith(" you joined, so it's off for you too"))
    }

    @Test fun `rejected text includes the reason when given`() {
        grant()
        notifier.notifyRejected(linked, CommandType.SKIP_NEXT, "already rang or changed", "Sam")
        val n = shadowOf(nm).allNotifications.single()
        assertEquals("partner_commands", n.channelId)
        val t = text(n)
        assertTrue(t, t.startsWith("Your \"both of us\" change to Sam's "))
        assertTrue(t, t.contains("didn't go through"))
        assertTrue(t, t.endsWith("already rang or changed"))
    }

    @Test fun `rejected text without a reason`() {
        grant()
        notifier.notifyRejected(linked, CommandType.DISABLE, " ", "Sam")
        val t = text(shadowOf(nm).allNotifications.single())
        assertTrue(t, t.endsWith("didn't go through"))
    }

    @Test fun `link notifications post nothing without permission`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.notifyInvite(cmd(CommandType.INVITE_LINK), null, "Sam")
        notifier.notifyOwnerDeleted(linked, "Sam")
        notifier.notifyOwnerTurnedOff(linked, "Sam")
        notifier.notifyRejected(linked, CommandType.SKIP_NEXT, null, "Sam")
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun `cancelInvite removes the notification`() {
        grant()
        notifier.notifyInvite(cmd(CommandType.INVITE_LINK), null, "Sam")
        AndroidCommandNotifier.cancelInvite(app, "c1")
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }
}
