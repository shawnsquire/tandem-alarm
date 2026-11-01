package me.shawnsquire.tandem.media

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.TandemApp
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** Fetches the owner's media that [LinkedMediaCacheImpl] has refs for but no file yet. */
class LinkedMediaDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            val app = applicationContext as? TandemApp ?: return Result.success()
            val c = app.container
            val cache = c.linkedMediaCache as? LinkedMediaCacheImpl ?: return Result.success()
            // Blank means unpaired: nothing to fetch. No answer in time: try again later.
            val hid = c.household.value?.id
                ?: withTimeoutOrNull(HOUSEHOLD_WAIT) { c.householdFlow.first()?.id.orEmpty() }
                ?: return Result.retry()
            if (hid.isEmpty()) return Result.success()
            return if (cache.downloadMissing(hid)) Result.success() else Result.retry()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "linked media download failed", e)
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "LinkedMediaWorker"
        private const val NAME = "linked-media-download"
        private val HOUSEHOLD_WAIT = 10.seconds

        /** Enqueues the one-off download, network required; KEEP because the running worker re-reads the refs until none are missing. */
        fun enqueue(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<LinkedMediaDownloadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
            } catch (e: Throwable) {
                Log.w(TAG, "could not enqueue linked media download", e)
            }
        }
    }
}
