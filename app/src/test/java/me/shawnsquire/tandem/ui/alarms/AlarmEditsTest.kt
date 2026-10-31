package me.shawnsquire.tandem.ui.alarms

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.SAMPLE_ALARMS
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.schedule.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Records the write order so "reschedule comes after the write" is checkable. */
private class OrderedRepo(private val inner: FakeAlarmRepository, val log: MutableList<String>) : AlarmRepository by inner {
    override suspend fun upsert(alarm: Alarm) { inner.upsert(alarm); log += "write" }
    override suspend fun delete(id: String) { inner.delete(id); log += "write" }
    override suspend fun setEnabled(id: String, enabled: Boolean) { inner.setEnabled(id, enabled); log += "write" }
    override suspend fun setSkip(id: String, occurrence: Instant?) { inner.setSkip(id, occurrence); log += "write" }
}

private class LoggingScheduler(val log: MutableList<String>) : me.shawnsquire.tandem.alarm.AlarmScheduler {
    override suspend fun rescheduleAll() { log += "reschedule" }
}

class AlarmEditsTest {
    private val a = SAMPLE_ALARMS.first()

    private fun logOf(block: suspend (AlarmEdits) -> Unit): List<String> {
        val log = mutableListOf<String>()
        val edits = AlarmEdits(OrderedRepo(FakeAlarmRepository(), log), LoggingScheduler(log))
        runTest { block(edits) }
        return log
    }

    @Test fun upsert() = assertEquals(listOf("write", "reschedule"), logOf { it.upsert(a.copy(label = "x")) })
    @Test fun delete() = assertEquals(listOf("write", "reschedule"), logOf { it.delete(a.id) })
    @Test fun setEnabled() = assertEquals(listOf("write", "reschedule"), logOf { it.setEnabled(a.id, false) })
    @Test fun setSkip() = assertEquals(listOf("write", "reschedule"), logOf { it.setSkip(a.id, Instant.EPOCH) })

    @Test fun writesReachRepositoryAndScheduleOnce() = runTest {
        val repo = FakeAlarmRepository()
        val scheduler = FakeAlarmScheduler()
        val edits = AlarmEdits(repo, scheduler)
        edits.setSkip(a.id, Instant.EPOCH)
        assertEquals(Instant.EPOCH, repo.get(a.id)!!.skippedOccurrenceAt)
        assertEquals(1, scheduler.rescheduleCount)
        edits.setSkip(a.id, null)
        assertNull(repo.alarms.first().first { it.id == a.id }.skippedOccurrenceAt)
        assertEquals(2, scheduler.rescheduleCount)
    }

    @Test fun cancelledCallerStillReschedulesAfterWrite() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var rescheduled = false
        val repo = object : AlarmRepository by FakeAlarmRepository() {
            override suspend fun upsert(alarm: Alarm) { gate.await() }
        }
        val scheduler = object : me.shawnsquire.tandem.alarm.AlarmScheduler {
            override suspend fun rescheduleAll() { rescheduled = true }
        }
        val job = launch { AlarmEdits(repo, scheduler).upsert(a) }
        job.cancel() // caller goes away while the write is in flight
        gate.complete(Unit)
        job.join()
        assertTrue("reschedule must still run after the write committed", rescheduled)
    }
}
