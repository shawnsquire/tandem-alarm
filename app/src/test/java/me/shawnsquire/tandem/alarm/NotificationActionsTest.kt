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
        val posted = shadowOf(manager).getNotification(AndroidRingingNotifier.SNOOZED_NOTIFICATION_ID)
        assertNotNull(posted)
        assertEquals(1, posted.actions.size)
        assertEquals("Dismiss", posted.actions[0].title.toString())

        notifier.cancelSnoozed()
        assertNull(shadowOf(manager).getNotification(AndroidRingingNotifier.SNOOZED_NOTIFICATION_ID))
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
}
