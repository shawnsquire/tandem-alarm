package me.shawnsquire.tandem.fakes

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.sync.PairingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class FakesTest {
    @Test
    fun alarmRepositoryEmitsSeededAlarms() = runTest {
        FakeAlarmRepository().alarms.test {
            assertEquals(listOf("Work", "Weekend"), awaitItem().map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setSkipUpdatesAndReEmits() = runTest {
        val repo = FakeAlarmRepository()
        val at = Instant.parse("2030-01-01T06:30:00Z")
        repo.alarms.test {
            awaitItem()
            repo.setSkip("alarm-work", at)
            assertEquals(at, awaitItem().first { it.id == "alarm-work" }.skippedOccurrenceAt)
            repo.setSkip("alarm-work", null)
            assertNull(awaitItem().first { it.id == "alarm-work" }.skippedOccurrenceAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setEnabledAndDelete() = runTest {
        val repo = FakeAlarmRepository()
        repo.setEnabled("alarm-work", false)
        assertEquals(false, repo.get("alarm-work")?.enabled)
        repo.delete("alarm-work")
        assertNull(repo.get("alarm-work"))
        assertEquals(1, repo.get("alarm-weekend")?.let { 1 })
    }

    @Test
    fun pairingJoinThenUnpair() = runTest {
        val repo = FakePairingRepository()
        repo.status.test {
            assertEquals(PairingStatus.Unpaired, awaitItem())
            repo.join(repo.createCode())
            assertEquals(true, awaitItem() is PairingStatus.Paired)
            repo.unpair()
            assertEquals(PairingStatus.Unpaired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
