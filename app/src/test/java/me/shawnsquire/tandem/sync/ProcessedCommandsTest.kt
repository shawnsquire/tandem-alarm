package me.shawnsquire.tandem.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ProcessedCommandsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = Instant.parse("2026-03-02T05:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val future = now.plusSeconds(3600)

    private fun TestScope.store(): Pair<ProcessedCommands, DataStore<Preferences>> {
        val ds = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "test.preferences_pb") },
        )
        return ProcessedCommands(ds, clock) to ds
    }

    @Test fun `unknown id has no outcome`() = runTest {
        assertNull(store().first.outcome("x"))
    }

    @Test fun `round trips applied and rejected with delimiters in reason`() = runTest {
        val p = store().first
        val reason = "a|b|c\nd%e é"
        p.record("c1", Outcome.Applied, future)
        p.record("c2", Outcome.Rejected(reason), future)
        p.record("we|ird", Outcome.Rejected(""), future)
        assertEquals(Outcome.Applied, p.outcome("c1"))
        assertEquals(Outcome.Rejected(reason), p.outcome("c2"))
        assertEquals(Outcome.Rejected(""), p.outcome("we|ird"))
    }

    @Test fun `recording prunes expired entries`() = runTest {
        val p = store().first
        p.record("old", Outcome.Applied, now.minusSeconds(1))
        assertEquals(Outcome.Applied, p.outcome("old"))
        p.record("new", Outcome.Applied, future)
        assertNull(p.outcome("old"))
        assertEquals(Outcome.Applied, p.outcome("new"))
    }

    @Test fun `caps entries keeping the newest`() = runTest {
        val (p, ds) = store()
        repeat(250) { p.record("c$it", Outcome.Applied, future.plusSeconds(it.toLong())) }
        assertEquals(200, ds.data.first().asMap().values.single().let { (it as Set<*>).size })
        assertNull(p.outcome("c0"))
        assertEquals(Outcome.Applied, p.outcome("c249"))
    }
}
