package me.shawnsquire.tandem.alarm

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class DeferredAlarmWritesTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val t1 = Instant.parse("2026-03-02T11:30:00Z")

    private val writes = listOf(
        DeferredWrite.SetEnabled("a", false),
        DeferredWrite.SetSkip("b", t1),
        DeferredWrite.SetSkip("b", null),
        DeferredWrite.SetEnabled("a", true),
    )

    private fun exercise(queue: DeferredAlarmWrites, fresh: () -> DeferredAlarmWrites = { queue }) {
        assertTrue(fresh().all().isEmpty())
        writes.forEach(queue::append)
        assertEquals(writes, fresh().all())
        val seqs = fresh().entries().map { it.seq }
        assertEquals("unique and increasing", seqs.sorted().distinct(), seqs)
        queue.removeThrough(seqs[2])
        assertEquals(writes.drop(3), fresh().all())
        queue.removeThrough(seqs[1]) // already trimmed: changes nothing
        assertEquals(writes.drop(3), fresh().all())
        queue.removeThrough(seqs.last() + 5) // past every queued seq
        assertTrue(fresh().all().isEmpty())

        // Never reused once the queue empties, so a stale trim can't remove a newer op.
        queue.append(writes[0])
        val next = fresh().entries().single().seq
        assertTrue("seq $next reused after emptying (last ${seqs.last()})", next > seqs.last())
        queue.removeThrough(seqs.last())
        assertEquals(listOf(writes[0]), fresh().all())
        queue.removeThrough(next)
        assertTrue(fresh().all().isEmpty())
    }

    @Test fun sharedPrefsQueueKeepsOrderAndRoundTrips() =
        exercise(SharedPrefsDeferredAlarmWrites(context)) { SharedPrefsDeferredAlarmWrites(context) }

    @Test fun inMemoryQueueBehavesTheSame() = exercise(InMemoryDeferredAlarmWrites())

    @Test fun onlyTouchesDeviceStorage() {
        val locked = LockedContext(context)
        exercise(SharedPrefsDeferredAlarmWrites(locked)) { SharedPrefsDeferredAlarmWrites(locked) }
    }

    @Test fun garbageReadsAsEmptyAndIsReplacedOnAppend() {
        deviceProtectedPrefs(context, "alarm_deferred_writes").edit().putString("writes", "{oops").commit()
        val queue = SharedPrefsDeferredAlarmWrites(context)
        assertTrue(queue.all().isEmpty())
        queue.append(writes[0])
        assertEquals(listOf(writes[0]), SharedPrefsDeferredAlarmWrites(context).all())
    }

    @Test fun legacyEntriesWithoutSeqDecodeInOrderAndTrimBySeq() {
        val legacy = """[{"id":"a","op":"setEnabled","enabled":false},{"id":"b","op":"setSkip","occurrence":${t1.toEpochMilli()}},""" +
            """{"id":"b","op":"setSkip"}]"""
        deviceProtectedPrefs(context, "alarm_deferred_writes").edit().putString("writes", legacy).commit()
        val queue = SharedPrefsDeferredAlarmWrites(context)

        val entries = queue.entries()
        assertEquals(writes.take(3), entries.map { it.write })
        assertEquals(entries.map { it.seq }.sorted().distinct(), entries.map { it.seq })
        queue.append(writes[3])
        assertTrue(queue.entries().last().seq > entries.last().seq)

        queue.removeThrough(entries[0].seq)
        assertEquals(writes.drop(1), SharedPrefsDeferredAlarmWrites(context).all())
    }

    @Test fun drainedLegacyQueueNeverReusesItsSeqs() {
        val legacy = """[{"id":"a","op":"setEnabled","enabled":false},{"id":"a","op":"setEnabled","enabled":true}]"""
        deviceProtectedPrefs(context, "alarm_deferred_writes").edit().putString("writes", legacy).commit()
        val queue = SharedPrefsDeferredAlarmWrites(context)
        val last = queue.entries().last().seq
        queue.removeThrough(last)

        queue.append(writes[0])
        assertTrue(SharedPrefsDeferredAlarmWrites(context).entries().single().seq > last)
    }
}
