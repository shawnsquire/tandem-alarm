package me.shawnsquire.tandem.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.data.AlarmRepository
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
        wrapAlarms: (AlarmRepository) -> AlarmRepository = { it },
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
        val applier = CommandApplierImpl(
            wrapAlarms(a), r, s, rc, remote, household, MutableStateFlow(name), p, n, clock, backgroundScope,
        )
        return Env(a, r, s, rc, remote, n, p, applier)
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

    @Test fun `unknown doc status is not applied and not verified`() = runTest {
        val e = env()
        e.remote.status = null
        try {
            e.applier.apply(cmd(CommandType.DISABLE))
            org.junit.Assert.fail("expected CommandNotVerifiedException")
        } catch (_: CommandNotVerifiedException) {
        }
        assertTrue(e.alarms.get("a1")!!.enabled)
        assertTrue(e.remote.resolved.isEmpty())
        assertTrue(e.notifier.calls.isEmpty())
    }

    @Test fun `applyPending skips the status read`() = runTest {
        val e = env()
        e.remote.status = CommandStatus.APPLIED
        e.applier.applyPending(hh, cmd(CommandType.DISABLE))
        assertFalse(e.alarms.get("a1")!!.enabled)
        assertEquals(0, e.remote.statusCalls)
        assertEquals(CommandStatus.APPLIED, e.remote.resolved.single().status)
    }

    @Test fun `applyPending ignores command for someone else`() = runTest {
        val e = env()
        e.applier.applyPending(hh, cmd(CommandType.DISABLE, to = "other"))
        assertTrue(e.alarms.get("a1")!!.enabled)
        assertTrue(e.remote.resolved.isEmpty())
    }

    @Test fun `command for someone else is ignored`() = runTest {
        val e = env()
        e.applier.apply(cmd(CommandType.DISABLE, to = "other"))
        assertTrue(e.alarms.get("a1")!!.enabled)
        assertTrue(e.remote.resolved.isEmpty())
    }

    @Test fun `unknown household is not applied and not verified`() = runTest {
        val e = env(household = flowOf(null))
        try {
            e.applier.apply(cmd(CommandType.DISABLE))
            org.junit.Assert.fail("expected CommandNotVerifiedException")
        } catch (_: CommandNotVerifiedException) {
        }
        assertTrue(e.alarms.get("a1")!!.enabled)
        assertTrue(e.remote.resolved.isEmpty())
        assertEquals(0, e.remote.statusCalls)
    }

    private class SlowSkipAlarms(private val inner: AlarmRepository, private val ms: Long) : AlarmRepository by inner {
        override suspend fun setSkip(id: String, occurrence: Instant?) {
            delay(ms)
            inner.setSkip(id, occurrence)
        }
    }

    @Test fun `caller timeout does not tear the command`() = runTest {
        val e = env(wrapAlarms = { SlowSkipAlarms(it, 5_000) })
        val c = cmd(CommandType.SKIP_NEXT)
        val result = withTimeoutOrNull(1_000) { e.applier.applyPending(hh, c) }
        assertNull(result)
        assertTrue(e.remote.resolved.isEmpty())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(c.occurrenceAt, e.alarms.get("a1")!!.skippedOccurrenceAt)
        assertEquals(CommandStatus.APPLIED, e.remote.resolved.single().status)
        assertEquals(1, e.notifier.calls.size)
        assertEquals(1, e.scheduler.rescheduleCount)
        // Recorded: a repeat is a duplicate and is not reapplied.
        e.alarms.setSkip("a1", null)
        e.applier.applyPending(hh, c)
        assertNull(e.alarms.get("a1")!!.skippedOccurrenceAt)
    }

    @Test fun `status read does not hold the lock`() = runTest {
        val e = env()
        val gate = CompletableDeferred<CommandStatus?>()
        e.remote.statusBlock = { gate.await() }
        val slow = launch { e.applier.apply(cmd(CommandType.DISABLE, id = "slow")) }
        runCurrent()
        assertEquals(1, e.remote.statusCalls)
        e.applier.applyPending(hh, cmd(CommandType.UNSKIP, id = "fast", occurrenceAt = null))
        assertEquals(listOf("fast"), e.remote.resolved.map { it.id })
        assertTrue(slow.isActive)
        gate.complete(CommandStatus.PENDING)
        runCurrent()
        assertEquals(listOf("fast", "slow"), e.remote.resolved.map { it.id })
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

    /** Starts a ring for the alarm right after the first write, like a receiver racing the applier. */
    private class RingsAfterWrite(
        private val inner: AlarmRepository,
        private val ring: () -> FakeRingStateStore,
        private val startedAt: Instant,
    ) : AlarmRepository by inner {
        override suspend fun setSkip(id: String, occurrence: Instant?) {
            inner.setSkip(id, occurrence)
            ring().set(RingState(id, "r1", startedAt, null))
        }

        override suspend fun setEnabled(id: String, enabled: Boolean) {
            inner.setEnabled(id, enabled)
            if (!enabled) ring().set(RingState(id, "r1", startedAt, null))
        }
    }

    private fun TestScope.racingEnv(
        alarm: Alarm,
        startedAt: Instant,
    ): Env {
        lateinit var e: Env
        e = env(alarms = listOf(alarm), wrapAlarms = { RingsAfterWrite(it, { e.ring }, startedAt) })
        return e
    }

    private fun Env.assertRaceRejected(c: Command) {
        assertEquals(
            listOf(FakeCommandRemote.Resolved("h1", c.id, CommandStatus.REJECTED, "already ringing")),
            remote.resolved,
        )
        assertTrue(notifier.calls.isEmpty())
        assertEquals(1, scheduler.rescheduleCount)
    }

    @Test fun `skip that loses the race to a ring is undone`() = runTest {
        val c = cmd(CommandType.SKIP_NEXT)
        val e = racingEnv(weekly, c.occurrenceAt!!)
        e.applier.apply(c)
        assertNull(e.alarms.get("a1")!!.skippedOccurrenceAt)
        assertTrue(e.alarms.get("a1")!!.enabled)
        e.assertRaceRejected(c)
    }

    @Test fun `undoing a late skip restores a skip that was already there`() = runTest {
        val old = now.minusSeconds(86_400)
        val c = cmd(CommandType.SKIP_NEXT)
        val e = racingEnv(weekly.copy(skippedOccurrenceAt = old), c.occurrenceAt!!)
        e.applier.apply(c)
        assertEquals(old, e.alarms.get("a1")!!.skippedOccurrenceAt)
        e.assertRaceRejected(c)
    }

    @Test fun `disable that loses the race to a ring is re-enabled`() = runTest {
        val c = cmd(CommandType.DISABLE)
        val e = racingEnv(weekly, c.occurrenceAt!!)
        e.applier.apply(c)
        assertTrue(e.alarms.get("a1")!!.enabled)
        e.assertRaceRejected(c)
    }

    @Test fun `one shot skip that loses the race to a ring is re-enabled`() = runTest {
        val c = cmd(CommandType.SKIP_NEXT, oneShot)
        val e = racingEnv(oneShot, c.occurrenceAt!!)
        e.applier.apply(c)
        assertTrue(e.alarms.get("a2")!!.enabled)
        e.assertRaceRejected(c)
    }

    @Test fun `a ring that started before the occurrence does not undo the skip`() = runTest {
        val c = cmd(CommandType.SKIP_NEXT)
        val e = racingEnv(weekly, c.occurrenceAt!!.minusSeconds(86_400))
        e.applier.apply(c)
        assertEquals(c.occurrenceAt, e.alarms.get("a1")!!.skippedOccurrenceAt)
        e.assertApplied(c)
    }
}
