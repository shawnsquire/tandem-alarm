package me.shawnsquire.tandem.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.provider.Settings
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.schedule.Alarm
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ConcurrentHashMap

/**
 * Notification-manager implementation of [RingingNotifier], plus the quiet prep notification.
 * [onRinging] is called after a ringing notification is posted so the service can promote it.
 */
class AndroidRingingNotifier(
    private val context: Context,
    private val onRinging: (ringId: String, id: Int, notification: Notification) -> Unit = { _, _, _ -> },
) : RingingNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** The Notify id posted for each ring this instance shows (see [showNotifyRinging]); the service's instance posts them all. */
    private val notifyUp = ConcurrentHashMap<String, Int>()

    fun ensureChannels() {
        val alarm = NotificationChannel(ALARM_CHANNEL, context.getString(R.string.alarm_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.alarm_channel_description)
            setSound(null, null) // MediaPlayer plays the ringtone
            enableVibration(false) // the vibration adapter vibrates
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        // Notify-mode rings alert through the channel itself, once per post. IMPORTANCE_HIGH "makes a
        // sound and appears as a heads-up notification" (heads-up only while unlocked); it can't be
        // changed once the channel exists. USAGE_ALARM: "Usage value to use when the usage is an alarm".
        // The default notification sound, not the default alarm alert: a heads-up wants a short chime,
        // and an alarm tone can run long.
        // https://developer.android.com/develop/ui/compose/notifications/channels
        // https://developer.android.com/reference/android/app/NotificationChannel#setSound(android.net.Uri,%20android.media.AudioAttributes)
        val notify = NotificationChannel(NOTIFY_CHANNEL, context.getString(R.string.alarm_notify_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.alarm_notify_channel_description)
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val snooze = NotificationChannel(SNOOZE_CHANNEL, context.getString(R.string.snooze_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.snooze_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val prep = NotificationChannel(PREP_CHANNEL, context.getString(R.string.alarm_prep_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.alarm_prep_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannels(listOf(alarm, notify, snooze, prep))
    }

    /** Quiet notification for the first `startForeground`: low importance, no full-screen intent. */
    fun prepNotification(): Notification {
        ensureChannels()
        return Notification.Builder(context, PREP_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.alarm_prep_title))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    fun cancelPrep() = manager.cancel(PREP_NOTIFICATION_ID)

    /** Every id of both surfaces, statelessly: a ring upgraded from Notify may have left any of them, and another instance may ask. */
    override fun cancelRinging(ringId: String) {
        manager.cancel(ringingNotificationId(ringId))
        cancelNotify(ringId)
    }

    private fun cancelNotify(ringId: String) {
        notifyUp.remove(ringId)
        manager.cancel(notifyNotificationId(ringId))
        manager.cancel(notifyAgainNotificationId(ringId))
    }

    /**
     * Also the upgrade of a Notify ring: the full notification is a fresh post under its own id (so
     * it launches the full-screen intent), it becomes the foreground one through [onRinging], and
     * only then is the Notify one cancelled (the system won't remove the current foreground notification).
     */
    override fun showRinging(alarm: Alarm?, ringId: String, labels: List<String>) {
        post(labels, ringId, withActions = true)
        cancelNotify(ringId)
    }

    override fun showFallbackRinging(ringId: String) = post(emptyList(), ringId, withActions = false)

    /**
     * Re-posts under the id already up, so it is an update of that notification: only-alert-once
     * keeps it from alerting again, and it carries no full-screen intent (see [post]). [onRinging]
     * gets the new one, so the service re-promotes the current label. The other surface's
     * notification is left alone.
     */
    override fun updateRingingLabel(alarm: Alarm?, ringId: String, labels: List<String>, notifySurface: Boolean): Boolean {
        if (notifySurface) return showNotifyRinging(alarm, ringId, labels)
        post(labels, ringId, withActions = true, fullScreen = false)
        return true
    }

    /**
     * Heads-up only, so the ringing activity never launches by itself: no full-screen intent. A tap
     * opens the app rather than the ringing screen; Stop and Snooze are on the notification. CATEGORY_ALARM
     * ("alarm or timer"): an alarms-only Do Not Disturb suppresses "all notifications except those of
     * category Notification.CATEGORY_ALARM".
     * https://developer.android.com/reference/android/app/Notification#CATEGORY_ALARM
     * https://developer.android.com/reference/android/app/NotificationManager#INTERRUPTION_FILTER_ALARMS
     *
     * Posts nothing and returns false when it couldn't alert ([notifyCanAlert]). Otherwise an update
     * of the one up, except with [alertAgain]: only-alert-once plays sound "only if the notification
     * is not already showing", so that is a fresh post under [ringId]'s other Notify id, made the
     * foreground one through [onRinging] before the old one is cancelled (as [showRinging] upgrades).
     * https://developer.android.com/reference/android/app/Notification.Builder#setOnlyAlertOnce(boolean)
     */
    override fun showNotifyRinging(alarm: Alarm?, ringId: String, labels: List<String>, alertAgain: Boolean): Boolean {
        ensureChannels()
        if (!notifyCanAlert()) return false
        val notification = builder(NOTIFY_CHANNEL, labels, AlarmIntents.showPendingIntent(context))
            .addAction(stopAction(ringId))
            .addAction(snoozeAction())
            .build()
        val up = notifyUp[ringId]
        val first = notifyNotificationId(ringId)
        val id = when {
            up == null -> first
            !alertAgain -> up
            up == first -> notifyAgainNotificationId(ringId)
            else -> first
        }
        manager.notify(id, notification)
        notifyUp[ringId] = id
        onRinging(ringId, id, notification)
        if (up != null && up != id) manager.cancel(up)
        return true
    }

    /**
     * Whether a post to the Notify channel would be seen and heard; notify() doesn't throw when it
     * wouldn't. Asks the app-level state (the POST_NOTIFICATIONS grant, and areNotificationsEnabled:
     * "whether notifications from the calling package are enabled") and the channel as the user left
     * it: below IMPORTANCE_DEFAULT ("makes noise") it is silent at best (IMPORTANCE_LOW "is not
     * audibly intrusive", the system's Silent setting; IMPORTANCE_NONE "does not show in the shade"),
     * and a channel whose sound was set to none makes none either.
     * https://developer.android.com/reference/android/app/NotificationManager#areNotificationsEnabled()
     * https://developer.android.com/reference/android/app/NotificationChannel#getImportance()
     * https://developer.android.com/reference/android/app/NotificationChannel#getSound()
     */
    private fun notifyCanAlert(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(NOTIFY_CHANNEL) ?: return false
        return channel.importance >= NotificationManager.IMPORTANCE_DEFAULT && channel.sound != null
    }

    /** [fullScreen] is false for a relabel: an update of the notification already up must not launch the screen again. */
    private fun post(labels: List<String>, ringId: String, withActions: Boolean, fullScreen: Boolean = true) {
        ensureChannels()
        val activity = AlarmIntents.ringingActivityPendingIntent(context)
        // A fresh post (new id, IMPORTANCE_HIGH channel) is what launches the full-screen intent; the
        // setFullScreenIntent docs describe it as "an intent to launch instead of posting the
        // notification to the status bar", and an update of an existing notification isn't guaranteed to
        // trigger it, or not to. So this is posted only once the session has decided to ring, never at
        // service start, and a relabel leaves it out.
        // https://developer.android.com/reference/android/app/Notification.Builder#setFullScreenIntent(android.app.PendingIntent,%20boolean)
        val notification = builder(ALARM_CHANNEL, labels, activity)
            .apply { if (fullScreen) setFullScreenIntent(activity, true) }
            .apply { if (withActions) { addAction(stopAction(ringId)); addAction(snoozeAction()) } }
            .build()
        val id = ringingNotificationId(ringId)
        manager.notify(id, notification)
        onRinging(ringId, id, notification)
    }

    /**
     * What both ringing surfaces share; only-alert-once so the foreground re-post doesn't alert again.
     * Several labels are joined on the one collapsed line and listed one per line when expanded.
     * https://developer.android.com/reference/android/app/Notification.BigTextStyle
     */
    private fun builder(channel: String, labels: List<String>, contentIntent: android.app.PendingIntent): Notification.Builder {
        val shown = labels.filter { it.isNotBlank() }
        return Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .apply {
                when (shown.size) {
                    0 -> Unit
                    1 -> setContentText(shown.single())
                    else -> {
                        setContentText(shown.joinToString(context.getString(R.string.alarm_label_separator)))
                        setStyle(Notification.BigTextStyle().bigText(shown.joinToString("\n")))
                    }
                }
            }
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
    }

    private fun stopAction(ringId: String) =
        Notification.Action.Builder(null, context.getString(R.string.alarm_action_stop), AlarmIntents.dismissPendingIntent(context, ringId)).build()

    private fun snoozeAction() =
        Notification.Action.Builder(null, context.getString(R.string.alarm_action_snooze), AlarmIntents.snoozeActionPendingIntent(context)).build()

    override fun showSnoozed(ringId: String, until: Instant, label: String?) {
        ensureChannels()
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(until.atZone(ZoneId.systemDefault()))
        val dismiss = Notification.Action.Builder(null, context.getString(R.string.alarm_action_dismiss), AlarmIntents.dismissPendingIntent(context, ringId)).build()
        val notification = Notification.Builder(context, SNOOZE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.alarm_notification_title))
            .setContentText(context.getString(R.string.alarm_snoozed_until, time))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(dismiss)
            .build()
        manager.notify(ringId, SNOOZED_NOTIFICATION_ID, notification)
    }

    override fun cancelSnoozed(ringId: String) = manager.cancel(ringId, SNOOZED_NOTIFICATION_ID)

    companion object {
        const val ALARM_CHANNEL = "alarm"
        const val NOTIFY_CHANNEL = "alarm_notify"
        const val SNOOZE_CHANNEL = "snooze"
        const val PREP_CHANNEL = "alarm_prep"
        const val PREP_NOTIFICATION_ID = 0x524E47 // "RNG"
        /** Snoozed notifications are tagged with their ringId; the (tag, id) pair is per ring. */
        const val SNOOZED_NOTIFICATION_ID = 0x534E5A

        /** Stable per-ring id that never collides with the prep or snoozed ids. */
        fun ringingNotificationId(ringId: String): Int = avoidReserved(ringId.hashCode())

        /** The Notify surface's id for [ringId]; never its ringing id, so an upgrade is a fresh post. */
        fun notifyNotificationId(ringId: String): Int {
            val ringing = ringingNotificationId(ringId)
            var id = avoidReserved("$ringId/notify".hashCode())
            while (id == ringing) id = avoidReserved(id + 1)
            return id
        }

        /** The Notify surface's other id, so a re-alert is a fresh post; never the ringing or the first Notify id. */
        fun notifyAgainNotificationId(ringId: String): Int {
            val taken = setOf(ringingNotificationId(ringId), notifyNotificationId(ringId))
            var id = avoidReserved("$ringId/notify-again".hashCode())
            while (id in taken) id = avoidReserved(id + 1)
            return id
        }

        private fun avoidReserved(start: Int): Int {
            var id = start
            while (id == 0 || id == PREP_NOTIFICATION_ID || id == SNOOZED_NOTIFICATION_ID) id++
            return id
        }
    }
}
