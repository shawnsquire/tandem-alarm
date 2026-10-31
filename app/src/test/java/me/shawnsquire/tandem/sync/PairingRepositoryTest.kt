package me.shawnsquire.tandem.sync

import app.cash.turbine.test
import com.google.firebase.functions.FirebaseFunctionsException.Code
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PairingRepositoryTest {
    private val h = Household("h1", "me", "them")

    private fun partner(name: String) = PartnerState(name, emptyList(), null, Instant.EPOCH)

    @Test fun `error messages by code`() {
        assertEquals("That code is invalid or has expired.", pairingErrorMessage(Code.NOT_FOUND, "invalid or expired code"))
        assertEquals("That household already has two phones.", pairingErrorMessage(Code.FAILED_PRECONDITION, "household is full"))
        assertEquals("This phone is already paired. Unpair first.", pairingErrorMessage(Code.FAILED_PRECONDITION, "already in a household"))
        assertEquals("Can't pair right now.", pairingErrorMessage(Code.FAILED_PRECONDITION, "other"))
        assertEquals("Can't pair right now.", pairingErrorMessage(Code.FAILED_PRECONDITION, null))
        assertEquals("Enter the pairing code.", pairingErrorMessage(Code.INVALID_ARGUMENT, "code is required"))
        assertEquals("Not signed in yet. Check your connection and try again.", pairingErrorMessage(Code.UNAUTHENTICATED, null))
        val offline = "No connection. Try again when you're online."
        assertEquals(offline, pairingErrorMessage(Code.UNAVAILABLE, null))
        assertEquals(offline, pairingErrorMessage(Code.DEADLINE_EXCEEDED, null))
        assertEquals("Something went wrong. Try again.", pairingErrorMessage(Code.INTERNAL, null))
    }

    @Test fun `unpaired when no household`() = runTest {
        pairingStatus(flowOf(null), flowOf(partner("A"))).test {
            assertEquals(PairingStatus.Unpaired, awaitItem()); awaitComplete()
        }
    }

    @Test fun `paired with partner name`() = runTest {
        pairingStatus(flowOf(h), flowOf(partner("Sam"))).test {
            assertEquals(PairingStatus.Paired("h1", "Sam"), awaitItem()); awaitComplete()
        }
    }

    @Test fun `paired without partner doc`() = runTest {
        pairingStatus(flowOf(h), flowOf(null)).test {
            assertEquals(PairingStatus.Paired("h1", null), awaitItem()); awaitComplete()
        }
    }

    @Test fun `blank name is null`() = runTest {
        pairingStatus(flowOf(h), flowOf(partner("  "))).test {
            assertEquals(PairingStatus.Paired("h1", null), awaitItem()); awaitComplete()
        }
    }
}
