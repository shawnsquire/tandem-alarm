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

    private fun roundTripWithOccurrence(ledger: SnoozeLedger, fresh: () -> SnoozeLedger = { ledger }) {
        ledger.put("ring-O", "a|weird", t1, t2)
        assertEquals(SnoozeEntry("ring-O", "a|weird", t1, t2), fresh().get("ring-O"))
        ledger.put("ring-N", "b", t1)
        assertNull(fresh().get("ring-N")!!.occurrenceAt)
    }

    @Test fun sharedPrefsRoundTripsOccurrence() =
        roundTripWithOccurrence(SharedPrefsSnoozeLedger(context)) { SharedPrefsSnoozeLedger(context) }

    @Test fun inMemoryRoundTripsOccurrence() = roundTripWithOccurrence(InMemorySnoozeLedger())

    @Test fun roundTripWithoutOccurrenceHasNullOccurrence() {
        val ledger = SharedPrefsSnoozeLedger(context)
        ledger.put("ring-X", "a", t1)
        assertEquals(SnoozeEntry("ring-X", "a", t1, null), SharedPrefsSnoozeLedger(context).get("ring-X"))
    }

    @Test fun malformedOccurrencePartKeepsTheEntryWithNullOccurrence() {
        deviceProtectedPrefs(context, "alarm_snoozes").edit().putString("ring-bad", "${t1.toEpochMilli()};oops|a").commit()
        assertEquals(SnoozeEntry("ring-bad", "a", t1, null), SharedPrefsSnoozeLedger(context).get("ring-bad"))
    }

    @Test fun oldFormatValueDecodesWithNullOccurrence() {
        deviceProtectedPrefs(context, "alarm_snoozes").edit().putString("ring-old", "${t1.toEpochMilli()}|a|weird").commit()
        assertEquals(SnoozeEntry("ring-old", "a|weird", t1, null), SharedPrefsSnoozeLedger(context).get("ring-old"))
    }

    private fun roundTripWithCount(ledger: SnoozeLedger, fresh: () -> SnoozeLedger = { ledger }) {
        ledger.put("ring-C", "a|weird", t1, t2, 2)
        assertEquals(SnoozeEntry("ring-C", "a|weird", t1, t2, 2), fresh().get("ring-C"))
        ledger.put("ring-D", "b", t1, null, 3) // a count without an occurrence
        assertEquals(SnoozeEntry("ring-D", "b", t1, null, 3), fresh().get("ring-D"))
        ledger.put("ring-E", "b", t1)
        assertEquals(0, fresh().get("ring-E")!!.snoozeCount)
    }

    @Test fun sharedPrefsRoundTripsCount() =
        roundTripWithCount(SharedPrefsSnoozeLedger(context)) { SharedPrefsSnoozeLedger(context) }

    @Test fun inMemoryRoundTripsCount() = roundTripWithCount(InMemorySnoozeLedger())

    @Test fun entriesWrittenBeforeTheCountReadZero() {
        val prefs = deviceProtectedPrefs(context, "alarm_snoozes")
        prefs.edit().putString("ring-old", "${t1.toEpochMilli()}|a|weird").commit()
        prefs.edit().putString("ring-occ", "${t1.toEpochMilli()};${t2.toEpochMilli()}|a").commit()
        assertEquals(SnoozeEntry("ring-old", "a|weird", t1, null, 0), SharedPrefsSnoozeLedger(context).get("ring-old"))
        assertEquals(SnoozeEntry("ring-occ", "a", t1, t2, 0), SharedPrefsSnoozeLedger(context).get("ring-occ"))
    }

    @Test fun badCountPartKeepsTheEntry() {
        val prefs = deviceProtectedPrefs(context, "alarm_snoozes")
        prefs.edit().putString("ring-bad", "${t1.toEpochMilli()};${t2.toEpochMilli()};oops|a").commit()
        prefs.edit().putString("ring-neg", "${t1.toEpochMilli()};${t2.toEpochMilli()};-3|a").commit()
        prefs.edit().putString("ring-noocc", "${t1.toEpochMilli()};oops;4|a").commit()
        assertEquals(SnoozeEntry("ring-bad", "a", t1, t2, 0), SharedPrefsSnoozeLedger(context).get("ring-bad"))
        assertEquals(SnoozeEntry("ring-neg", "a", t1, t2, 0), SharedPrefsSnoozeLedger(context).get("ring-neg"))
        // A bad occurrence does not lose the count behind it.
        assertEquals(SnoozeEntry("ring-noocc", "a", t1, null, 4), SharedPrefsSnoozeLedger(context).get("ring-noocc"))
    }
}
