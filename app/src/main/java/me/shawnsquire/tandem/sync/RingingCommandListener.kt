package me.shawnsquire.tandem.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.RingState

/**
 * While a ring exists (state present, sounding OR snoozed), keeps a live listener on pending commands
 * to this device so a partner's STOP_RINGING lands instantly. Detaches only when the ring ends.
 *
 * Stays attached through a snooze on both phones: crossing snoozes (both phones snooze within
 * seconds; validation rule 4 settles on the later T) and a partner's STOP during a snooze must land
 * within seconds rather than at the next FCM or pull, and the owner needs it too because the
 * joiner's SNOOZE/STOP target the owner's ring. The cost is one Firestore listener for the snooze
 * length. Parked snoozes (SnoozeLedger entries with no RingState) are not covered and still rely on
 * FCM and the pull.
 */
class RingingCommandListener(
    private val ringState: Flow<RingState?>,
    private val household: Flow<Household?>,
    private val remote: CommandRemote,
    private val applyPending: suspend (Household, Command) -> Unit,
    private val scope: CoroutineScope,
) {
    private var started = false

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // A failure upstream must not end the listener for good: resubscribe (retryWhen never retries cancellation).
            ringState
                .map { it != null }
                .distinctUntilChanged()
                .flatMapLatest { active ->
                    if (!active) {
                        emptyFlow()
                    } else {
                        household.flatMapLatest { h ->
                            if (h == null) {
                                emptyFlow()
                            } else {
                                // pendingUpdates completes quietly on a snapshot error; reattach while still active.
                                flow {
                                    while (true) {
                                        emitAll(remote.pendingUpdates(h.id, h.myUid).map { h to it })
                                        delay(RETRY_DELAY_MS)
                                    }
                                }
                            }
                        }
                    }
                }
                .retryWhen { cause, _ ->
                    Log.w(TAG, "ringing listener failed; resubscribing", cause)
                    delay(RETRY_DELAY_MS)
                    true
                }
                .collect { (h, cmds) ->
                    for (cmd in cmds.sortedBy { it.createdAt }) {
                        try {
                            applyPending(h, cmd)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Log.w(TAG, "apply ${cmd.id} failed", e)
                        }
                    }
                }
        }
    }

    private companion object {
        const val TAG = "RingingListener"
        const val RETRY_DELAY_MS = 1_000L
    }
}
