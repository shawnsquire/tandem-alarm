package me.shawnsquire.tandem.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}
