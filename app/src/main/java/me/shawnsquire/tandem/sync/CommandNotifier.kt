package me.shawnsquire.tandem.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandType
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Tells the owner that the partner changed one of their alarms. */
interface CommandNotifier {
    /** [turnedOff] is true when a skip on a one-shot alarm turned it off instead. */
    fun notifyApplied(cmd: Command, alarm: Alarm?, partnerName: String, turnedOff: Boolean)

    /** INVITE_LINK from the owner: Join / Decline. [alarm] is the owner's published alarm, if known. */
    fun notifyInvite(cmd: Command, alarm: PartnerAlarm?, partnerName: String)

    /** The owner deleted a joined alarm; [alarm] is the removed linked row. */
    fun notifyOwnerDeleted(alarm: Alarm, partnerName: String)

    /** The owner turned off a joined alarm ("so it's off for you too"); [alarm] is the linked row. */
    fun notifyOwnerTurnedOff(alarm: Alarm, partnerName: String)

    /** A "both of us" command for linked row [alarm] was rejected. */
    fun notifyRejected(alarm: Alarm, type: CommandType, reason: String?, partnerName: String)
}

class AndroidCommandNotifier(private val context: Context) : CommandNotifier {
    override fun notifyApplied(cmd: Command, alarm: Alarm?, partnerName: String, turnedOff: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val time = alarm?.let {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(LocalTime.of(it.hour, it.minute))
        }.orEmpty()
        val text = when {
            cmd.type == CommandType.STOP_RINGING -> context.getString(R.string.partner_cmd_stopped, partnerName)
            turnedOff || cmd.type == CommandType.DISABLE ->
                context.getString(R.string.partner_cmd_turned_off, partnerName, time)
            cmd.type == CommandType.SKIP_NEXT -> context.getString(R.string.partner_cmd_skipped, partnerName, time)
            cmd.type == CommandType.UNSKIP -> context.getString(R.string.partner_cmd_unskipped, partnerName, time)
            else -> context.getString(R.string.partner_cmd_turned_on, partnerName, time)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val tap = launch?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(cmd.id.hashCode(), notification)
    }

    // L6 writes the texts.
    override fun notifyInvite(cmd: Command, alarm: PartnerAlarm?, partnerName: String) = Unit

    override fun notifyOwnerDeleted(alarm: Alarm, partnerName: String) = Unit

    override fun notifyOwnerTurnedOff(alarm: Alarm, partnerName: String) = Unit

    override fun notifyRejected(alarm: Alarm, type: CommandType, reason: String?, partnerName: String) = Unit

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.partner_commands_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "partner_commands"
    }
}
