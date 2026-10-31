package me.shawnsquire.tandem

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [fullStartup] exactly once per process: at [start] if the user is already unlocked,
 * otherwise when the unlock receiver fires (or [ensureStarted] sees the user unlocked first).
 *
 * If [fullStartup] throws, it stays marked as started and the error is only logged. A retry would
 * double-start whatever listeners the first attempt got to, and a rethrow from the unlock receiver
 * would kill the process, taking down a ring that began before unlock.
 */
class TandemStartup(
    private val isUnlocked: () -> Boolean,
    private val registerUnlockReceiver: (onUnlocked: () -> Unit) -> Unit,
    private val fullStartup: () -> Unit,
    // runCatching: Log is unavailable in plain JVM tests.
    private val log: (String, Throwable) -> Unit = { message, e -> runCatching { Log.e(TAG, message, e) } },
) {
    private val started = AtomicBoolean(false)

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
        if (isUnlocked()) runOnce()
    }

    private fun runOnce() {
        if (!started.compareAndSet(false, true)) return
        try {
            fullStartup()
        } catch (e: Throwable) {
            log("full startup failed; not retrying", e)
        }
    }

    private companion object {
        const val TAG = "TandemStartup"
    }
}
