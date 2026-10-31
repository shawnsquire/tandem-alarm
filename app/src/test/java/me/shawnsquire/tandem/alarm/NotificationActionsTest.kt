package me.shawnsquire.tandem.alarm

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeRingingController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationActionsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun controlAsyncRunsDismissAndSnooze() = runTest {
        val controller = FakeRingingController()
        val scope = TestScope(testScheduler)

        controlAsync(null, scope) { controller.dismiss("r1") }
        controlAsync(null, scope) { controller.snooze() }
        scope.advanceUntilIdle()

        assertEquals(listOf("r1"), controller.dismissed)
        assertEquals(1, controller.snoozeCount)
    }

    @Test
    fun controlAsyncSwallowsFailure() = runTest {
        val scope = TestScope(testScheduler)
        controlAsync(null, scope) { error("boom") }
        scope.advanceUntilIdle()
    }

    @Test
    fun dismissPendingIntentsAreDistinctPerRing() {
        assertTrue(AlarmIntents.dismissPendingIntent(context, "a") != AlarmIntents.dismissPendingIntent(context, "b"))
    }

    @Test
    fun foregroundNotificationHasFullScreenIntentAndAlarmCategory() {
        val n = AndroidRingingNotifier(context).foregroundNotification()

        assertNotNull(n.fullScreenIntent)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertEquals("alarm", n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun channelsAreCreatedWithExpectedImportance() {
        AndroidRingingNotifier(context).ensureChannels()

        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("alarm").importance)
        assertNull(manager.getNotificationChannel("alarm").sound)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("snooze").importance)
    }

    @Test
    fun showSnoozedPostsDismissActionAndCancelRemovesIt() {
        val notifier = AndroidRingingNotifier(context)

        notifier.showSnoozed("r1", Instant.parse("2026-01-01T07:30:00Z"))
        val posted = snoozed("r1")
        assertNotNull(posted)
        assertEquals(1, posted!!.actions.size)
        assertEquals("Dismiss", posted.actions[0].title.toString())

        notifier.cancelSnoozed("r1")
        assertNull(snoozed("r1"))
    }

    private fun snoozed(ringId: String): Notification? = manager.activeNotifications
        .firstOrNull { it.tag == ringId && it.id == AndroidRingingNotifier.SNOOZED_NOTIFICATION_ID }
        ?.notification

    @Test
    fun snoozedNotificationsForTwoRingsCoexist() {
        val notifier = AndroidRingingNotifier(context)
        notifier.showSnoozed("r1", Instant.parse("2026-01-01T07:30:00Z"))
        notifier.showSnoozed("r2", Instant.parse("2026-01-01T07:40:00Z"))
        assertNotNull(snoozed("r1"))
        assertNotNull(snoozed("r2"))

        notifier.cancelSnoozed("r1")
        assertNull(snoozed("r1"))
        assertNotNull(snoozed("r2"))
    }

    @Test
    fun snoozePendingIntentsAreDistinctPerRing() {
        val at = Instant.parse("2026-01-01T07:30:00Z")
        val a = AlarmIntents.snoozePendingIntent(context, "x", "ring-A", at)
        val b = AlarmIntents.snoozePendingIntent(context, "x", "ring-B", at)
        assertTrue(a != b)
        assertEquals(a, AlarmIntents.existingSnoozePendingIntent(context, "ring-A"))
    }

    @Test
    fun showRingingHasStopAndSnoozeActions() {
        AndroidRingingNotifier(context).showRinging(null, "r1")
        val posted = shadowOf(manager).getNotification(RingingService.NOTIFICATION_ID)
        assertEquals(listOf("Stop", "Snooze"), posted.actions.map { it.title.toString() })
    }

    @Test
    fun serviceCallsStartForegroundImmediately() {
        val intent = android.content.Intent(context, RingingService::class.java)
            .putExtra(AlarmIntents.EXTRA_ALARM_ID, "alarm-work")
            .putExtra(AlarmIntents.EXTRA_OCCURRENCE, 1000L)
        val controller = Robolectric.buildService(RingingService::class.java, intent).create()
        controller.startCommand(0, 1)

        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
    }

    @Test
    fun foregroundNotificationReusesCurrentRingingUntilCleared() {
        val notifier = AndroidRingingNotifier(context)
        notifier.showRinging(null, "r1")
        val ringing = notifier.foregroundNotification()
        assertEquals(2, ringing.actions.size)

        notifier.clearRinging()
        assertNull(notifier.foregroundNotification().actions)
    }

    @Test
    fun serviceStartsForegroundForNullIntent() {
        val controller = Robolectric.buildService(RingingService::class.java).create()
        controller.startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        // The promotion happened (id recorded), then the idle service removed it and stopped itself.
        assertEquals(RingingService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.isStoppedBySelf)
    }

    @Test
    fun idleServiceStopsWithTheLatestStartId() {
        val controller = Robolectric.buildService(RingingService::class.java).create()
        controller.startCommand(0, 7)
        // stopSelf() without an id would kill a start that is still in flight.
        assertEquals(7, shadowOf(controller.get()).stopSelfResultId)
    }
}
