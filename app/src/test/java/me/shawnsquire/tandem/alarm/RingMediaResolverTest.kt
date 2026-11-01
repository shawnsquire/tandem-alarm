package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeLinkedMediaCache
import me.shawnsquire.tandem.media.LinkedMediaCache
import me.shawnsquire.tandem.media.RingMedia
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class RingMediaResolverTest {
    private val own = Alarm("a", "Wake", 6, 30, emptySet(), true, null, "content://own", "/wall/own")
    private val linked = own.copy(
        link = AlarmLink("owner", "o1", LinkMode.RING, null, true, null, "Mine", null, null),
    )

    private class Cache(private val block: suspend () -> RingMedia) : LinkedMediaCache by FakeLinkedMediaCache() {
        var calls = 0
        override suspend fun resolve(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: String?): RingMedia {
            calls++
            return block()
        }
    }

    /** The cache's dispatcher on the test clock, so a quick resolve isn't overtaken by the virtual timeout. */
    private fun TestScope.testIo() = StandardTestDispatcher(testScheduler)

    @Test fun ownRingIgnoresTheCache() = runTest {
        val cache = Cache { error("must not be asked") }
        val m = resolveRingMedia(own, cache, "content://def", "/wall/def", dispatcher = testIo())
        assertEquals(RingMedia("Wake", listOf("content://own", "content://def"), listOf("/wall/own", "/wall/def")), m)
        assertEquals(0, cache.calls)
    }

    @Test fun noAlarmIsTheDefaultsOnly() = runTest {
        val m = resolveRingMedia(null, Cache { error("no") }, "content://def", null, dispatcher = testIo())
        assertEquals(RingMedia("", listOf("content://def"), emptyList()), m)
    }

    @Test fun linkedRingUsesTheCacheResult() = runTest {
        val result = RingMedia("Owner label", listOf("/linked/x"), listOf("/linked/w"))
        assertEquals(result, resolveRingMedia(linked, Cache { result }, "content://def", null, dispatcher = testIo()))
    }

    @Test fun slowCacheFallsBackAfterTheTimeout() = runTest {
        val cache = Cache { awaitCancellation() }
        val m = resolveRingMedia(linked, cache, "content://def", null, timeout = 500.milliseconds, dispatcher = testIo())
        assertEquals(500, currentTime)
        assertEquals(RingMedia("Mine", listOf("content://own", "content://def"), listOf("/wall/own")), m)
    }

    @Test fun throwingCacheFallsBack() = runTest {
        val m = resolveRingMedia(linked, Cache { throw IllegalStateException("disk") }, null, null, dispatcher = testIo())
        assertEquals(RingMedia("Mine", listOf("content://own"), listOf("/wall/own")), m)
    }

    @Test fun callerCancellationPropagates() = runTest {
        val started = CompletableDeferred<Unit>()
        val job = async { resolveRingMedia(linked, Cache { started.complete(Unit); awaitCancellation() }, null, null, dispatcher = testIo()) }
        runCurrent()
        assertTrue(started.isCompleted)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun cacheBlockingItsThreadFallsBackWithinTheTimeoutOffTheCallersThread() = runTest {
        // Real IO threads: a resolve run on the caller's (the test's single) thread would block it,
        // and withTimeoutOrNull can't interrupt a blocked thread.
        val latch = CountDownLatch(1)
        val callerThread = Thread.currentThread()
        var resolveThread: Thread? = null
        val started = CountDownLatch(1)
        try {
            val cache = Cache { resolveThread = Thread.currentThread(); started.countDown(); latch.await(5, TimeUnit.SECONDS); RingMedia("late", emptyList(), emptyList()) }
            val job = async { resolveRingMedia(linked, cache, "content://def", null, timeout = 500.milliseconds, dispatcher = Dispatchers.IO) }
            runCurrent()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            advanceTimeBy(501); runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(RingMedia("Mine", listOf("content://own", "content://def"), listOf("/wall/own")), job.await())
            assertNotEquals(callerThread, resolveThread)
        } finally {
            latch.countDown()
        }
    }
}
