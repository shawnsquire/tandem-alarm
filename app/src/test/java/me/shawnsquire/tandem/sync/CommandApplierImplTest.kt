package me.shawnsquire.tandem.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.fakes.FakeRingingController
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.nextOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class CommandApplierImplTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-03-02T05:00:00Z") // Monday
    private val clock = Clock.fixed(now, zone)
    private val hh = Household("h1", "me", "them")

    private val weekly = Alarm(
        "a1", "Work", 6, 30, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), true, null, null, null,
    )
    private val oneShot = weekly.copy(id = "a2", days = emptySet())

    private class Env(
        val alarms: FakeAlarmRepository,
        val ring: FakeRingStateStore,
        val scheduler: FakeAlarmScheduler,
        val ringing: FakeRingingController,
        val remote: FakeCommandRemote,
        val notifier: FakeCommandNotifier,
        val processed: ProcessedCommands,
        val applier: CommandApplierImpl,
    )

    private fun TestScope.env(
        alarms: List<Alarm> = listOf(weekly, oneShot),
        ring: RingState? = null,
        household: Flow<Household?> = flowOf(hh),
        name: String? = "Sam",
    ): Env {
        val a = FakeAlarmRepository(alarms)
        val r = FakeRingStateStore(ring)
        val s = FakeAlarmScheduler()
        val rc = FakeRingingController()
        val remote = FakeCommandRemote()
        val n = FakeCommandNotifier()
        val ds = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "t${n.hashCode()}.preferences_pb") },
        )
        val p = ProcessedCommands(ds, clock)
        return Env(a, r, s, rc, remote, n, p, CommandApplierImpl(a, r, s, rc, remote, household, flowOf(name), p, n, clock))
    }

    private fun cmd(
        type: CommandType,
        alarm: Alarm = weekly,
        occurrenceAt: Instant? = nextOccurrence(alarm, now, zone),
        ringId: String? = null,
        id: String = "c1",
        to: String = "me",
        expiresAt: Instant = now.plusSeconds(3600),
    ) = Command(id, "them", to, type, alarm.id, occurrenceAt, ringId, now.minusSeconds(60), expiresAt)

    private fun Env.assertApplied(c: Command, turnedOff: Boolean = false) {
        assertEquals(1, scheduler.rescheduleCount)
        assertEquals(listOf(FakeCommandRemote.Resolved("h1", c.id, CommandStatus.APPLIED, null)), remote.resolved)
        assertEquals(1, notifier.calls.size)
        assertEquals("Sam", notifier.calls[0].partnerName)
        assertEquals(turnedOff, notifier.calls[0].turnedOff)
    }

    @Test fun `skip next sets skip`() = runTest {
        val e = env()
        val c = cmd(CommandType.SKIP_NEXT)
        e.applier.apply(c)
        assertEquals(c.occurrenceAt, e.alarms.get("a1")!!.skippedOccurrenceAt)
        assertTrue(e.alarms.get("a1")!!.enabled)
        e.assertApplied(c)
    }

    @Test fun `skip next on one shot turns it off`() = runTest {
        val e = env()
        val c = cmd(CommandType.SKIP_NEXT, oneShot)
        e.applier.apply(c)
        val a = e.alarms.get("a2")!!
        assertFalse(a.enabled)
        assertNull(a.skippedOccurrenceAt)
        e.assertApplied(c, turnedOff = true)
    }

    @Test fun `unskip clears skip`() = runTest {
        val skipped = nextOccurrence(weekly, now, zone)!!
        val e = env(alarms = listOf(weekly.copy(skippedOccurrenceAt = skipped)))
        val c = cmd(CommandType.UNSKIP, occurrenceAt = skipped)
        e.applier.apply(c)
        assertNull(e.alarms.get("a1")!!.skippedOccurrenceAt)
        e.assertApplied(c)
    }

    @Test fun `disable and enable`() = runTest {
        val e = env()
        val c = cmd(CommandType.DISABLE)
        e.applier.apply(c)
        assertFalse(e.alarms.get("a1")!!.enabled)
        e.assertApplied(c)

        val e2 = env(alarms = listOf(weekly.copy(enabled = false)))
        val c2 = cmd(CommandType.ENABLE, occurrenceAt = null)
        e2.applier.apply(c2)
        assertTrue(e2.alarms.get("a1")!!.enabled)
        e2.assertApplied(c2)
    }

    @Test fun `stop ringing dismisses`() = runTest {
        val e = env(ring = RingState("a1", "r1", now.minusSeconds(10), null))
        val c = cmd(CommandType.STOP_RINGING, occurrenceAt = null, ringId = "r1")
        e.applier.apply(c)
        assertEquals(listOf("r1"), e.ringing.dismissed)
        e.assertApplied(c)
    }

    @Test fun `stale occurrence is rejected`() = runTest {
        val e = env()
        val c = cmd(CommandType.SKIP_NEXT, occurrenceAt = now.minusSeconds(86_400))
        e.applier.apply(c)
        assertNull(e.alarms.get("a1")!!.skippedOccurrenceAt)
        assertEquals(0, e.scheduler.rescheduleCount)
        assertTrue(e.notifier.calls.isEmpty())
        assertEquals(
            listOf(FakeCommandRemote.Resolved("h1", "c1", CommandStatus.REJECTED, "already rang or changed")),
            e.remote.resolved,
        )
    }

    @Test fun `expired is rejected`() = runTest {
        val e = env()
        e.applier.apply(cmd(CommandType.DISABLE, expiresAt = now))
        assertTrue(e.alarms.get("a1")!!.enabled)
        assertEquals(CommandStatus.REJECTED, e.remote.resolved.single().status)
        assertEquals("expired", e.remote.resolved.single().reason)
    }

    @Test fun `duplicate is not reapplied but resolve is resent`() = runTest {
        val e = env()
        val c = cmd(CommandType.DISABLE)
        e.applier.apply(c)
        e.alarms.setEnabled("a1", true)
        e.applier.apply(c)
        assertTrue(e.alarms.get("a1")!!.enabled)
        assertEquals(1, e.notifier.calls.size)
        assertEquals(1, e.scheduler.rescheduleCount)
        assertEquals(2, e.remote.resolved.size)
        assertEquals(e.remote.resolved[0], e.remote.resolved[1])
    }

    @Test fun `non pending doc is not applied`() = runTest {
        val e = env()
        e.remote.status = CommandStatus.APPLIED
        e.applier.apply(cmd(CommandType.DISABLE))
        assertTrue(e.alarms.get("a1")!!.enabled)
        assertTrue(e.remote.resolved.isEmpty())
        assertTrue(e.notifier.calls.isEmpty())
    }

    @Test fun `unknown doc status still applies`() = runTest {
        val e = env()
        e.remote.status = null
        e.applier.apply(cmd(CommandType.DISABLE))
        assertFalse(e.alarms.get("a1")!!.enabled)
    }

    @Test fun `applyPending skips the status read`() = runTest {
        val e = env()
        e.remote.status = CommandStatus.APPLIED
        e.applier.applyPending("h1", cmd(CommandType.DISABLE))
        assertFalse(e.alarms.get("a1")!!.enabled)
    }

    @Test fun `command for someone else is ignored`() = runTest {
        val e = env()
        e.applier.apply(cmd(CommandType.DISABLE, to = "other"))
        assertTrue(e.alarms.get("a1")!!.enabled)
        assertTrue(e.remote.resolved.isEmpty())
    }

    @Test fun `unknown household applies locally without resolving`() = runTest {
        val e = env(household = flowOf(null))
        e.applier.apply(cmd(CommandType.DISABLE))
        assertFalse(e.alarms.get("a1")!!.enabled)
        assertTrue(e.remote.resolved.isEmpty())
    }

    @Test fun `notifier failure does not fail apply`() = runTest {
        val e = env()
        e.notifier.failure = SecurityException("no permission")
        e.applier.apply(cmd(CommandType.DISABLE))
        assertFalse(e.alarms.get("a1")!!.enabled)
        assertEquals(CommandStatus.APPLIED, e.remote.resolved.single().status)
    }

    @Test fun `blank partner name falls back`() = runTest {
        val e = env(name = " ")
        e.applier.apply(cmd(CommandType.DISABLE))
        assertEquals("Your partner", e.notifier.calls.single().partnerName)
    }
}
