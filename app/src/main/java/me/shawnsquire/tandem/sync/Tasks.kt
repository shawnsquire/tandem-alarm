package me.shawnsquire.tandem.sync

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Suspends until this Play Services [Task] completes (kotlinx-coroutines-play-services is not a dependency). */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    // Direct executor: the default delivers on the main looper, which a blocked main thread would stall.
    addOnCompleteListener(Runnable::run) { t ->
        val e = t.exception
        when {
            e != null -> cont.resumeWithException(e)
            t.isCanceled -> cont.cancel()
            else -> @Suppress("UNCHECKED_CAST") cont.resume(t.result as T)
        }
    }
}
