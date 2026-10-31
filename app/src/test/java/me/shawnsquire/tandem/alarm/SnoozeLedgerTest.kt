package me.shawnsquire.tandem.alarm

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import me.shawnsquire.tandem.awaitTandemStartup

@RunWith(RobolectricTestRunner::class)
class SnoozeLedgerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val t1 = Instant.parse("2026-03-02T07:10:00Z")
    private val t2 = Instant.parse("2026-03-02T07:20:00Z")

    // The startup reschedule drops long-past ledger entries; it must not race these.
    @Before fun awaitStartup() = awaitTandemStartup()

    private fun exercise(ledger: SnoozeLedger, fresh: () -> SnoozeLedger = { ledger }) {
        assertTrue(ledger.all().isEmpty())
        ledger.put("ring-A", "a|weird", t1)
        ledger.put("ring-B", "b", t2)
        assertEquals(SnoozeEntry("ring-A", "a|weird", t1), fresh().get("ring-A"))
        assertEquals(
            setOf(SnoozeEntry("ring-A", "a|weird", t1), SnoozeEntry("ring-B", "b", t2)),
            fresh().all().toSet(),
        )
        ledger.put("ring-A", "a|weird", t2) // re-snooze replaces
        assertEquals(t2, fresh().get("ring-A")!!.until)
        ledger.remove("ring-A")
        assertNull(fresh().get("ring-A"))
        assertEquals(listOf(SnoozeEntry("ring-B", "b", t2)), fresh().all())
        ledger.remove("missing") // no-op
    }

    @Test fun sharedPrefsLedgerRoundTripsAndPersistsAcrossInstances() =
        exercise(SharedPrefsSnoozeLedger(context)) { SharedPrefsSnoozeLedger(context) }

    @Test fun inMemoryLedgerBehavesTheSame() = exercise(InMemorySnoozeLedger())
}
