package me.shawnsquire.tandem.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.schedule.Alarm
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Notification-manager implementation of [RingingNotifier], plus the quiet prep notification.
 * [onRinging] is called after a ringing notification is posted so the service can promote it.
 */
class AndroidRingingNotifier(
    private val context: Context,
    private val onRinging: (ringId: String, id: Int, notification: Notification) -> Unit = { _, _, _ -> },
) : RingingNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val alarm = NotificationChannel(ALARM_CHANNEL, context.getString(R.string.alarm_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.alarm_channel_description)
            setSound(null, null) // MediaPlayer plays the ringtone
            enableVibration(false) // the vibration adapter vibrates
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
        manager.createNotificationChannels(listOf(alarm, snooze, prep))
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

    fun cancelRinging(ringId: String) = manager.cancel(ringingNotificationId(ringId))

    override fun showRinging(alarm: Alarm?, ringId: String) {
        ensureChannels()
        val activity = AlarmIntents.ringingActivityPendingIntent(context)
        val stop = Notification.Action.Builder(null, context.getString(R.string.alarm_action_stop), AlarmIntents.dismissPendingIntent(context, ringId)).build()
        val snooze = Notification.Action.Builder(null, context.getString(R.string.alarm_action_snooze), AlarmIntents.snoozeActionPendingIntent(context)).build()
        val label = alarm?.label
        // A fresh post (new id, IMPORTANCE_HIGH channel) is what launches the full-screen intent; the
        // setFullScreenIntent docs describe it as "an intent to launch instead of posting the
        // notification to the status bar", and an update of an existing notification isn't guaranteed to
        // trigger it. So this is posted only once the session has decided to ring, never at service start.
        // https://developer.android.com/reference/android/app/Notification.Builder#setFullScreenIntent(android.app.PendingIntent,%20boolean)
        val notification = Notification.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .apply { if (!label.isNullOrBlank()) setContentText(label) }
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activity)
            .setFullScreenIntent(activity, true)
            .addAction(stop)
            .addAction(snooze)
            .build()
        val id = ringingNotificationId(ringId)
        manager.notify(id, notification)
        onRinging(ringId, id, notification)
    }

    override fun showSnoozed(ringId: String, until: Instant) {
        ensureChannels()
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(until.atZone(ZoneId.systemDefault()))
        val dismiss = Notification.Action.Builder(null, context.getString(R.string.alarm_action_dismiss), AlarmIntents.dismissPendingIntent(context, ringId)).build()
        val notification = Notification.Builder(context, SNOOZE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
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
        const val SNOOZE_CHANNEL = "snooze"
        const val PREP_CHANNEL = "alarm_prep"
        const val PREP_NOTIFICATION_ID = 0x524E47 // "RNG"
        /** Snoozed notifications are tagged with their ringId; the (tag, id) pair is per ring. */
        const val SNOOZED_NOTIFICATION_ID = 0x534E5A

        /** Stable per-ring id that never collides with the prep or snoozed ids. */
        fun ringingNotificationId(ringId: String): Int {
            var id = ringId.hashCode()
            while (id == 0 || id == PREP_NOTIFICATION_ID || id == SNOOZED_NOTIFICATION_ID) id++
            return id
        }
    }
}
