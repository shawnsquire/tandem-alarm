package me.shawnsquire.tandem.sync

import android.util.Log
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles one FCM data message: records delivery, parses the command and applies it within [budget].
 * If applying times out or fails, [fallback] (an expedited pull) re-fetches pending commands from the server.
 * Never throws except for cancellation.
 */
class FcmCommandHandler(
    private val applier: CommandApplier,
    private val recordFcm: suspend (Instant) -> Unit,
    private val clock: Clock,
    private val fallback: () -> Unit,
    private val budget: Duration = DEFAULT_BUDGET,
) {
    suspend fun handle(data: Map<String, String>) {
        try {
            recordFcm(clock.instant())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "could not record FCM delivery", e)
        }
        val cmd = commandFromFcm(data)
        if (cmd == null) {
            Log.w(TAG, "ignoring malformed FCM payload")
            return
        }
        val completed = try {
            withTimeoutOrNull(budget) { applier.apply(cmd) } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "applying command failed", e)
            false
        }
        if (!completed) {
            Log.w(TAG, "command not applied in time; falling back to pull")
            try {
                fallback()
            } catch (e: Throwable) {
                Log.w(TAG, "fallback failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "FcmCommandHandler"

        /** FirebaseMessagingService.onMessageReceived should complete within 20 s; stay well under. */
        val DEFAULT_BUDGET: Duration = 10.seconds
    }
}
