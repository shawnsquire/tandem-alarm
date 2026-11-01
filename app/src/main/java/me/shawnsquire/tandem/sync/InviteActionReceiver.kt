package me.shawnsquire.tandem.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.TandemApp

/**
 * Join / Decline on an invite notification. Not direct-boot aware: the container (and Firebase)
 * only exist after the first unlock, and an invite can't be answered before then.
 */
class InviteActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val commandId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: return
        val container = (context.applicationContext as TandemApp).container
        val pending = goAsync()
        container.syncScope.launch {
            try {
                handleInviteAction(container.linkRepository, intent)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "invite action failed for $commandId", e)
            } finally {
                // Only a "deleted this alarm" failure rejects the command; other failures leave it pending and
                // it is re-posted on re-delivery, so the notification is dropped either way.
                AndroidCommandNotifier.cancelInvite(context, commandId)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_JOIN = "me.shawnsquire.tandem.action.INVITE_JOIN"
        const val ACTION_DECLINE = "me.shawnsquire.tandem.action.INVITE_DECLINE"
        const val EXTRA_COMMAND_ID = "command_id"
        const val EXTRA_OWNER_ALARM_ID = "owner_alarm_id"
        private const val TAG = "InviteActionReceiver"
    }
}

/** Answers the invite named by [intent] through [repo]; ignores an unknown action or missing extras. */
internal suspend fun handleInviteAction(repo: LinkRepository, intent: Intent) {
    val commandId = intent.getStringExtra(InviteActionReceiver.EXTRA_COMMAND_ID) ?: return
    when (intent.action) {
        InviteActionReceiver.ACTION_JOIN -> {
            val ownerAlarmId = intent.getStringExtra(InviteActionReceiver.EXTRA_OWNER_ALARM_ID) ?: return
            repo.acceptInvite(commandId, ownerAlarmId)
        }
        InviteActionReceiver.ACTION_DECLINE -> repo.declineInvite(commandId)
        else -> Unit
    }
}
