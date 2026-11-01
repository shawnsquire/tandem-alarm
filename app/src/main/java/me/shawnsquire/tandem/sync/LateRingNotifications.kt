package me.shawnsquire.tandem.sync

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.shawnsquire.tandem.R
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Shows the late-ring question on the partner-commands channel, with Ring / Skip actions. */
class AndroidLateRingNotifier(
    private val context: Context,
    private val clock: java.time.Clock = LiveZoneClock,
) : LateRingNotifier {
    override fun post(prompt: LateRingPrompt) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            // The old time passing ends the question; the system removes the notification then.
            val remaining = prompt.occurrenceAt.toEpochMilli() - clock.millis()
            if (remaining <= 0) return
            AndroidCommandNotifier.ensureChannel(context)
            val text = context.getString(
                R.string.link_notice_late_ring, prompt.partnerName, time(prompt.movedTo), occurrence(prompt.occurrenceAt),
            )
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val tap = launch?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val ring = action(
                context.getString(R.string.link_late_ring_ring, occurrence(prompt.occurrenceAt)),
                LateRingActionReceiver.ACTION_RING, prompt,
            )
            val skip = action(context.getString(R.string.link_late_ring_skip), LateRingActionReceiver.ACTION_SKIP, prompt)
            val notification = NotificationCompat.Builder(context, AndroidCommandNotifier.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(remaining)
                .addAction(ring)
                .addAction(skip)
                .build()
            NotificationManagerCompat.from(context).notify(tag(prompt.alarmId), NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "could not post late ring prompt for ${prompt.alarmId}", e)
        }
    }

    override fun cancel(alarmId: String) {
        try {
            NotificationManagerCompat.from(context).cancel(tag(alarmId), NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "could not cancel late ring prompt for $alarmId", e)
        }
    }

    override fun posted(): Set<String> = try {
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .mapNotNull { it.tag?.takeIf { tag -> tag.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX) }
            .toSet()
    } catch (e: Exception) {
        Log.w(TAG, "could not list late ring prompts", e)
        emptySet()
    }

    private fun time(at: Instant): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at.atZone(clock.zone))

    /** The occurrence time, with a short day name ("Tue 6:30") when it isn't today. */
    private fun occurrence(at: Instant): String {
        val local = at.atZone(clock.zone)
        if (local.toLocalDate() == clock.instant().atZone(clock.zone).toLocalDate()) return time(at)
        return "${DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(local)} ${time(at)}"
    }

    private fun action(title: String, action: String, prompt: LateRingPrompt): NotificationCompat.Action {
        val intent = Intent(context, LateRingActionReceiver::class.java)
            .setAction(action)
            .putExtra(LateRingActionReceiver.EXTRA_ALARM_ID, prompt.alarmId)
            .putExtra(LateRingActionReceiver.EXTRA_OCCURRENCE_AT, prompt.occurrenceAt.toEpochMilli())
        // Extras don't distinguish PendingIntents, so each alarm and action gets its own request code.
        val pending = PendingIntent.getBroadcast(
            context, "$action:${prompt.alarmId}".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, title, pending).build()
    }

    private companion object {
        const val TAG = "LateRingNotifier"
        const val TAG_PREFIX = "late-ring:"
        const val NOTIFICATION_ID = 1

        fun tag(alarmId: String) = "$TAG_PREFIX$alarmId"
    }
}
