package me.shawnsquire.tandem.sync

import app.cash.turbine.test
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PartnerRepositoryTest {
    private val h = Household("h1", "me", "them")

    @Test fun `null household emits null`() = runTest {
        PartnerRepositoryImpl(flowOf(null)) { error("unused") }.partner.test {
            assertEquals(null, awaitItem()); awaitComplete()
        }
    }

    @Test fun `source error emits null`() = runTest {
        PartnerRepositoryImpl(flowOf(h)) { flow { throw IllegalStateException("denied") } }.partner.test {
            assertEquals(null, awaitItem()); awaitComplete()
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
}
