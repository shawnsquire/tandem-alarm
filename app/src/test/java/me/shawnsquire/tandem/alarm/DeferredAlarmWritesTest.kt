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
        queue.removeFirst(3)
        assertEquals(writes.drop(3), fresh().all())
        queue.removeFirst(5) // more than queued
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
}
