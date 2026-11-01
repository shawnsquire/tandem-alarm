package me.shawnsquire.tandem.sync

import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LinkRepositoryImplTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val hh = Household("h1", "me", "them")
    private val now = Instant.parse("2026-03-02T05:00:00Z")
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
    private var storeCount = 0

    private class RecordingSender : CommandSender {
        val sent = mutableListOf<Triple<CommandType, String, Instant?>>()
        val status = MutableSharedFlow<Pair<CommandStatus, String?>>(replay = 1)
        override fun send(
            type: CommandType, alarmId: String, occurrenceAt: Instant?, ringId: String?, snoozedUntil: Instant?,
        ): Flow<Pair<CommandStatus, String?>> {
            sent += Triple(type, alarmId, occurrenceAt)
            return status
        }
    }

    private inner class Env(
        val scope: TestScope, rows: List<Alarm>, partner: PartnerState?, household: Household?,
        wrap: (FakeCommandRemote) -> CommandRemote = { it },
        householdFlow: Flow<Household?>? = null,
    ) {
        val fake = FakeAlarmRepository(rows)
        val partner = MutableStateFlow(partner)
        val household = MutableStateFlow(household)
        val sender = RecordingSender()
        val remote = FakeCommandRemote()
        val scheduler = FakeAlarmScheduler()
        val notifier = FakeCommandNotifier()
        val removalCalls = mutableListOf<Pair<String, RemovalReason>>()
        private fun store() = createSyncDataStore(
            File(tmp.root, "s-${storeCount++}.preferences_pb"), scope.backgroundScope,
        )
        val removals = LinkRemovals(store())
        val processed = ProcessedCommands(store(), Clock.fixed(now, ZoneOffset.UTC))
        val repo = LinkRepositoryImpl(
            fake, this.partner, householdFlow ?: this.household, sender, wrap(remote), processed, removals,
            { id, r -> removalCalls += id to r }, scheduler, notifier,
            Clock.fixed(now, ZoneOffset.UTC), scope.backgroundScope,
        )
        suspend fun rows() = fake.linkedAlarms.first()
    }

    private fun ownerAlarm(id: String = "o1", skip: Instant? = null, solo: Instant? = null) = PartnerAlarm(
        id, "Wake", 6, 30, weekdays, true, now.plusSeconds(3600), skip, soloSkippedOccurrenceAt = solo,
    )

    private fun state(vararg a: PartnerAlarm, name: String = "Shawn") = PartnerState(name, a.toList(), null, null)

    private fun linked(
        id: String = "row1", pending: PendingBoth? = null, joinerEnabled: Boolean = true, ownerAlarmId: String = "o1",
    ) = Alarm(
        id, "Wake", 6, 30, weekdays, true, null, null, null,
        link = AlarmLink("them", ownerAlarmId, LinkMode.NOTIFY, null, joinerEnabled, pending, null, null, null),
    )

    private fun TestScope.env(
        rows: List<Alarm> = emptyList(),
        partner: PartnerState? = state(ownerAlarm()),
        household: Household? = hh,
    ) = Env(this, rows, partner, household)

    @Test
    fun joinCreatesRingRowWithoutSoloSkip() = runTest {
        val e = env(partner = state(ownerAlarm(skip = now.plusSeconds(5), solo = now.plusSeconds(9))))
        val r = e.repo.join("o1") as LinkResult.Joined
        val row = e.rows().single()
        assertEquals(r.alarmId, row.id)
        assertEquals("Wake", row.label)
        assertNull(row.ringtoneUri)
        assertNull(row.wallpaperPath)
        assertNull(row.soloSkippedOccurrenceAt)
        val l = row.link!!
        assertEquals(LinkMode.RING, l.mode)
        assertTrue(l.joinerEnabled)
        assertEquals("them", l.ownerUid)
        assertEquals(now.plusSeconds(5), l.ownerSkippedOccurrenceAt)
        assertNull(l.pendingBoth)
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test
    fun joinIsIdempotent() = runTest {
        val e = env()
        val a = e.repo.join("o1")
        val b = e.repo.join("o1")
        assertEquals(a, b)
        assertEquals(1, e.rows().size)
    }

    @Test
    fun rejoinCancelsPendingLeave() = runTest {
        val e = env(rows = listOf(linked()))
        e.removals.add("row1", RemovalReason.LEFT)
        assertEquals(LinkResult.Joined("row1"), e.repo.join("o1"))
        assertTrue(e.removals.all().isEmpty())
    }

    @Test
    fun joinNotPaired() = runTest {
        assertEquals(LinkResult.Failed("not paired"), env(household = null).repo.join("o1"))
        assertEquals(LinkResult.Failed("not paired"), env(partner = null).repo.join("o1"))
    }

    @Test
    fun joinAcceptsOpenInvites() = runTest {
        val e = env()
        e.remote.fetchResult = listOf(
            Command("i1", "them", "me", CommandType.INVITE_LINK, "o1", null, null, now, now.plusSeconds(100)),
            Command("i2", "them", "me", CommandType.INVITE_LINK, "other", null, null, now, now.plusSeconds(100)),
            Command("i3", "x", "me", CommandType.INVITE_LINK, "o1", null, null, now, now.plusSeconds(100)),
        )
        e.repo.join("o1")
        runCurrent()
        assertEquals(listOf(FakeCommandRemote.Resolved("h1", "i1", CommandStatus.APPLIED, null)), e.remote.resolved)
        assertEquals(Outcome.Applied, e.processed.outcome("i1"))
    }

    @Test
    fun acceptInviteJoinsAndResolvesApplied() = runTest {
        val e = env()
        val r = e.repo.acceptInvite("i1", "o1")
        runCurrent()
        assertTrue(r is LinkResult.Joined)
        assertEquals(listOf(FakeCommandRemote.Resolved("h1", "i1", CommandStatus.APPLIED, null)), e.remote.resolved)
    }

    @Test
    fun acceptInviteForDeletedAlarmRejects() = runTest {
        val e = env(partner = state(name = "Shawn"))
        val r = e.repo.acceptInvite("i1", "o1")
        assertEquals(LinkResult.Failed("Shawn deleted this alarm"), r)
        assertEquals(
            listOf(FakeCommandRemote.Resolved("h1", "i1", CommandStatus.REJECTED, "Shawn deleted this alarm")),
            e.remote.resolved,
        )
        assertEquals(Outcome.Rejected("Shawn deleted this alarm"), e.processed.outcome("i1"))
        assertTrue(e.rows().isEmpty())
    }

    @Test
    fun acceptInviteNotPairedLeavesCommandPending() = runTest {
        val e = env(household = null)
        assertEquals(LinkResult.Failed("not paired"), e.repo.acceptInvite("i1", "o1"))
        assertTrue(e.remote.resolved.isEmpty())
    }

    @Test
    fun declineRejects() = runTest {
        val e = env()
        e.repo.declineInvite("i1")
        assertEquals(listOf(FakeCommandRemote.Resolved("h1", "i1", CommandStatus.REJECTED, "declined")), e.remote.resolved)
        assertEquals(Outcome.Rejected("declined"), e.processed.outcome("i1"))
        assertTrue(e.rows().isEmpty())
    }

    @Test
    fun leaveRequestsRemovalAndReschedules() = runTest {
        val e = env(rows = listOf(linked()))
        e.repo.leave("row1")
        assertEquals(listOf("row1" to RemovalReason.LEFT), e.removalCalls)
        assertEquals(1, e.scheduler.rescheduleCount)
        e.repo.leave("nope")
        assertEquals(1, e.removalCalls.size)
    }

    @Test
    fun settersWriteAndReschedule() = runTest {
        val e = env(rows = listOf(linked()))
        e.repo.setMode("row1", LinkMode.RING)
        e.repo.setOverrides("row1", "L", "R", "W")
        e.repo.setJoinerEnabled("row1", false)
        val l = e.rows().single().link!!
        assertEquals(LinkMode.RING, l.mode)
        assertEquals(Triple("L", "R", "W"), Triple(l.overrideLabel, l.overrideRingtoneUri, l.overrideWallpaperPath))
        assertFalse(l.joinerEnabled)
        assertEquals(3, e.scheduler.rescheduleCount)
        e.repo.setOverrides("row1", null, null, null)
        assertNull(e.rows().single().link!!.overrideLabel)
    }

    @Test
    fun sendBothWritesPendingBeforeReturning() = runTest {
        val e = env(rows = listOf(linked(joinerEnabled = false)))
        e.remote.createdId = "c9"
        val occ = now.plusSeconds(60)
        e.repo.sendBoth("row1", CommandType.SKIP_NEXT, occ)
        val l = e.rows().single().link!!
        assertEquals(PendingBoth(CommandType.SKIP_NEXT, occ, "c9"), l.pendingBoth)
        assertFalse(l.joinerEnabled)
        assertEquals(1, e.scheduler.rescheduleCount)
        val c = e.remote.created.single()
        assertEquals(listOf("h1", "me", "them", CommandType.SKIP_NEXT, "o1", occ), listOf(c.hid, c.from, c.to, c.type, c.alarmId, c.occurrenceAt))
    }

    @Test
    fun sendBothEnableSetsJoinerEnabled() = runTest {
        val e = env(rows = listOf(linked(joinerEnabled = false)))
        e.repo.sendBoth("row1", CommandType.ENABLE, null)
        assertTrue(e.rows().single().link!!.joinerEnabled)
    }

    @Test
    fun sendBothOnNonLinkedOrUnpairedThrows() = runTest {
        val e = env(rows = listOf(linked()))
        try { e.repo.sendBoth("nope", CommandType.DISABLE, null); org.junit.Assert.fail() } catch (_: IllegalArgumentException) {}
        val e2 = env(rows = listOf(linked()), household = null)
        try { e2.repo.sendBoth("row1", CommandType.DISABLE, null); org.junit.Assert.fail() } catch (_: IllegalStateException) {}
    }

    @Test
    fun rejectionClearsPendingAndNotifies() = runTest {
        val e = env(rows = listOf(linked()))
        e.remote.createdId = "c1"
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        e.remote.updatesFor("c1").emit(CommandStatus.REJECTED to "already ringing")
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
        assertEquals(2, e.scheduler.rescheduleCount)
        val n = e.notifier.rejected.single()
        assertEquals(Triple(CommandType.DISABLE, "already ringing", "Shawn"), Triple(n.type, n.reason, n.partnerName))
    }

    @Test
    fun rejectionDoesNotClearNewerPending() = runTest {
        val e = env(rows = listOf(linked()))
        e.remote.createdId = "c1"
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        e.remote.createdId = "c2"
        e.repo.sendBoth("row1", CommandType.ENABLE, null)
        runCurrent()
        e.remote.updatesFor("c1").emit(CommandStatus.REJECTED to "no")
        runCurrent()
        assertEquals("c2", e.rows().single().link!!.pendingBoth?.commandId)
        assertTrue(e.notifier.rejected.isEmpty())
    }

    @Test
    fun appliedLeavesPending() = runTest {
        val e = env(rows = listOf(linked()))
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        e.remote.updatesFor("c1").emit(CommandStatus.APPLIED to null)
        runCurrent()
        assertEquals("c1", e.rows().single().link!!.pendingBoth?.commandId)
        assertTrue(e.notifier.rejected.isEmpty())
    }

    @Test
    fun expiryClearsAndNotifies() = runTest {
        val e = env(rows = listOf(linked()))
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        e.remote.updatesFor("c1").emit(CommandStatus.PENDING to null)
        advanceTimeBy(24.hours + 1.seconds)
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
        assertEquals("expired", e.notifier.rejected.single().reason)
    }

    @Test
    fun startReobservesPendingRows() = runTest {
        val e = env(rows = listOf(linked(pending = PendingBoth(CommandType.DISABLE, null, "c5"))))
        e.repo.start()
        e.repo.start()
        runCurrent()
        e.remote.updatesFor("c5").emit(CommandStatus.REJECTED to "nope")
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
        assertEquals(1, e.notifier.rejected.size)
    }

    @Test
    fun inviteTracksOpenInvitesUntilResolved() = runTest {
        val e = env()
        val seen = mutableListOf<Set<String>>()
        val job = backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { e.repo.openInvites.collect { seen += it } }
        e.repo.invite("a1")
        runCurrent()
        assertEquals(listOf(CommandType.INVITE_LINK to "a1"), e.sender.sent.map { it.first to it.second })
        assertEquals(setOf("a1"), seen.last())
        e.sender.status.emit(CommandStatus.PENDING to null)
        runCurrent()
        assertEquals(setOf("a1"), seen.last())
        e.sender.status.emit(CommandStatus.APPLIED to null)
        runCurrent()
        assertEquals(emptySet<String>(), seen.last())
        job.cancel()
    }

    @Test
    fun inviteExpiresAfter24Hours() = runTest {
        val e = env()
        e.repo.invite("a1")
        runCurrent()
        assertEquals(setOf("a1"), e.repo.openInvites.first())
        advanceTimeBy(24.hours + 1.seconds)
        runCurrent()
        assertEquals(emptySet<String>(), e.repo.openInvites.first())
    }

    @Test
    fun linksExcludeRowsPendingRemoval() = runTest {
        val e = env(rows = listOf(linked("r1", ownerAlarmId = "b"), linked("r2", ownerAlarmId = "a"), linked("r3", ownerAlarmId = "c")))
        e.removals.add("r3", RemovalReason.OWNER_DELETED)
        assertEquals(
            listOf(PublishedLink("a", LinkMode.NOTIFY), PublishedLink("b", LinkMode.NOTIFY)),
            e.repo.links.first(),
        )
    }

    @Test
    fun neverEmittingHouseholdFailsBounded() = runTest {
        val silent = MutableSharedFlow<Household?>()
        val e = Env(this, listOf(linked()), state(ownerAlarm()), null, householdFlow = silent)
        assertEquals(LinkResult.Failed("not paired"), e.repo.join("o2"))
        assertEquals(LinkResult.Failed("not paired"), e.repo.acceptInvite("i1", "o2"))
        assertTrue(e.remote.resolved.isEmpty())
        e.repo.declineInvite("i1")
        assertTrue(e.remote.resolved.isEmpty())
        try { e.repo.sendBoth("row1", CommandType.DISABLE, null); org.junit.Assert.fail() } catch (_: IllegalStateException) {}
        assertNull(e.rows().single().link!!.pendingBoth)
        assertTrue(e.remote.created.isEmpty())
        // join lock was not left held
        assertEquals(LinkResult.Failed("not paired"), e.repo.join("o2"))
    }

    @Test
    fun linksDeduplicatesElements() = runTest {
        val e = env(rows = listOf(linked("r1", ownerAlarmId = "a"), linked("r2", ownerAlarmId = "a")))
        assertEquals(listOf(PublishedLink("a", LinkMode.NOTIFY)), e.repo.links.first())
    }

    @Test
    fun watchResubscribesWhenObserveCompletesEmpty() = runTest {
        var calls = 0
        val e = Env(this, listOf(linked()), state(ownerAlarm()), hh, wrap = { f ->
            object : CommandRemote by f {
                override fun observe(householdId: String, commandId: String) =
                    if (calls++ == 0) kotlinx.coroutines.flow.emptyFlow() else f.observe(householdId, commandId)
            }
        })
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        advanceTimeBy(10.seconds)
        assertEquals("c1", e.rows().single().link!!.pendingBoth?.commandId)
        assertTrue(e.notifier.rejected.isEmpty())
        e.remote.updatesFor("c1").emit(CommandStatus.REJECTED to "no")
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
        assertEquals("no", e.notifier.rejected.single().reason)
    }

    @Test
    fun watchWaitsForHouseholdWithoutExpiringEarly() = runTest {
        val hhFlow = MutableStateFlow<Household?>(hh)
        val e = Env(this, listOf(linked()), state(ownerAlarm()), hh, householdFlow = hhFlow)
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        hhFlow.value = null
        runCurrent()
        advanceTimeBy(1.hours)
        assertEquals("c1", e.rows().single().link!!.pendingBoth?.commandId)
    }
}
