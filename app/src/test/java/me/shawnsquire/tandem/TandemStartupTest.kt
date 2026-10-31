package me.shawnsquire.tandem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun aThrowingStartupIsLoggedNotRethrownAndRetriedUntilItSucceeds() {
        var logged: Throwable? = null
        val startup = TandemStartup(
            isUnlocked = { true },
            registerUnlockReceiver = { error("unused") },
            fullStartup = { if (runs.incrementAndGet() == 1) error("boom") },
            log = { _, e -> logged = e },
        )

        startup.start()
        assertNotNull(logged)
        assertEquals("boom", logged!!.message)

        // Not marked as started: the next caller (e.g. the container getter) tries again.
        logged = null
        startup.ensureStarted()
        assertEquals(2, runs.get())
        assertNull(logged)

        // Success sticks.
        startup.ensureStarted()
        startup.start()
        assertEquals(2, runs.get())
    }

    @Test
    fun aCallerOnAnotherThreadWaitsForTheRunningAttempt() {
        unlocked = true
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val startup = startup(fullStartup = { entered.countDown(); gate.await(5, TimeUnit.SECONDS); runs.incrementAndGet() })
        val first = Thread { startup.ensureStarted() }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        // Started while the first attempt runs: must not return before it finishes.
        val returned = CountDownLatch(1)
        val second = Thread { startup.ensureStarted(); returned.countDown() }.apply { start() }
        assertFalse("second caller returned during the first attempt", returned.await(200, TimeUnit.MILLISECONDS))

        gate.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        first.join(5_000)
        second.join(5_000)
        assertEquals(1, runs.get())
    }

    @Test
    fun aReentrantCallFromInsideStartupNeitherRunsAgainNorDeadlocks() {
        unlocked = true
        lateinit var startup: TandemStartup
        startup = startup(fullStartup = {
            runs.incrementAndGet()
            // e.g. something in full startup reading TandemApp.container
            startup.ensureStarted()
        })

        // On its own thread, so a deadlock fails the test instead of hanging the suite.
        val done = CountDownLatch(1)
        Thread { startup.ensureStarted(); done.countDown() }.apply { isDaemon = true; start() }

        assertTrue("re-entrant call deadlocked", done.await(5, TimeUnit.SECONDS))
        assertEquals(1, runs.get())
        // The outer attempt still counts as the success.
        startup.ensureStarted()
        assertEquals(1, runs.get())
    }
}
