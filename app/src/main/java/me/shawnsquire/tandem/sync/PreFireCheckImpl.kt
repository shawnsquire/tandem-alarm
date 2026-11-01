package me.shawnsquire.tandem.sync

import android.util.Log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.schedule.Command

/**
 * Pulls pending commands from the server just before an alarm fires (I1). The 3 s cap is internal
 * and unconditional; any failure, timeout, or missing household yields false and never throws.
 *
 * For a linked row the owner's doc is read with `Source.SERVER` concurrently with the pull, under
 * the same cap, and applied through [LinkSync] whenever the read succeeded. This class adds no
 * suppression logic: LinkSync copies hour, minute, days, enabled and the shared skip (never the
 * owner's solo skip or `nextOccurrenceAt`), and the caller's re-read of the row decides whether X
 * still rings.
 */
class PreFireCheckImpl(
    private val household: Flow<Household?>,
    private val remote: CommandRemote,
    private val applyPending: suspend (Household, Command) -> Unit,
    /** Reads the owner's doc with `Source.SERVER` for a linked row. */
    private val partnerRepository: PartnerRepository,
    /** Applies the owner doc it read to the linked rows. */
    private val linkSync: LinkSync,
    /** Invoked after a successful pre-fire pull; must not block (the caller's 3 s budget is spent). */
    private val onSuccess: () -> Unit = {},
) : PreFireCheck {

    override suspend fun pullAndApply(timeout: Duration, linkedOwnerAlarmId: String?): Boolean {
        val cap = minOf(timeout, MAX_TIMEOUT)
        val ok = if (linkedOwnerAlarmId == null) pull(cap) else pullWithOwnerDoc(cap)
        if (ok) {
            try {
                onSuccess()
            } catch (e: Throwable) {
                Log.w(TAG, "onSuccess failed", e)
            }
        }
        return ok
    }

    /** Command pull and owner-doc read in parallel under one cap; true only if both and the apply succeeded. */
    private suspend fun pullWithOwnerDoc(cap: Duration): Boolean = try {
        withTimeoutOrNull(cap) {
            coroutineScope {
                // A failed pull must not cancel the read: a successful owner read is still applied.
                val pulled = async {
                    try {
                        pullBody()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "pre-fire pull failed", e)
                        false
                    }
                }
                val read = async { partnerRepository.readFromServer() }
                val owner = read.await()
                var applied = false
                if (owner != null) {
                    // LinkSync runs the pass in its own scope, so the cap can't tear a half-applied pass.
                    linkSync.apply(owner)
                    applied = true
                }
                pulled.await() && applied
            }
        } ?: false
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        false
    } catch (e: Throwable) {
        Log.w(TAG, "pre-fire owner check failed", e)
        false
    }

    /** Server pull with an explicit budget (the worker uses a longer one). True only if the server read succeeded. */
    internal suspend fun pull(timeout: Duration): Boolean = try {
        withTimeoutOrNull(timeout) { pullBody() } ?: false
    } catch (e: CancellationException) {
        // Our own timeout is swallowed by withTimeoutOrNull; anything else is the caller's cancellation.
        currentCoroutineContext().ensureActive()
        false
    } catch (e: Throwable) {
        Log.w(TAG, "pre-fire pull failed", e)
        false
    }

    /** The pull itself: false on an unknown household, a thrown fetch propagates to the caller's catch. */
    private suspend fun pullBody(): Boolean {
        val h = household.first() ?: return false
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
        return true
    }

    private companion object {
        const val TAG = "PreFireCheck"
        val MAX_TIMEOUT = 3.seconds
    }
}
