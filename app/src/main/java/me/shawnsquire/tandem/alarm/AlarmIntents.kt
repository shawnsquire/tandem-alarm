package me.shawnsquire.tandem.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import me.shawnsquire.tandem.MainActivity
import java.time.Instant
import java.time.ZoneId

/**
 * Single home for the intents, actions and extras shared by the alarm engine.
 *
 * PendingIntent identity ignores extras, so each intent gets a unique data URI
 * (`tandem://alarm/<id>` for fires, `tandem://alarm/<id>/replay` for missed-alarm replays, `tandem://snooze/<ringId>` for snoozes, one per ring).
 */
object AlarmIntents {
    const val EXTRA_ALARM_ID = "me.shawnsquire.tandem.extra.ALARM_ID"
    const val EXTRA_OCCURRENCE = "me.shawnsquire.tandem.extra.OCCURRENCE"
    const val EXTRA_RING_ID = "me.shawnsquire.tandem.extra.RING_ID"
    const val EXTRA_IS_SNOOZE = "me.shawnsquire.tandem.extra.IS_SNOOZE"
    /** The zone id the scheduler computed EXTRA_OCCURRENCE in; absent on replays and older intents. */
    const val EXTRA_ZONE = "me.shawnsquire.tandem.extra.ZONE"

    const val ACTION_FIRE = "me.shawnsquire.tandem.action.FIRE"
    const val ACTION_SNOOZE_FIRE = "me.shawnsquire.tandem.action.SNOOZE_FIRE"

    const val ACTION_DISMISS = "me.shawnsquire.tandem.action.DISMISS"
    const val ACTION_SNOOZE = "me.shawnsquire.tandem.action.SNOOZE"

    private const val DISMISS_REQUEST_CODE = 0x444953 // "DIS"
    private const val SNOOZE_ACTION_REQUEST_CODE = 0x53415A // "SAZ"
    private const val RINGING_REQUEST_CODE = 0x52494E // "RIN"
    private const val SNOOZE_REQUEST_CODE = 0x534E5A // "SNZ"
    private const val REPLAY_REQUEST_CODE = 0x525059 // "RPY"
    private const val SHOW_REQUEST_CODE = 0x53484F // "SHO"

    private fun fireIntent(context: Context, alarmId: String) =
        Intent(ACTION_FIRE, Uri.parse("tandem://alarm/$alarmId"), context, AlarmReceiver::class.java)

    private fun replayIntent(context: Context, alarmId: String) =
        Intent(ACTION_FIRE, Uri.parse("tandem://alarm/$alarmId/replay"), context, AlarmReceiver::class.java)

    private fun snoozeIntent(context: Context, ringId: String) =
        Intent(ACTION_SNOOZE_FIRE, Uri.parse("tandem://snooze/${Uri.encode(ringId)}"), context, AlarmReceiver::class.java)

    /** [zone] is the one [occurrence] was computed in; null when unknown (a re-arm from the records). */
    fun firePendingIntent(context: Context, alarmId: String, occurrence: Instant, zone: ZoneId? = null): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            fireIntent(context, alarmId)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_OCCURRENCE, occurrence.toEpochMilli())
                .apply { if (zone != null) putExtra(EXTRA_ZONE, zone.id) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** One-off fire of a missed [occurrence]; its own identity so it never replaces the normal fire. */
    fun replayPendingIntent(context: Context, alarmId: String, occurrence: Instant): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REPLAY_REQUEST_CODE,
            replayIntent(context, alarmId)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_OCCURRENCE, occurrence.toEpochMilli()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun existingReplayPendingIntent(context: Context, alarmId: String): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REPLAY_REQUEST_CODE,
            replayIntent(context, alarmId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

    fun existingFirePendingIntent(context: Context, alarmId: String): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            fireIntent(context, alarmId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Snooze fire for [ringId]; the fixed request code is fine because the data URI is per ring. */
    fun snoozePendingIntent(context: Context, alarmId: String, ringId: String, at: Instant): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            SNOOZE_REQUEST_CODE,
            snoozeIntent(context, ringId)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_RING_ID, ringId)
                .putExtra(EXTRA_IS_SNOOZE, true)
                .putExtra(EXTRA_OCCURRENCE, at.toEpochMilli()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun existingSnoozePendingIntent(context: Context, ringId: String): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            SNOOZE_REQUEST_CODE,
            snoozeIntent(context, ringId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

    fun showPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Notification action: dismiss the ring [ringId] (from the ringing or the snoozed notification). */
    fun dismissPendingIntent(context: Context, ringId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            DISMISS_REQUEST_CODE,
            Intent(ACTION_DISMISS, Uri.parse("tandem://dismiss/$ringId"), context, AlarmReceiver::class.java)
                .putExtra(EXTRA_RING_ID, ringId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Notification action: snooze the current ring. */
    fun snoozeActionPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            SNOOZE_ACTION_REQUEST_CODE,
            Intent(ACTION_SNOOZE, Uri.parse("tandem://snooze-action"), context, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun ringingActivityPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            RINGING_REQUEST_CODE,
            Intent(context, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
