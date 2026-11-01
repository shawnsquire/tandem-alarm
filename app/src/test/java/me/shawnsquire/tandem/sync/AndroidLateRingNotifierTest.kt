package me.shawnsquire.tandem.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidLateRingNotifierTest {
    private val app = RuntimeEnvironment.getApplication()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val now = Instant.parse("2026-03-02T05:50:00Z")
    private val clock = Clock.fixed(now, ZoneId.systemDefault())
    private val notifier = AndroidLateRingNotifier(app, clock)

    private val movedTo = Instant.parse("2026-03-02T06:00:00Z")
    private val old = Instant.parse("2026-03-02T06:30:00Z")
    private val prompt = LateRingPrompt("a1", "Sam", movedTo, old)

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun short(at: Instant) =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at.atZone(ZoneId.systemDefault()))

    private fun single(): Notification = shadowOf(nm).allNotifications.single()

    @Test fun `posts the question with both times on the partner channel`() {
        grant()
        notifier.post(prompt)
        val n = single()
        val expected = "Sam's phone hasn't picked up the change to ${short(movedTo)} yet. " +
            "If it still hasn't by ${short(old)}, should it ring then?"
        assertEquals(expected, n.extras.getCharSequence("android.text").toString())
        assertEquals(expected, n.extras.getCharSequence("android.bigText").toString())
        assertEquals("partner_commands", n.channelId)
        assertTrue(n.contentIntent != null)
    }

    @Test fun `has Ring and Skip actions that broadcast the answer`() {
        grant()
        notifier.post(prompt)
        val n = single()
        assertEquals(listOf("Ring at ${short(old)}", "Skip"), n.actions.map { it.title.toString() })
        val intents = n.actions.map { shadowOf(it.actionIntent).savedIntent }
        assertEquals(
            listOf(LateRingActionReceiver.ACTION_RING, LateRingActionReceiver.ACTION_SKIP),
            intents.map { it.action },
        )
        intents.forEach {
            assertEquals(LateRingActionReceiver::class.java.name, it.component!!.className)
            assertEquals("a1", it.getStringExtra(LateRingActionReceiver.EXTRA_ALARM_ID))
            assertEquals(old.toEpochMilli(), it.getLongExtra(LateRingActionReceiver.EXTRA_OCCURRENCE_AT, 0))
        }
        assertEquals(2, n.actions.map { shadowOf(it.actionIntent).requestCode }.toSet().size)
        n.actions.forEach { assertTrue(shadowOf(it.actionIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0) }
    }

    @Test fun `times out when the old occurrence passes`() {
        grant()
        notifier.post(prompt)
        assertEquals(old.toEpochMilli() - now.toEpochMilli(), single().timeoutAfter)
    }

    @Test fun `is tagged by alarm and a re-post replaces it`() {
        grant()
        notifier.post(prompt)
        notifier.post(prompt.copy(movedTo = movedTo.minusSeconds(900)))
        val posted = shadowOf(nm).allNotifications
        assertEquals(1, posted.size)
        assertTrue(nm.activeNotifications.single().tag == "late-ring:a1")
        notifier.post(prompt.copy(alarmId = "a2"))
        assertEquals(2, shadowOf(nm).allNotifications.size)
    }

    @Test fun `cancel removes it and posted lists the alarm ids`() {
        grant()
        assertTrue(notifier.posted().isEmpty())
        notifier.post(prompt)
        notifier.post(prompt.copy(alarmId = "a2"))
        assertEquals(setOf("a1", "a2"), notifier.posted())
        notifier.cancel("a1")
        assertEquals(setOf("a2"), notifier.posted())
    }

    @Test fun `a deadline already passed posts nothing`() {
        grant()
        notifier.post(prompt.copy(occurrenceAt = now))
        notifier.post(prompt.copy(occurrenceAt = now.minusSeconds(60)))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun `no permission posts nothing`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.post(prompt)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }
}
