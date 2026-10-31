package me.shawnsquire.tandem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TandemStartupTest {
    private val runs = AtomicInteger()
    private var unlocked = false
    private var receiver: (() -> Unit)? = null
    private var registrations = 0

    private fun startup(
        isUnlocked: () -> Boolean = { unlocked },
        fullStartup: () -> Unit = { runs.incrementAndGet() },
    ) = TandemStartup(
        isUnlocked = isUnlocked,
        registerUnlockReceiver = { onUnlocked -> registrations++; receiver = onUnlocked },
        fullStartup = fullStartup,
        log = { _, _ -> },
    )

    @Test
    fun unlockedStartRunsOnceWithoutRegistering() {
        unlocked = true
        startup().start()

        assertEquals(1, runs.get())
        assertEquals(0, registrations)
    }

    @Test
    fun lockedStartDoesNotRunAndRegisters() {
        startup().start()

        assertEquals(0, runs.get())
        assertEquals(1, registrations)
    }

    @Test
    fun unlockCallbackRunsOnce() {
        startup().start()
        unlocked = true

        receiver!!()
        receiver!!()

        assertEquals(1, runs.get())
    }

    @Test
    fun callbackAfterAnAlreadyRunStartDoesNotRunAgain() {
        val startup = startup()
        startup.start()
        unlocked = true
        startup.ensureStarted() // e.g. BOOT_COMPLETED beat USER_UNLOCKED

        receiver!!()

        assertEquals(1, runs.get())
    }

    @Test
    fun unlockBetweenTheCheckAndTheRegistrationStillRunsExactlyOnce() {
        // The first check sees locked; the user unlocks before the receiver is registered, so the
        // broadcast is missed. The re-check after registering must catch it.
        var checks = 0
        val startup = startup(isUnlocked = { checks++ > 0 })

        startup.start()

        assertEquals(1, runs.get())
        assertEquals(1, registrations)
        // A late broadcast (it was already in flight) changes nothing.
        receiver!!()
        assertEquals(1, runs.get())
    }

    @Test
    fun ensureStartedIsANoOpWhileLocked() {
        val startup = startup()

        startup.ensureStarted()

        assertEquals(0, runs.get())
        assertEquals(0, registrations)
    }

    @Test
    fun ensureStartedRunsOnceWhenUnlocked() {
        unlocked = true
        val startup = startup()

        startup.ensureStarted()
        startup.ensureStarted()
        startup.start()

        assertEquals(1, runs.get())
    }

    @Test
    fun concurrentCallersGetASingleRun() {
        unlocked = true
        val gate = CountDownLatch(1)
        val startup = startup(fullStartup = { gate.await(); runs.incrementAndGet() })
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                ready.countDown()
                ready.await()
                startup.ensureStarted()
            }
        }
        ready.await(5, TimeUnit.SECONDS)
        gate.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(1, runs.get())
    }

    @Test
    fun aThrowingStartupIsLoggedNotRethrownAndNeverRetried() {
        unlocked = true
        var logged: Throwable? = null
        val startup = TandemStartup(
            isUnlocked = { true },
            registerUnlockReceiver = { error("unused") },
            fullStartup = { runs.incrementAndGet(); error("boom") },
            log = { _, e -> logged = e },
        )

        startup.start()
        assertNotNull(logged)
        assertEquals("boom", logged!!.message)

        // A retry would double-start whatever listeners the first attempt already started.
        logged = null
        startup.ensureStarted()
        assertEquals(1, runs.get())
        assertNull(logged)
    }
}
