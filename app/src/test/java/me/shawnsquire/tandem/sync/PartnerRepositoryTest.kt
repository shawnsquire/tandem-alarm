package me.shawnsquire.tandem.sync

import app.cash.turbine.test
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PartnerRepositoryTest {
    private val h = Household("h1", "me", "them")

    @Test fun `null household emits null`() = runTest {
        PartnerRepositoryImpl(flowOf(null)) { error("unused") }.partner.test {
            assertEquals(null, awaitItem()); awaitComplete()
        }
    }

    @Test fun `source error emits null, resubscribes after the backoff and recovers`() = runTest {
        var listens = 0
        val src = { _: Household ->
            flow {
                if (++listens == 1) throw IllegalStateException("denied")
                emit(mapOf<String, Any?>("displayName" to "Sam")); awaitCancellation()
            }
        }
        PartnerRepositoryImpl(flowOf(h), src).partner.test {
            assertEquals(null, awaitItem())
            expectNoEvents()
            assertEquals(1, listens)
            testScheduler.advanceTimeBy(2_001); runCurrent()
            assertEquals("Sam", awaitItem()?.displayName)
            assertEquals(2, listens)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `missing or empty doc emits null`() = runTest {
        PartnerRepositoryImpl(flowOf(h)) { flowOf(null) }.partner.test {
            assertEquals(null, awaitItem()); awaitComplete()
        }
    }

    @Test fun `maps doc`() = runTest {
        val src = { _: Household -> flowOf(mapOf<String, Any?>("displayName" to "Sam")) }
        PartnerRepositoryImpl(flowOf(h), src).partner.test {
            assertEquals("Sam", awaitItem()?.displayName); awaitComplete()
        }
    }

    private fun withServer(read: suspend (Household) -> ServerDoc?, hh: Household? = h) =
        PartnerRepositoryImpl(flowOf(hh), { error("unused") }, read)

    @Test fun `readFromServer parses a server result`() = runTest {
        val repo = withServer({ ServerDoc(mapOf("displayName" to "Sam"), fromCache = false) })
        assertEquals("Sam", repo.readFromServer()?.displayName)
    }

    @Test fun `readFromServer reads the partner's doc for the household`() = runTest {
        var seen: Household? = null
        withServer({ seen = it; ServerDoc(mapOf("displayName" to "Sam"), false) }).readFromServer()
        assertEquals(h, seen)
    }

    @Test fun `readFromServer ignores a cache-only snapshot`() = runTest {
        val repo = withServer({ ServerDoc(mapOf("displayName" to "Sam"), fromCache = true) })
        assertNull(repo.readFromServer())
    }

    @Test fun `readFromServer is null on exception, missing doc, no read and no household`() = runTest {
        assertNull(withServer({ throw IllegalStateException("offline") }).readFromServer())
        assertNull(withServer({ ServerDoc(null, fromCache = false) }).readFromServer())
        assertNull(withServer({ null }).readFromServer())
        assertNull(withServer({ error("unused") }, hh = null).readFromServer())
    }

    @Test fun `readFromServer is null when the household is not known`() = runTest {
        val repo = PartnerRepositoryImpl(MutableSharedFlow(), { error("unused") }, { error("unused") })
        assertNull(repo.readFromServer())
        assertEquals(1_000, testScheduler.currentTime)
    }

    @Test fun `readFromServer propagates caller cancellation`() = runTest {
        val repo = withServer({ awaitCancellation() })
        val job = launch { repo.readFromServer() }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
    }
}
