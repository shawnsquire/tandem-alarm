package me.shawnsquire.tandem.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Entry point for exact-alarm broadcasts. Does no work itself: it hands the fire straight to
 * [RingingService] as a foreground service (an allowed start from a `setAlarmClock` broadcast).
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmIntents.ACTION_FIRE, AlarmIntents.ACTION_SNOOZE_FIRE ->
                context.startForegroundService(
                    Intent(context, RingingService::class.java)
                        .setAction(intent.action)
                        .putExtras(intent),
                )
            else -> Unit
        }
    }
}
