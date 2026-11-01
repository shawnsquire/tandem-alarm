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
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.RingContext
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.RingOrigin
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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
        val store: DataStore<Preferences>,
        val removals: LinkRemovals,
    )

    private fun TestScope.env(
        alarms: List<Alarm> = listOf(weekly, oneShot),
        ring: RingState? = null,
        household: Flow<Household?> = flowOf(hh),
        name: String? = "Sam",
        wrapAlarms: (AlarmRepository) -> AlarmRepository = { it },
        partner: Flow<PartnerState?> = flowOf(null),
        inviteStore: DataStore<Preferences>? = null,
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
        val removals = LinkRemovals(ds)
        val applier = CommandApplierImpl(
            wrapAlarms(a), r, s, rc, remote, household, MutableStateFlow(name), partner, p, InviteLedger(inviteStore ?: ds, clock), removals, n, clock, backgroundScope,
        )
        return Env(a, r, s, rc, remote, n, p, applier, ds, removals)
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
        assertEquals(listOf(RingOrigin.REMOTE), e.ringing.dismissOrigins)
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

    /**
     * The controller reports a ring that started after the applier's validation read, as when a fire
     * wins the ringing lock first: the write must not happen.
     */
    private fun TestScope.racingEnv(alarm: Alarm, startedAt: Instant): Env =
        env(alarms = listOf(alarm)).also {
            it.ringing.ringingAlarmId = alarm.id
            it.ringing.ringStartedAt = startedAt
        }

    private suspend fun Env.assertRaceRejected(c: Command) {
        assertEquals(Outcome.Rejected("already ringing"), processed.outcome(c.id))
        assertEquals(
            listOf(FakeCommandRemote.Resolved("h1", c.id, CommandStatus.REJECTED, "already ringing")),
            remote.resolved,
        )
        assertTrue(notifier.calls.isEmpty())
        assertEquals(0, scheduler.rescheduleCount)
    }

    @Test fun `skip that loses the race to a ring is rejected and not written`() = runTest {
        val c = cmd(CommandType.SKIP_NEXT)
        val e = racingEnv(weekly, c.occurrenceAt!!)
        e.applier.apply(c)
        assertNull(e.alarms.get("a1")!!.skippedOccurrenceAt)
        assertTrue(e.alarms.get("a1")!!.enabled)
        e.assertRaceRejected(c)
    }

    @Test fun `a refused skip leaves a skip that was already there`() = runTest {
        val old = now.minusSeconds(86_400)
        val c = cmd(CommandType.SKIP_NEXT)
        val e = racingEnv(weekly.copy(skippedOccurrenceAt = old), c.occurrenceAt!!)
        e.applier.apply(c)
        assertEquals(old, e.alarms.get("a1")!!.skippedOccurrenceAt)
        e.assertRaceRejected(c)
    }

    @Test fun `disable that loses the race to a ring is rejected and not written`() = runTest {
        val c = cmd(CommandType.DISABLE)
        val e = racingEnv(weekly, c.occurrenceAt!!)
        e.applier.apply(c)
        assertTrue(e.alarms.get("a1")!!.enabled)
        e.assertRaceRejected(c)
    }

    @Test fun `one shot skip that loses the race to a ring is rejected and not written`() = runTest {
        val c = cmd(CommandType.SKIP_NEXT, oneShot)
        val e = racingEnv(oneShot, c.occurrenceAt!!)
        e.applier.apply(c)
        assertTrue(e.alarms.get("a2")!!.enabled)
        e.assertRaceRejected(c)
    }

    @Test fun `a ring that started before the occurrence still applies the skip`() = runTest {
        val c = cmd(CommandType.SKIP_NEXT)
        val e = racingEnv(weekly, c.occurrenceAt!!.minusSeconds(86_400))
        e.applier.apply(c)
        assertEquals(c.occurrenceAt, e.alarms.get("a1")!!.skippedOccurrenceAt)
        e.assertApplied(c)
    }

    // ---- Linked alarms (L4) ----

    private val ownerId = "o9"
    private val linkedRow = Alarm(
        "L1", "Work", 6, 30, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), true, null, null, null,
        link = AlarmLink("them", ownerId, LinkMode.RING, null, true, null, null, null, null),
    )
    private val x = nextOccurrence(linkedRow, now, zone)!!

    private fun fromOwner(
        type: CommandType,
        alarmId: String = ownerId,
        occurrenceAt: Instant? = x,
        ringId: String? = null,
        snoozedUntil: Instant? = null,
        id: String = "c1",
    ) = Command(id, "them", "me", type, alarmId, occurrenceAt, ringId, now.minusSeconds(60), now.plusSeconds(3600), snoozedUntil)

    private fun liveContext() = RingContext(
        ring = RingState("L1", "r1", now.minusSeconds(5), null, occurrenceAt = x),
        merged = emptyList(), snoozes = emptyList(), lastRang = null,
    )

    private fun rejection(e: Env, reason: String) =
        assertEquals(listOf(FakeCommandRemote.Resolved("h1", "c1", CommandStatus.REJECTED, reason)), e.remote.resolved)

    @Test fun `snooze from owner maps to the linked row`() = runTest {
        val e = env(alarms = listOf(weekly, linkedRow))
        e.ringing.ringContextResult = liveContext()
        val until = now.plusSeconds(600)
        e.applier.apply(fromOwner(CommandType.SNOOZE, snoozedUntil = until))
        assertEquals(
            listOf(FakeRingingController.SnoozeOccurrenceCall("L1", x, until, RingOrigin.REMOTE)),
            e.ringing.snoozedOccurrences,
        )
        assertEquals(listOf("L1"), e.ringing.ringContextAlarmIds)
        assertEquals(CommandStatus.APPLIED, e.remote.resolved.single().status)
        assertEquals(Outcome.Applied, e.processed.outcome("c1"))
        // The generic "turned on" text is for enable; a snooze posts nothing.
        assertTrue(e.notifier.calls.isEmpty())
    }

    @Test fun `stop by occurrence from owner dismisses the linked row's occurrence`() = runTest {
        val e = env(alarms = listOf(weekly, linkedRow))
        e.ringing.ringContextResult = liveContext()
        e.applier.apply(fromOwner(CommandType.STOP_RINGING))
        assertEquals(listOf(FakeRingingController.OccurrenceCall("L1", x, RingOrigin.REMOTE)), e.ringing.dismissedOccurrences)
        assertTrue(e.ringing.dismissed.isEmpty())
        assertEquals(CommandStatus.APPLIED, e.remote.resolved.single().status)
    }

    @Test fun `stop by ringId from owner maps to the linked row id`() = runTest {
        val e = env(alarms = listOf(weekly, linkedRow), ring = RingState("L1", "r1", now.minusSeconds(5), null))
        e.applier.apply(fromOwner(CommandType.STOP_RINGING, occurrenceAt = null, ringId = "r1"))
        assertEquals(listOf("r1"), e.ringing.dismissed)
        assertEquals(listOf(RingOrigin.REMOTE), e.ringing.dismissOrigins)
        assertEquals(CommandStatus.APPLIED, e.remote.resolved.single().status)
    }

    @Test fun `ring commands onto a row pending LEFT removal are rejected`() = runTest {
        for (type in listOf(CommandType.STOP_RINGING, CommandType.SNOOZE)) {
            val e = env(alarms = listOf(weekly, linkedRow))
            e.ringing.ringContextResult = liveContext()
            e.removals.add("L1", RemovalReason.LEFT)
            e.applier.apply(fromOwner(type, snoozedUntil = now.plusSeconds(600)))
            assertTrue(e.ringing.dismissedOccurrences.isEmpty())
            assertTrue(e.ringing.snoozedOccurrences.isEmpty())
            assertEquals(0, e.scheduler.rescheduleCount)
            assertTrue(e.notifier.calls.isEmpty())
            rejection(e, "left this alarm")
        }
    }

    @Test fun `ring commands onto a row pending another removal still apply`() = runTest {
        for (reason in listOf(RemovalReason.OWNER_DELETED, RemovalReason.UNPAIRED)) {
            val e = env(alarms = listOf(weekly, linkedRow))
            e.ringing.ringContextResult = liveContext()
            e.removals.add("L1", reason)
            e.applier.apply(fromOwner(CommandType.STOP_RINGING))
            assertEquals(1, e.ringing.dismissedOccurrences.size)
            assertEquals(CommandStatus.APPLIED, e.remote.resolved.single().status)
        }
    }

    @Test fun `a mirrored stop by occurrence posts no notification`() = runTest {
        val e = env(alarms = listOf(weekly, linkedRow))
        e.ringing.ringContextResult = liveContext()
        e.applier.apply(fromOwner(CommandType.STOP_RINGING))
        assertEquals(CommandStatus.APPLIED, e.remote.resolved.single().status)
        assertTrue(e.notifier.calls.isEmpty())
    }

    @Test fun `a stop by ringId still notifies once`() = runTest {
        val e = env(ring = RingState("a1", "r1", now.minusSeconds(10), null))
        val c = cmd(CommandType.STOP_RINGING, occurrenceAt = null, ringId = "r1")
        e.applier.apply(c)
        assertEquals(1, e.notifier.calls.size)
        assertEquals(c, e.notifier.calls[0].cmd)
    }

    @Test fun `stop by ringId for a ring on another alarm is rejected`() = runTest {
        val e = env(alarms = listOf(weekly, linkedRow), ring = RingState("a1", "r1", now.minusSeconds(5), null))
        e.applier.apply(fromOwner(CommandType.STOP_RINGING, occurrenceAt = null, ringId = "r1"))
        assertTrue(e.ringing.dismissed.isEmpty())
        rejection(e, "not ringing")
    }

    @Test fun `ring command aimed at a linked row's own id is rejected`() = runTest {
        val e = env(alarms = listOf(weekly, linkedRow))
        e.ringing.ringContextResult = liveContext()
        e.applier.apply(fromOwner(CommandType.STOP_RINGING, alarmId = "L1"))
        assertTrue(e.ringing.dismissedOccurrences.isEmpty())
        assertEquals(CommandStatus.REJECTED, e.remote.resolved.single().status)
        val e2 = env(alarms = listOf(weekly, linkedRow))
        e2.ringing.ringContextResult = liveContext()
        e2.applier.apply(fromOwner(CommandType.SNOOZE, alarmId = "L1", snoozedUntil = now.plusSeconds(60)))
        assertTrue(e2.ringing.snoozedOccurrences.isEmpty())
        assertEquals(CommandStatus.REJECTED, e2.remote.resolved.single().status)
    }

    @Test fun `ring command from someone other than the link owner is not mapped`() = runTest {
        val e = env(alarms = listOf(weekly, linkedRow))
        e.ringing.ringContextResult = liveContext()
        e.applier.apply(fromOwner(CommandType.STOP_RINGING).copy(from = "stranger"))
        assertTrue(e.ringing.dismissedOccurrences.isEmpty())
        rejection(e, "alarm not found")
    }

    @Test fun `skip and disable aimed at a linked row's owner id are not mapped`() = runTest {
        for (type in listOf(CommandType.SKIP_NEXT, CommandType.DISABLE, CommandType.UNSKIP, CommandType.ENABLE)) {
            val e = env(alarms = listOf(weekly, linkedRow))
            e.applier.apply(fromOwner(type))
            assertEquals(linkedRow, e.alarms.get("L1"))
            rejection(e, "alarm not found")
        }
    }

    @Test fun `skip aimed at a linked row's own id is rejected`() = runTest {
        val e = env(alarms = listOf(weekly, linkedRow))
        e.applier.apply(fromOwner(CommandType.SKIP_NEXT, alarmId = "L1"))
        assertEquals(linkedRow, e.alarms.get("L1"))
        rejection(e, "alarm not found")
    }

    @Test fun `validate gets the ring context`() = runTest {
        // Without a live ring in the context the early-window rule rejects; with one it applies.
        val e = env(alarms = listOf(weekly, linkedRow))
        e.applier.apply(fromOwner(CommandType.STOP_RINGING))
        assertEquals(listOf("L1"), e.ringing.ringContextAlarmIds)
        assertTrue(e.ringing.dismissedOccurrences.isEmpty())
        rejection(e, "already rang or changed")
    }

    @Test fun `own alarm ring command also builds a context`() = runTest {
        val ownX = nextOccurrence(weekly, now, zone)!!
        val e = env()
        e.ringing.ringContextResult = RingContext(
            RingState("a1", "r1", now.minusSeconds(5), null, occurrenceAt = ownX), emptyList(), emptyList(), null,
        )
        e.applier.apply(cmd(CommandType.STOP_RINGING, occurrenceAt = ownX))
        assertEquals(listOf("a1"), e.ringing.ringContextAlarmIds)
        assertEquals(listOf(FakeRingingController.OccurrenceCall("a1", ownX, RingOrigin.REMOTE)), e.ringing.dismissedOccurrences)
        assertTrue(e.notifier.calls.isEmpty()) // a mirrored dismiss shows on the ringing screen only
    }

    @Test fun `skip on a solo skipped occurrence upgrades it to a shared skip`() = runTest {
        val e = env(alarms = listOf(weekly.copy(soloSkippedOccurrenceAt = nextOccurrence(weekly, now, zone))))
        val c = cmd(CommandType.SKIP_NEXT, occurrenceAt = nextOccurrence(weekly, now, zone))
        e.applier.apply(c)
        val a = e.alarms.get("a1")!!
        assertEquals(c.occurrenceAt, a.skippedOccurrenceAt)
        assertNull(a.soloSkippedOccurrenceAt)
        e.assertApplied(c)
    }

    @Test fun `skip for a different occurrence leaves the solo skip`() = runTest {
        val solo = nextOccurrence(weekly, now, zone)!!
        val later = nextOccurrence(weekly, solo.plusSeconds(1), zone)!!
        val e = env(alarms = listOf(weekly.copy(soloSkippedOccurrenceAt = solo)))
        // Full-view next occurrence (the Partner tab's X') is accepted by validate and isn't the solo one.
        e.applier.apply(cmd(CommandType.SKIP_NEXT, occurrenceAt = later))
        val a = e.alarms.get("a1")!!
        assertEquals(later, a.skippedOccurrenceAt)
        assertEquals(solo, a.soloSkippedOccurrenceAt)
    }

    @Test fun `one shot skip clears a solo skip`() = runTest {
        val ox = nextOccurrence(oneShot, now, zone)!!
        val e = env(alarms = listOf(oneShot.copy(soloSkippedOccurrenceAt = ox)))
        e.applier.apply(cmd(CommandType.SKIP_NEXT, oneShot, occurrenceAt = ox))
        val a = e.alarms.get("a2")!!
        assertFalse(a.enabled)
        assertNull(a.soloSkippedOccurrenceAt)
    }

    @Test fun `unskip clears only the shared skip`() = runTest {
        val shared = nextOccurrence(weekly, now, zone)!!
        val solo = nextOccurrence(weekly, shared.plusSeconds(1), zone)!!
        val e = env(alarms = listOf(weekly.copy(skippedOccurrenceAt = shared, soloSkippedOccurrenceAt = solo)))
        e.applier.apply(cmd(CommandType.UNSKIP, occurrenceAt = shared))
        val a = e.alarms.get("a1")!!
        assertNull(a.skippedOccurrenceAt)
        assertEquals(solo, a.soloSkippedOccurrenceAt)
    }

    private fun partnerWith(vararg ids: String) = PartnerState(
        "Sam",
        ids.map { PartnerAlarm(it, "Gym", 7, 15, setOf(DayOfWeek.MONDAY), true, null, null) },
        null, null,
    )

    private fun invite() = fromOwner(CommandType.INVITE_LINK, occurrenceAt = null)

    @Test fun `invite posts a notification and stays pending`() = runTest {
        val ps = partnerWith(ownerId)
        val e = env(partner = flowOf(ps))
        val c = invite()
        e.applier.apply(c)
        assertEquals(listOf(FakeCommandNotifier.Invite(c, ps.alarms.single(), "Sam")), e.notifier.invites)
        assertTrue(e.remote.resolved.isEmpty())
        assertNull(e.processed.outcome("c1"))
        assertEquals(0, e.scheduler.rescheduleCount)
        assertTrue(e.notifier.calls.isEmpty())
        // Re-delivery doesn't post again and still resolves nothing.
        e.applier.apply(c)
        assertEquals(1, e.notifier.invites.size)
        assertTrue(e.remote.resolved.isEmpty())
    }

    @Test fun `an already answered invite resolves from the record`() = runTest {
        val e = env(partner = flowOf(partnerWith(ownerId)))
        e.processed.record("c1", Outcome.Rejected("declined"), now.plusSeconds(60))
        e.applier.apply(invite())
        assertTrue(e.notifier.invites.isEmpty())
        assertEquals(CommandStatus.REJECTED, e.remote.resolved.single().status)
    }

    @Test fun `invite for an alarm not yet in the partner state stays pending`() = runTest {
        val e = env(partner = flowOf(partnerWith("other")))
        e.applier.apply(invite())
        assertTrue(e.notifier.invites.isEmpty())
        assertTrue(e.remote.resolved.isEmpty())
        assertNull(e.processed.outcome("c1"))
        assertEquals(0, e.scheduler.rescheduleCount)
    }

    @Test fun `invite notifies once the alarm shows up on a later delivery`() = runTest {
        val partner = MutableStateFlow<PartnerState?>(partnerWith("other"))
        val e = env(partner = partner)
        val c = invite()
        e.applier.apply(c)
        assertTrue(e.notifier.invites.isEmpty())
        partner.value = partnerWith("other", ownerId)
        e.applier.apply(c)
        assertEquals(1, e.notifier.invites.size)
        assertTrue(e.remote.resolved.isEmpty())
        assertNull(e.processed.outcome("c1"))
    }

    @Test fun `invite for one alarm posts when a different alarm is already joined`() = runTest {
        val e = env(alarms = listOf(weekly, linkedRow), partner = flowOf(partnerWith(ownerId, "o10")))
        e.applier.apply(fromOwner(CommandType.INVITE_LINK, alarmId = "o10", occurrenceAt = null))
        assertEquals(1, e.notifier.invites.size)
        assertTrue(e.remote.resolved.isEmpty())
    }

    @Test fun `invite for an alarm already joined from this owner resolves applied`() = runTest {
        val e = env(alarms = listOf(weekly, linkedRow), partner = flowOf(partnerWith(ownerId)))
        e.applier.apply(invite())
        assertTrue(e.notifier.invites.isEmpty())
        assertTrue(e.notifier.calls.isEmpty())
        assertEquals(0, e.scheduler.rescheduleCount)
        assertEquals(listOf(FakeCommandRemote.Resolved("h1", "c1", CommandStatus.APPLIED, null)), e.remote.resolved)
        assertEquals(Outcome.Applied, e.processed.outcome("c1"))
    }

    @Test fun `invite for an alarm joined from someone else is rejected`() = runTest {
        val other = linkedRow.copy(link = linkedRow.link!!.copy(ownerUid = "stranger"))
        val e = env(alarms = listOf(weekly, other), partner = flowOf(partnerWith(ownerId)))
        e.applier.apply(invite())
        assertTrue(e.notifier.invites.isEmpty())
        rejection(e, "already joined")
    }

    @Test fun `a pending invite is notified once per process`() = runTest {
        val e = env(partner = flowOf(partnerWith(ownerId)))
        val c = invite()
        e.applier.apply(c)
        e.applier.apply(c)
        assertEquals(1, e.notifier.invites.size)
        assertTrue(e.remote.resolved.isEmpty())
    }

    @Test fun `a notified invite is not posted again after a restart`() = runTest {
        val ps = flowOf(partnerWith(ownerId))
        val first = env(partner = ps)
        val c = invite()
        first.applier.apply(c)
        assertEquals(1, first.notifier.invites.size)
        val second = env(partner = ps, inviteStore = first.store)
        second.applier.apply(c)
        assertTrue(second.notifier.invites.isEmpty())
        assertTrue(second.remote.resolved.isEmpty())
    }

    @Test fun `invite with unknown partner state stays pending`() = runTest {
        val e = env(partner = flowOf(null))
        e.applier.apply(invite())
        assertTrue(e.notifier.invites.isEmpty())
        assertTrue(e.remote.resolved.isEmpty())
        assertNull(e.processed.outcome("c1"))
    }

    @Test fun `expired invite is rejected and not posted`() = runTest {
        val e = env(partner = flowOf(partnerWith(ownerId)))
        e.applier.apply(invite().copy(expiresAt = now))
        assertTrue(e.notifier.invites.isEmpty())
        rejection(e, "expired")
    }
}
