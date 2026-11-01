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
import org.junit.Assert.assertFalse
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

    @org.junit.Before
    fun resetFireLock() = FireWakeLock.resetForTest()
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
    fun prepNotificationIsQuietWithNoFullScreenIntent() {
        val notifier = AndroidRingingNotifier(context)
        val n = notifier.prepNotification()

        assertNull(n.fullScreenIntent)
        assertEquals(AndroidRingingNotifier.PREP_CHANNEL, n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("alarm_prep").importance)
        assertNull(manager.getNotificationChannel("alarm_prep").sound)
        assertEquals("Checking alarm\u2026", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
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
    fun showRingingPostsFullScreenNotificationUnderItsOwnIdAndReportsIt() {
        val reported = mutableListOf<Triple<String, Int, Notification>>()
        AndroidRingingNotifier(context) { r, id, n -> reported += Triple(r, id, n) }.showRinging(null, "r1")

        val id = AndroidRingingNotifier.ringingNotificationId("r1")
        val posted = shadowOf(manager).getNotification(id)
        assertEquals(listOf("Stop", "Snooze"), posted.actions.map { it.title.toString() })
        assertNotNull(posted.fullScreenIntent)
        assertNotNull(posted.contentIntent)
        assertEquals("alarm", posted.channelId)
        assertEquals(Notification.CATEGORY_ALARM, posted.category)
        assertEquals(1, reported.size)
        assertEquals("r1", reported[0].first)
        assertEquals(id, reported[0].second)
    }

    @Test
    fun notifyRingingIsAHeadsUpAlarmWithActionsAndNoFullScreenIntent() {
        val reported = mutableListOf<Triple<String, Int, Notification>>()
        AndroidRingingNotifier(context) { r, id, n -> reported += Triple(r, id, n) }.showNotifyRinging(null, "r1")

        val id = AndroidRingingNotifier.notifyNotificationId("r1")
        val posted = shadowOf(manager).getNotification(id)
        assertEquals(AndroidRingingNotifier.NOTIFY_CHANNEL, posted.channelId)
        assertEquals(Notification.CATEGORY_ALARM, posted.category)
        assertNull(posted.fullScreenIntent)
        assertNotNull(posted.contentIntent)
        assertEquals(listOf("Stop", "Snooze"), posted.actions.map { it.title.toString() })
        assertEquals(listOf("r1" to id), reported.map { it.first to it.second }) // promoted like a ring
        assertTrue(id != AndroidRingingNotifier.ringingNotificationId("r1"))

        val channel = manager.getNotificationChannel(AndroidRingingNotifier.NOTIFY_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, channel.sound)
        assertEquals(android.media.AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
    }

    @Test
    fun showRingingAfterNotifyPostsAFreshFullScreenNotificationThenRemovesTheNotifyOne() {
        val events = mutableListOf<String>()
        val notifyId = AndroidRingingNotifier.notifyNotificationId("r1")
        val ringingId = AndroidRingingNotifier.ringingNotificationId("r1")
        val notifier = AndroidRingingNotifier(context) { _, id, _ ->
            // The service promotes here; the Notify one must still be posted (it is still the foreground one).
            events += "foreground:$id:notifyPosted=${shadowOf(manager).getNotification(notifyId) != null}"
        }
        notifier.showNotifyRinging(null, "r1")
        notifier.showRinging(null, "r1")

        assertEquals(listOf("foreground:$notifyId:notifyPosted=true", "foreground:$ringingId:notifyPosted=true"), events)
        assertNull(shadowOf(manager).getNotification(notifyId))
        assertNotNull(shadowOf(manager).getNotification(ringingId).fullScreenIntent)
    }

    @Test
    fun cancelRingingRemovesTheNotifyNotification() {
        val notifier = AndroidRingingNotifier(context)
        notifier.showNotifyRinging(null, "r1")
        notifier.cancelRinging("r1")
        assertNull(shadowOf(manager).getNotification(AndroidRingingNotifier.notifyNotificationId("r1")))
    }

    @Test
    fun serviceForegroundFollowsANotifyRingUpgradedToTheFullSurface() {
        val controller = Robolectric.buildService(RingingService::class.java, fireIntent()).create()
        controller.startCommand(0, 1)
        val service = controller.get()
        val notifier = AndroidRingingNotifier(context, service::onRingingPosted)
        notifier.showNotifyRinging(null, "ring-A")
        assertEquals(AndroidRingingNotifier.notifyNotificationId("ring-A"), shadowOf(service).lastForegroundNotificationId)

        notifier.showRinging(null, "ring-A")
        val ringingId = AndroidRingingNotifier.ringingNotificationId("ring-A")
        assertEquals(ringingId, shadowOf(service).lastForegroundNotificationId)
        assertNull(shadowOf(manager).getNotification(AndroidRingingNotifier.notifyNotificationId("ring-A")))

        controller.startCommand(0, 2) // re-entry keeps the upgraded notification as the foreground one
        assertEquals(ringingId, shadowOf(service).lastForegroundNotificationId)
    }

    @Test
    fun fallbackRingingHasFullScreenButNoActions() {
        AndroidRingingNotifier(context).showFallbackRinging("fallback-1")
        val posted = shadowOf(manager).getNotification(AndroidRingingNotifier.ringingNotificationId("fallback-1"))
        assertNotNull(posted.fullScreenIntent)
        assertNotNull(posted.contentIntent)
        assertNull(posted.actions)
    }

    @Test
    fun reentryKeepsRingingNotificationAsForeground() {
        val controller = Robolectric.buildService(RingingService::class.java, fireIntent()).create()
        controller.startCommand(0, 1) // session A, pending
        val n = AndroidRingingNotifier(context)
        val ringId = "ring-A"
        val id = AndroidRingingNotifier.ringingNotificationId(ringId)
        n.showRinging(null, ringId)
        controller.get().onRingingPosted(ringId, id, shadowOf(manager).getNotification(id))
        assertEquals(id, shadowOf(controller.get()).lastForegroundNotificationId)

        controller.startCommand(0, 2) // session B starts; foreground must stay on A's notification
        assertEquals(id, shadowOf(controller.get()).lastForegroundNotificationId)
    }

    @Test
    fun ringingNotificationIdsAvoidReservedIds() {
        val id = AndroidRingingNotifier.ringingNotificationId("r1")
        assertTrue(id != AndroidRingingNotifier.PREP_NOTIFICATION_ID && id != AndroidRingingNotifier.SNOOZED_NOTIFICATION_ID && id != 0)
        assertTrue(id != AndroidRingingNotifier.ringingNotificationId("r2"))
    }

    @Test
    fun cancelRingingRemovesIt() {
        val notifier = AndroidRingingNotifier(context)
        notifier.showRinging(null, "r1")
        notifier.cancelRinging("r1")
        assertNull(shadowOf(manager).getNotification(AndroidRingingNotifier.ringingNotificationId("r1")))
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
    fun suppressedFirePostsNoFullScreenIntent() {
        // Alarm does not exist locally, so the session finishes without ringing.
        val intent = android.content.Intent(context, RingingService::class.java)
            .putExtra(AlarmIntents.EXTRA_ALARM_ID, "missing")
            .putExtra(AlarmIntents.EXTRA_OCCURRENCE, 1000L)
        val controller = Robolectric.buildService(RingingService::class.java, intent).create()
        controller.startCommand(0, 1)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertTrue(shadowOf(manager).allNotifications.none { it.fullScreenIntent != null })
    }

    class ThrowingTypedService : RingingService() {
        override fun startForegroundTyped(id: Int, notification: Notification) = throw SecurityException("typed")
    }

    class ThrowingBothService : RingingService() {
        override fun startForegroundTyped(id: Int, notification: Notification) = throw SecurityException("typed")
        override fun startForegroundPlain(id: Int, notification: Notification) = throw IllegalStateException("plain")
    }

    private fun fireIntent() = android.content.Intent(context, RingingService::class.java)
        .putExtra(AlarmIntents.EXTRA_ALARM_ID, "alarm-work")
        .putExtra(AlarmIntents.EXTRA_OCCURRENCE, 1000L)

    @Test
    fun typedStartForegroundFailureFallsBackToPlainAndSessionStarts() {
        val controller = Robolectric.buildService(ThrowingTypedService::class.java, fireIntent()).create()
        controller.startCommand(0, 1)

        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        assertEquals(1, controller.get().sessionsStarted)
    }

    @Test
    fun startForegroundFailingEntirelyStillStartsSession() {
        val controller = Robolectric.buildService(ThrowingBothService::class.java, fireIntent()).create()
        controller.startCommand(0, 1)

        assertEquals(1, controller.get().sessionsStarted)
    }

    @Test
    fun serviceReleasesFireWakeLockAfterTakingItsOwn() {
        FireWakeLock.acquire(context)
        val fire = FireWakeLock.lockForTest()!!
        assertTrue(fire.isHeld)
        val service = Robolectric.buildService(RingingService::class.java, fireIntent()).create().startCommand(0, 1).get()
        assertFalse(fire.isHeld)
        val own = service.wakeLockForTest()!!
        assertTrue(own !== fire && own.isHeld)
    }

    @Test
    fun serviceStartsForegroundForNullIntent() {
        val controller = Robolectric.buildService(RingingService::class.java).create()
        controller.startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        // The promotion happened (id recorded), then the idle service removed it and stopped itself.
        assertEquals(AndroidRingingNotifier.PREP_NOTIFICATION_ID, shadow.lastForegroundNotificationId)
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
