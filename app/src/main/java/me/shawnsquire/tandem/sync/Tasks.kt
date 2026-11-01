package me.shawnsquire.tandem.sync

import com.google.android.gms.tasks.Task
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Suspends until this Play Services [Task] completes (kotlinx-coroutines-play-services is not a dependency).
 *
 * Cancelling the caller does not cancel the Task: that needs a CancellationToken at creation, which the
 * Firestore calls used here don't take, and listeners can't be removed. Instead the listener's only
 * reference to the coroutine is dropped on cancellation, so the pending Task doesn't keep it alive.
 */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    val ref = AtomicReference<CancellableContinuation<T>?>(cont)
    cont.invokeOnCancellation { ref.set(null) }
    // Direct executor: the default delivers on the main looper, which a blocked main thread would stall.
    addOnCompleteListener(Runnable::run) { t ->
        val c = ref.getAndSet(null) ?: return@addOnCompleteListener
        val e = t.exception
        when {
            e != null -> c.resumeWithException(e)
            t.isCanceled -> c.cancel()
            else -> @Suppress("UNCHECKED_CAST") c.resume(t.result as T)
        }
    }
}
