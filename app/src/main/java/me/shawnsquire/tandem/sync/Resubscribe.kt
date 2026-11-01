package me.shawnsquire.tandem.sync

import android.util.Log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/**
 * Keeps a listener flow alive across errors. When the upstream fails (a Firestore listener is dead after
 * its first error), emits [onError] once, waits, and subscribes again; the wait doubles from [initial]
 * up to [max] and resets only after a subscription stayed up for the current wait. A value alone doesn't
 * reset it: a listener raises a cached snapshot before the server rejects the listen, so a denied doc
 * would otherwise retry every [initial] forever. [onError] is only a stand-in value for the failure window:
 * it must never be read as proof that something is gone (see [householdOnServer]). Ends only when the
 * upstream completes normally or the collector is cancelled.
 */
internal fun <T> Flow<T>.resubscribeOnError(
    onError: T,
    initial: Duration = 2.seconds,
    max: Duration = 60.seconds,
): Flow<T> = flow {
    var wait = initial
    while (true) {
        var failed = false
        var stayedUp = false
        coroutineScope {
            val timer = launch { delay(wait); stayedUp = true }
            catch { e -> failed = true; Log.w(TAG, "listener failed; resubscribing", e) }
                .collect { emit(it) }
            timer.cancel()
        }
        if (!failed) return@flow
        if (stayedUp) wait = initial
        emit(onError)
        delay(wait)
        wait = (wait * 2).coerceAtMost(max)
    }
}

private const val TAG = "Resubscribe"
