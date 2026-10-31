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

/**
 * Re-registers alarms after boot, clock change or timezone change. Never starts a service.
 * LOCKED_BOOT_COMPLETED arrives before the first unlock: the engine then schedules from the
 * device-protected mirror, and BOOT_COMPLETED reschedules again once the user unlocks.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val pending = goAsync()
                val engine = AlarmEngine.get(context)
                rescheduleAsync(
                    pending, engine.scheduler, CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    rearmOverdueSnooze = true, ringSlot = engine.ringSlot,
                )
            }
            else -> Unit
        }
    }
}

internal fun rescheduleAsync(
    pending: BroadcastReceiver.PendingResult?,
    scheduler: AlarmScheduler,
    scope: CoroutineScope,
    rearmOverdueSnooze: Boolean = false,
    ringSlot: RingSlot? = null,
    isLive: (String) -> Boolean = LiveRings::isLive,
): Job = scope.launch {
    try {
        if (ringSlot != null) {
            // A fresh boot has no live session; clear a leftover RingState first. No reschedule of its
            // own: the one below must still see the previous records to replay missed alarms.
            try {
                ringSlot.clearStale(isLive, reschedule = false)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e("BootReceiver", "clearStale failed", e)
            }
        }
        if (rearmOverdueSnooze && scheduler is AlarmSchedulerImpl) {
            scheduler.rescheduleAll(rearmOverdueSnooze = true)
        } else {
            scheduler.rescheduleAll()
        }
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Log.e("BootReceiver", "rescheduleAll failed", e)
    } finally {
        pending?.finish()
    }
}
