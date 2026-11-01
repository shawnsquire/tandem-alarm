package me.shawnsquire.tandem.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.TandemApp
import java.time.Instant

/**
 * Ring / Skip on a late-ring prompt. Not direct-boot aware, like [InviteActionReceiver]: the container
 * only exists after the first unlock.
 */
class LateRingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as TandemApp).container
        val pending = goAsync()
        container.syncScope.launch {
            try {
                handleLateRingAction(intent, container.lateRingPrompts::answer)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "late ring answer failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RING = "me.shawnsquire.tandem.action.LATE_RING_RING"
        const val ACTION_SKIP = "me.shawnsquire.tandem.action.LATE_RING_SKIP"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_OCCURRENCE_AT = "occurrence_at"
        private const val TAG = "LateRingActionReceiver"
    }
}

/** Passes the answer named by [intent] to [answer]; ignores an unknown action or missing extras. */
internal suspend fun handleLateRingAction(intent: Intent, answer: suspend (String, Instant, Boolean) -> Unit) {
    val alarmId = intent.getStringExtra(LateRingActionReceiver.EXTRA_ALARM_ID) ?: return
    if (!intent.hasExtra(LateRingActionReceiver.EXTRA_OCCURRENCE_AT)) return
    val at = Instant.ofEpochMilli(intent.getLongExtra(LateRingActionReceiver.EXTRA_OCCURRENCE_AT, 0))
    when (intent.action) {
        LateRingActionReceiver.ACTION_RING -> answer(alarmId, at, true)
        LateRingActionReceiver.ACTION_SKIP -> answer(alarmId, at, false)
        else -> Unit
    }
}
