package me.shawnsquire.tandem

import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.RuntimeEnvironment
import kotlin.time.Duration.Companion.seconds

/**
 * Waits for the stale-ring cleanup and rescheduleAll that TandemApp's startup launches in the
 * background. They write the alarm mirror, the ring state and the scheduler records, so a test
 * using those must not race them.
 */
fun awaitTandemStartup() = runBlocking {
    val app = RuntimeEnvironment.getApplication() as? TandemApp ?: return@runBlocking
    withTimeout(10.seconds) { app.startupAlarmWork.joinAll() }
}
