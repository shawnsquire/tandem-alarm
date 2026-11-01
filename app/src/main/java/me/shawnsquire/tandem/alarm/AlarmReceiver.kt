package me.shawnsquire.tandem.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.schedule.RingOrigin

/**
 * Entry point for exact-alarm broadcasts and notification actions. Fires do no work here: they
 * go straight to [RingingService] as a foreground service (an allowed start from a
 * `setAlarmClock` broadcast). Dismiss/Snooze actions run on the process [AlarmEngine]'s
 * controller, which works before the first unlock.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmIntents.ACTION_FIRE, AlarmIntents.ACTION_SNOOZE_FIRE -> {
                // Held across the receiver-to-service gap; the service releases it once it has its own.
                FireWakeLock.acquire(context)
                context.startForegroundService(
                    Intent(context, RingingService::class.java)
                        .setAction(intent.action)
                        .putExtras(intent),
                )
            }
            AlarmIntents.ACTION_DISMISS -> {
                val ringId = intent.getStringExtra(AlarmIntents.EXTRA_RING_ID) ?: return
                val pending = goAsync()
                val controller = AlarmEngine.get(context).controller
                controlAsync(pending, CoroutineScope(SupervisorJob() + Dispatchers.Default)) { controller.dismiss(ringId, RingOrigin.LOCAL) }
            }
            AlarmIntents.ACTION_SNOOZE -> {
                val pending = goAsync()
                val controller = AlarmEngine.get(context).controller
                controlAsync(pending, CoroutineScope(SupervisorJob() + Dispatchers.Default)) { controller.snooze(RingOrigin.LOCAL) }
            }
            else -> Unit
        }
    }
}

internal fun controlAsync(
    pending: BroadcastReceiver.PendingResult?,
    scope: CoroutineScope,
    action: suspend () -> Unit,
): Job = scope.launch {
    try {
        action()
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Log.e("AlarmReceiver", "notification action failed", e)
    } finally {
        pending?.finish()
    }
}
