package me.shawnsquire.tandem.alarm

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Short process-wide partial wake lock that bridges the gap between the receiver returning and
 * [RingingService] holding its own lock. Not reference counted; the 30 s timeout is a backstop.
 */
object FireWakeLock {
    private const val TAG = "FireWakeLock"
    const val LOCK_TAG = "tandem:fire"
    const val TIMEOUT_MS = 30_000L

    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        try {
            val l = lock ?: context.applicationContext.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)
                .also { it.setReferenceCounted(false); lock = it }
            l.acquire(TIMEOUT_MS)
        } catch (e: Throwable) {
            Log.e(TAG, "could not acquire fire wake lock", e)
        }
    }

    @Synchronized
    fun release() {
        try {
            lock?.takeIf { it.isHeld }?.release()
        } catch (e: Throwable) {
            Log.e(TAG, "could not release fire wake lock", e)
        }
    }

    /** Tests only: drop the cached lock, which belongs to a previous test's PowerManager. */
    @Synchronized
    internal fun resetForTest() {
        lock = null
    }
}
