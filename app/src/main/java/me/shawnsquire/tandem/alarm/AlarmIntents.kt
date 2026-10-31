package me.shawnsquire.tandem.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import me.shawnsquire.tandem.MainActivity
import java.time.Instant

/**
 * Single home for the intents, actions and extras shared by the alarm engine.
 *
 * PendingIntent identity ignores extras, so each intent gets a unique data URI
 * (`tandem://alarm/<id>` for fires, `tandem://snooze` for the single snooze slot).
 */
object AlarmIntents {
    const val EXTRA_ALARM_ID = "me.shawnsquire.tandem.extra.ALARM_ID"
    const val EXTRA_OCCURRENCE = "me.shawnsquire.tandem.extra.OCCURRENCE"
    const val EXTRA_RING_ID = "me.shawnsquire.tandem.extra.RING_ID"
    const val EXTRA_IS_SNOOZE = "me.shawnsquire.tandem.extra.IS_SNOOZE"

    const val ACTION_FIRE = "me.shawnsquire.tandem.action.FIRE"
    const val ACTION_SNOOZE_FIRE = "me.shawnsquire.tandem.action.SNOOZE_FIRE"

    private const val SNOOZE_REQUEST_CODE = 0x534E5A // "SNZ"
    private const val SHOW_REQUEST_CODE = 0x53484F // "SHO"

    private fun fireIntent(context: Context, alarmId: String) =
        Intent(ACTION_FIRE, Uri.parse("tandem://alarm/$alarmId"), context, AlarmReceiver::class.java)

    private fun snoozeIntent(context: Context) =
        Intent(ACTION_SNOOZE_FIRE, Uri.parse("tandem://snooze"), context, AlarmReceiver::class.java)

    fun firePendingIntent(context: Context, alarmId: String, occurrence: Instant): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            fireIntent(context, alarmId)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_OCCURRENCE, occurrence.toEpochMilli()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun existingFirePendingIntent(context: Context, alarmId: String): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            fireIntent(context, alarmId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

    fun snoozePendingIntent(context: Context, alarmId: String, ringId: String, at: Instant): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            SNOOZE_REQUEST_CODE,
            snoozeIntent(context)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_RING_ID, ringId)
                .putExtra(EXTRA_IS_SNOOZE, true)
                .putExtra(EXTRA_OCCURRENCE, at.toEpochMilli()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun existingSnoozePendingIntent(context: Context): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            SNOOZE_REQUEST_CODE,
            snoozeIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

    fun showPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
