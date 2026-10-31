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

/** Notification-manager implementation of [RingingNotifier], plus the foreground notification. */
class AndroidRingingNotifier(private val context: Context) : RingingNotifier {
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
        manager.createNotificationChannels(listOf(alarm, snooze))
    }

    /** Initial notification for `startForeground`: high priority, full-screen intent to the ringing UI. */
    fun foregroundNotification(): Notification {
        ensureChannels()
        return builder(label = null).build()
    }

    private fun builder(label: String?): Notification.Builder {
        val activity = AlarmIntents.ringingActivityPendingIntent(context)
        return Notification.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .apply { if (!label.isNullOrBlank()) setContentText(label) }
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activity)
            .setFullScreenIntent(activity, true)
    }

    override fun showRinging(alarm: Alarm?, ringId: String) {
        ensureChannels()
        val stop = Notification.Action.Builder(null, context.getString(R.string.alarm_action_stop), AlarmIntents.dismissPendingIntent(context, ringId)).build()
        val snooze = Notification.Action.Builder(null, context.getString(R.string.alarm_action_snooze), AlarmIntents.snoozeActionPendingIntent(context)).build()
        val notification = builder(alarm?.label).addAction(stop).addAction(snooze).build()
        manager.notify(RingingService.NOTIFICATION_ID, notification)
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
        manager.notify(SNOOZED_NOTIFICATION_ID, notification)
    }

    override fun cancelSnoozed() = manager.cancel(SNOOZED_NOTIFICATION_ID)

    companion object {
        const val ALARM_CHANNEL = "alarm"
        const val SNOOZE_CHANNEL = "snooze"
        const val SNOOZED_NOTIFICATION_ID = 0x534E5A
    }
}
