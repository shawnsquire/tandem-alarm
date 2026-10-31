package me.shawnsquire.tandem

import android.util.Log

/**
 * Runs [fullStartup] once per process, as soon as the user is unlocked: at [start] if already
 * unlocked, otherwise when the unlock receiver fires or [ensureStarted] sees the user unlocked first.
 *
 * A throwing [fullStartup] is only logged, never rethrown: a rethrow from the unlock receiver or a
 * component would kill the process, taking down a ring that began before unlock. It is not marked
 * as done either, so the next [ensureStarted] retries. That is safe because [fullStartup] can only
 * throw before it publishes the container; everything after that catches its own failures, so no
 * listener is ever started twice.
 *
 * Callers on other threads wait for a running attempt, so none returns before the container is
 * set. A re-entrant call from inside [fullStartup] returns at once instead of running it again.
 */
class TandemStartup(
    private val isUnlocked: () -> Boolean,
    private val registerUnlockReceiver: (onUnlocked: () -> Unit) -> Unit,
    private val fullStartup: () -> Unit,
    // runCatching: Log is unavailable in plain JVM tests.
    private val log: (String, Throwable) -> Unit = { message, e -> runCatching { Log.e(TAG, message, e) } },
) {
    private val lock = Any()
    @Volatile private var succeeded = false
    private var inProgress = false // guarded by lock; only its own thread can see it true

    fun start() {
        if (isUnlocked()) {
            runOnce()
            return
        }
        // Register before re-checking: an unlock between the first check and the registration
        // sends its broadcast to nobody, so only the re-check would catch it.
        registerUnlockReceiver { runOnce() }
        if (isUnlocked()) runOnce()
    }

    fun ensureStarted() {
        if (!succeeded && isUnlocked()) runOnce()
    }

    private fun runOnce() {
        synchronized(lock) {
            if (succeeded || inProgress) return
            inProgress = true
            try {
                fullStartup()
                succeeded = true
            } catch (e: Throwable) {
                log("full startup failed; the next ensureStarted retries", e)
            } finally {
                inProgress = false
            }
        }
    }

    private companion object {
        const val TAG = "TandemStartup"
    }
}
