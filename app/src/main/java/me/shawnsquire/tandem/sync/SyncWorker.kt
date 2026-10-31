package me.shawnsquire.tandem.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import me.shawnsquire.tandem.TandemApp

/** Safety net: pulls pending partner commands from the server when FCM was missed. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            val app = applicationContext as? TandemApp ?: return Result.success()
            val c = app.container
            runSyncOnce(
                pull = { c.preFireCheckImpl.pull(PULL_BUDGET) },
                publishNow = { c.statePublisherImpl.publishNow() },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "sync pull failed", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val PERIODIC_NAME = "sync-pull"
        private const val NOW_NAME = "sync-pull-now"
        private val PULL_BUDGET = 30.seconds
        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Enqueues the 15 min periodic pull; no-op if already scheduled or WorkManager is unavailable. */
        fun schedule(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(connected)
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } catch (e: Throwable) {
                Log.w(TAG, "could not schedule periodic sync", e)
            }
        }

        /**
         * Expedited one-off pull (fallback when an FCM message arrives and the process can't act directly).
         * minSdk 33: expedited work runs as a JobScheduler expedited job, so no getForegroundInfo is needed.
         */
        fun runNow(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(connected)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, request)
            } catch (e: Throwable) {
                Log.w(TAG, "could not enqueue immediate sync", e)
            }
        }
    }
}

/**
 * One worker pass: pull pending commands, then heartbeat the device doc (awaiting the ack, capped at
 * 10 s, so the process stays alive long enough for the write). The heartbeat runs even if the pull
 * failed. Only the caller's cancellation propagates.
 */
internal suspend fun runSyncOnce(pull: suspend () -> Boolean, publishNow: suspend () -> Boolean) {
    try {
        pull()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w("SyncWorker", "sync pull failed", e)
    }
    try {
        kotlinx.coroutines.withTimeoutOrNull(HEARTBEAT_BUDGET) { publishNow() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w("SyncWorker", "heartbeat failed", e)
    }
}

private val HEARTBEAT_BUDGET = 10.seconds
