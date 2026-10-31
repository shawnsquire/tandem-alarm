package me.shawnsquire.tandem.sync

import android.util.Log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.schedule.Command

/**
 * Pulls pending commands from the server just before an alarm fires (I1). The 3 s cap is internal
 * and unconditional; any failure, timeout, or missing household yields false and never throws.
 */
class PreFireCheckImpl(
    private val household: Flow<Household?>,
    private val remote: CommandRemote,
    private val applyPending: suspend (Household, Command) -> Unit,
    /** Invoked after a successful pre-fire pull; must not block (the caller's 3 s budget is spent). */
    private val onSuccess: () -> Unit = {},
) : PreFireCheck {

    override suspend fun pullAndApply(timeout: Duration): Boolean {
        val ok = pull(minOf(timeout, MAX_TIMEOUT))
        if (ok) {
            try {
                onSuccess()
            } catch (e: Throwable) {
                Log.w(TAG, "onSuccess failed", e)
            }
        }
        return ok
    }

    /** Server pull with an explicit budget (the worker uses a longer one). True only if the server read succeeded. */
    internal suspend fun pull(timeout: Duration): Boolean = try {
        withTimeoutOrNull(timeout) {
            val h = household.first() ?: return@withTimeoutOrNull false
            val cmds = remote.fetchPendingFromServer(h.id, h.myUid)
            for (cmd in cmds.sortedBy { it.createdAt }) {
                try {
                    applyPending(h, cmd)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "apply ${cmd.id} failed", e)
                }
            }
            true
        } ?: false
    } catch (e: CancellationException) {
        // Our own timeout is swallowed by withTimeoutOrNull; anything else is the caller's cancellation.
        currentCoroutineContext().ensureActive()
        false
    } catch (e: Throwable) {
        Log.w(TAG, "pre-fire pull failed", e)
        false
    }

    private companion object {
        const val TAG = "PreFireCheck"
        val MAX_TIMEOUT = 3.seconds
    }
}
