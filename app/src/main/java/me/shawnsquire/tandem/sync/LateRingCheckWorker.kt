package me.shawnsquire.tandem.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.shawnsquire.tandem.TandemApp
import java.util.concurrent.TimeUnit

/**
 * Decides, a couple of minutes after an edit, whether to ask the owner about a late ring
 * ([LateRingPrompts.check]). Giving up is safe: with no answer the joiner rings.
 */
class LateRingCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val alarmId = inputData.getString(KEY_ALARM_ID) ?: return Result.success()
        return try {
            val app = applicationContext as? TandemApp ?: return Result.success()
            when (app.container.lateRingPrompts.check(alarmId)) {
                LateRingPrompts.CheckResult.DONE -> Result.success()
                LateRingPrompts.CheckResult.RETRY -> retryOrGiveUp()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "late ring check failed for $alarmId", e)
            retryOrGiveUp()
        }
    }

    private fun retryOrGiveUp(): Result = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()

    companion object {
        private const val TAG = "LateRingCheckWorker"
        private const val KEY_ALARM_ID = "alarm_id"
        private const val MAX_ATTEMPTS = 4
        private const val DELAY_MINUTES = 2L

        private fun name(alarmId: String) = "late-ring-check:$alarmId"

        /** Schedules the check after a short delay; REPLACE so the newest edit's check wins. */
        fun enqueue(context: Context, alarmId: String) {
            try {
                val request = OneTimeWorkRequestBuilder<LateRingCheckWorker>()
                    .setInitialDelay(DELAY_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .setInputData(Data.Builder().putString(KEY_ALARM_ID, alarmId).build())
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(name(alarmId), ExistingWorkPolicy.REPLACE, request)
            } catch (e: Throwable) {
                Log.w(TAG, "could not enqueue late ring check for $alarmId", e)
            }
        }

        fun cancel(context: Context, alarmId: String) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(name(alarmId))
            } catch (e: Throwable) {
                Log.w(TAG, "could not cancel late ring check for $alarmId", e)
            }
        }
    }
}
