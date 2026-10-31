package me.shawnsquire.tandem.sync

import me.shawnsquire.tandem.schedule.CommandStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandSnapshotTrackerTest {
    private val rejected = CommandStatus.REJECTED to "couldn't send"

    @Test fun `existing doc emits its status and reason`() {
        val t = CommandSnapshotTracker()
        assertEquals(CommandStatus.PENDING to null, t.update(true, true, CommandStatus.PENDING, null))
        assertEquals(CommandStatus.REJECTED to "expired", t.update(true, false, CommandStatus.REJECTED, "expired"))
    }

    @Test fun `missing doc before it was ever seen emits nothing`() {
        assertNull(CommandSnapshotTracker().update(false, false, null, null))
    }

    @Test fun `rollback after local write is reported as rejected`() {
        val t = CommandSnapshotTracker()
        t.update(true, true, CommandStatus.PENDING, null)
        assertEquals(rejected, t.update(false, false, null, null))
    }

    @Test fun `missing doc with pending writes is not a rejection`() {
        val t = CommandSnapshotTracker()
        t.update(true, true, CommandStatus.PENDING, null)
        assertNull(t.update(false, true, null, null))
    }

    @Test fun `doc with unknown status emits nothing`() {
        assertNull(CommandSnapshotTracker().update(true, false, null, null))
    }
}
