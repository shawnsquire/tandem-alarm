package me.shawnsquire.tandem.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.TandemApp

/** Re-registers alarms after boot, clock change or timezone change. Never starts a service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val pending = goAsync()
                val scheduler = (context.applicationContext as TandemApp).container.alarmScheduler
                rescheduleAsync(pending, scheduler, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            }
            else -> Unit
        }
    }
}

internal fun rescheduleAsync(
    pending: BroadcastReceiver.PendingResult?,
    scheduler: AlarmScheduler,
    scope: CoroutineScope,
): Job = scope.launch {
    try {
        scheduler.rescheduleAll()
    } catch (e: Exception) {
        Log.e("BootReceiver", "rescheduleAll failed", e)
    } finally {
        pending?.finish()
    }
}
