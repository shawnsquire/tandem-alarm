package me.shawnsquire.tandem.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RingStateStoreImplTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.store(): Pair<RingStateStoreImpl, DataStore<Preferences>> {
        val ds = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "test.preferences_pb") },
        )
        return RingStateStoreImpl(ds) to ds
    }

    private val started = Instant.ofEpochMilli(1_700_000_000_000)

    @Test
    fun emptyEmitsNull() = runTest {
        assertNull(store().first.state.first())
    }

    @Test
    fun roundTripWithoutSnooze() = runTest {
        val s = store().first
        val rs = RingState("a1", "r1", started, null)
        s.set(rs)
        assertEquals(rs, s.state.first())
    }

    @Test
    fun roundTripWithSnooze() = runTest {
        val s = store().first
        val rs = RingState("a1", "r1", started, started.plusSeconds(600))
        s.set(rs)
        assertEquals(rs, s.state.first())
    }

    @Test
    fun overwriteRemovesStaleSnooze() = runTest {
        val s = store().first
        s.set(RingState("a1", "r1", started, started.plusSeconds(600)))
        val next = RingState("a1", "r1", started, null)
        s.set(next)
        assertEquals(next, s.state.first())
    }

    @Test
    fun setNullClears() = runTest {
        val (s, ds) = store()
        s.set(RingState("a1", "r1", started, started.plusSeconds(600)))
        s.set(null)
        assertNull(s.state.first())
        assertEquals(0, ds.data.first().asMap().size)
    }

    @Test
    fun incompleteStateEmitsNull() = runTest {
        val (s, ds) = store()
        s.set(RingState("a1", "r1", started, null))
        ds.updateData { p ->
            p.toMutablePreferences().also { m -> m.remove(m.asMap().keys.first { it.name == "ringId" }) }
        }
        assertNull(s.state.first())
    }

    private val occ = Instant.ofEpochMilli(1_700_000_300_000)
    private val members = listOf(
        RingMember("a2", Instant.ofEpochMilli(1_700_000_400_000)),
        RingMember("a3", Instant.ofEpochMilli(1_700_000_500_000)),
    )

    @Test
    fun roundTripWithOccurrenceAndMerged() = runTest {
        val s = store().first
        val rs = RingState("a1", "r1", started, null, occ, members)
        s.set(rs)
        assertEquals(rs, s.state.first())
    }

    @Test
    fun roundTripWithoutNewFieldsGivesNullAndEmpty() = runTest {
        val s = store().first
        s.set(RingState("a1", "r1", started, null))
        val r = s.state.first()!!
        assertNull(r.occurrenceAt)
        assertEquals(emptyList<RingMember>(), r.merged)
    }

    @Test
    fun oldVersionStoreReadsBack() = runTest {
        val (s, ds) = store()
        ds.edit { p ->
            p[stringPreferencesKey("alarmId")] = "a1"
            p[stringPreferencesKey("ringId")] = "r1"
            p[longPreferencesKey("startedAt")] = started.toEpochMilli()
            p[longPreferencesKey("snoozedUntil")] = started.plusSeconds(600).toEpochMilli()
        }
        assertEquals(RingState("a1", "r1", started, started.plusSeconds(600)), s.state.first())
    }

    @Test
    fun corruptMergedKeepsRing() = runTest {
        val (s, ds) = store()
        s.set(RingState("a1", "r1", started, null, occ, members))
        ds.edit { p -> p[stringPreferencesKey("merged")] = "{not json[" }
        assertEquals(RingState("a1", "r1", started, null, occ, emptyList()), s.state.first())
    }

    @Test
    fun corruptMemberDroppedOthersKept() = runTest {
        val (s, ds) = store()
        s.set(RingState("a1", "r1", started, null))
        ds.edit { p ->
            p[stringPreferencesKey("merged")] =
                """[{"alarmId":"a2","occurrenceAt":5},{"alarmId":"x"},"junk",{"alarmId":"a3","occurrenceAt":6}]"""
        }
        assertEquals(
            listOf(RingMember("a2", Instant.ofEpochMilli(5)), RingMember("a3", Instant.ofEpochMilli(6))),
            s.state.first()!!.merged,
        )
    }

    @Test
    fun overwriteRemovesStaleNewKeys() = runTest {
        val (s, ds) = store()
        s.set(RingState("a1", "r1", started, null, occ, members))
        s.set(RingState("a1", "r1", started, null))
        assertEquals(3, ds.data.first().asMap().size)
    }

    @Test
    fun setNullClearsNewKeys() = runTest {
        val (s, ds) = store()
        s.set(RingState("a1", "r1", started, null, occ, members))
        s.set(null)
        assertNull(s.state.first())
        assertEquals(0, ds.data.first().asMap().size)
    }

    @Test
    fun snoozeCountRoundTripsAndAbsentReadsZero() = runTest {
        val (s, ds) = store()
        s.set(RingState("a1", "r1", started, null, snoozeCount = 3))
        assertEquals(3, s.state.first()!!.snoozeCount)
        s.set(RingState("a1", "r1", started, null))
        assertEquals(0, s.state.first()!!.snoozeCount)
        assertEquals(3, ds.data.first().asMap().size) // a zero count is not stored
    }

    @Test
    fun oldVersionStoreWithoutCountReadsZero() = runTest {
        val (s, ds) = store()
        ds.edit { p ->
            p[stringPreferencesKey("alarmId")] = "a1"
            p[stringPreferencesKey("ringId")] = "r1"
            p[longPreferencesKey("startedAt")] = started.toEpochMilli()
        }
        assertEquals(0, s.state.first()!!.snoozeCount)
    }

    @Test
    fun negativeOrGarbledCountReadsZeroAndKeepsTheRing() = runTest {
        val (s, ds) = store()
        s.set(RingState("a1", "r1", started, null, occ, members, snoozeCount = 2))
        ds.edit { p -> p[intPreferencesKey("snoozeCount")] = -4 }
        assertEquals(RingState("a1", "r1", started, null, occ, members), s.state.first())
        ds.edit { p ->
            p.remove(intPreferencesKey("snoozeCount"))
            p[stringPreferencesKey("snoozeCount")] = "oops"
        }
        assertEquals(RingState("a1", "r1", started, null, occ, members), s.state.first())
    }

    @Test
    fun setNullClearsTheCount() = runTest {
        val (s, ds) = store()
        s.set(RingState("a1", "r1", started, null, snoozeCount = 2))
        s.set(null)
        assertNull(s.state.first())
        assertEquals(0, ds.data.first().asMap().size)
    }
}
