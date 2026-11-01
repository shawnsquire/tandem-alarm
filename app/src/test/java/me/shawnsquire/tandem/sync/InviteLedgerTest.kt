package me.shawnsquire.tandem.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class InviteLedgerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = Instant.parse("2026-03-02T05:00:00Z")
    private val future = now.plusSeconds(3600)

    private fun TestScope.store(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { File(tmp.root, "test.preferences_pb") },
    )

    private fun ledger(ds: DataStore<Preferences>, at: Instant = now) = InviteLedger(ds, Clock.fixed(at, ZoneOffset.UTC))

    @Test fun `open invites round trip and remove by command id`() = runTest {
        val l = ledger(store())
        l.addOpen("c|1", "a|1", future)
        l.addOpen("c2", "a2", future)
        assertEquals(listOf("c|1" to "a|1", "c2" to "a2"), l.open().map { it.commandId to it.alarmId })
        l.removeOpen("c|1")
        assertEquals(listOf("c2"), l.open().map { it.commandId })
    }

    @Test fun `expired open invites are not returned`() = runTest {
        val ds = store()
        ledger(ds).addOpen("c1", "a1", future)
        assertTrue(ledger(ds, future.plusSeconds(1)).open().isEmpty())
    }

    @Test fun `markNotified is true once per id until it expires`() = runTest {
        val ds = store()
        assertTrue(ledger(ds).markNotified("c1", future))
        assertFalse(ledger(ds).markNotified("c1", future))
        assertTrue(ledger(ds).markNotified("c2", future))
        assertTrue(ledger(ds, future.plusSeconds(1)).markNotified("c1", future.plusSeconds(3600)))
    }
}
