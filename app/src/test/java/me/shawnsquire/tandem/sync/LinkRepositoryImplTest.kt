package me.shawnsquire.tandem.sync

import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.runBlocking
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
        /** Collections that end PENDING right away, as the real flow does when its listener fails. */
        var failingCollects = 0
        var collects = 0
        var onCollect: () -> Unit = {}
        override fun send(
            type: CommandType, alarmId: String, occurrenceAt: Instant?, ringId: String?, snoozedUntil: Instant?,
            onCreated: (String) -> Unit,
        ): Flow<Pair<CommandStatus, String?>> {
            sent += Triple(type, alarmId, occurrenceAt)
            onCreated("inv${sent.size}")
            return kotlinx.coroutines.flow.flow {
                onCollect()
                if (collects++ < failingCollects) emit(CommandStatus.PENDING to null) else emitAll(status)
            }
        }
    }

    private inner class Env(
        val scope: TestScope, rows: List<Alarm>, partner: PartnerState?, household: Household?,
        private val wrap: (FakeCommandRemote) -> CommandRemote = { it },
        private val householdFlow: Flow<Household?>? = null,
    ) {
        val fake = FakeAlarmRepository(rows)
        val partner = MutableStateFlow(partner)
        val household = MutableStateFlow(household)
        val sender = RecordingSender()
        val remote = FakeCommandRemote()
        val scheduler = FakeAlarmScheduler()
        val notifier = FakeCommandNotifier()
        val removalCalls = mutableListOf<Pair<String, RemovalReason>>()
        val cancelCalls = mutableListOf<String>()
        val noteCalls = mutableListOf<Pair<String, String>>()
        var cancelResult = true
        var settled = true
        private fun store() = createSyncDataStore(
            File(tmp.root, "s-${storeCount++}.preferences_pb"), scope.backgroundScope,
        )
        val removals = LinkRemovals(store())
        val processed = ProcessedCommands(store(), Clock.fixed(now, ZoneOffset.UTC))
        private val inviteStore = store()
        fun makeRepo(clock: Clock = Clock.fixed(now, ZoneOffset.UTC), repoScope: kotlinx.coroutines.CoroutineScope = scope.backgroundScope) = LinkRepositoryImpl(
            fake, this.partner, householdFlow ?: this.household, sender, wrap(remote), processed, InviteLedger(inviteStore, clock), removals,
            { id, r -> removalCalls += id to r },
            { id -> cancelCalls += id; if (cancelResult) removals.remove(id); cancelResult },
            { row, cmd -> noteCalls += row to cmd },
            { settled },
            scheduler, notifier,
            clock, repoScope,
        )
        val repo by lazy { makeRepo() }
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
        assertEquals(PendingBoth(CommandType.SKIP_NEXT, occ, "c9", now), l.pendingBoth)
        assertFalse(l.joinerEnabled)
        assertEquals(1, e.scheduler.rescheduleCount)
        val c = e.remote.created.single()
        assertEquals(listOf("h1", "me", "them", CommandType.SKIP_NEXT, "o1", occ), listOf(c.hid, c.from, c.to, c.type, c.alarmId, c.occurrenceAt))
    }

    @Test
    fun sendBothStoresPendingWithItsIdAndTimeBeforeCreating() = runTest {
        val e = env(rows = listOf(linked(joinerEnabled = false)))
        e.remote.createdId = "c9"
        var atCreate: Alarm? = null
        e.remote.onCreate = { atCreate = runBlocking { e.rows().single() } }
        e.repo.sendBoth("row1", CommandType.ENABLE, null)
        val l = atCreate!!.link!!
        assertEquals(PendingBoth(CommandType.ENABLE, null, "c9", now), l.pendingBoth)
        assertTrue(l.joinerEnabled)
    }

    @Test
    fun sendBothThatFailsToCreateClearsPendingAndRestoresJoinerEnabled() = runTest {
        val e = env(rows = listOf(linked(joinerEnabled = false)))
        e.remote.createFailure = IllegalStateException("boom")
        try { e.repo.sendBoth("row1", CommandType.ENABLE, null); org.junit.Assert.fail() } catch (_: IllegalStateException) {}
        val l = e.rows().single().link!!
        assertNull(l.pendingBoth)
        assertFalse(l.joinerEnabled)
        // Only the cleanup's: the create runs before the send's own reschedule.
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    /** Suppressed failures along the cause chain: coroutine stack-trace recovery (on under tests) wraps a copy around the original. */
    private fun Throwable.allSuppressed() = generateSequence(this) { it.cause }.flatMap { it.suppressed.asSequence() }.toList()

    @Test
    fun sendBothCreateFailureSurvivesAFailingCleanup() = runTest {
        val e = env(rows = listOf(linked()))
        e.remote.createFailure = IllegalStateException("boom")
        var updates = 0
        e.fake.afterUpdateLink = { if (updates++ == 1) throw IllegalArgumentException("cleanup write failed") }
        e.scheduler.rescheduleBlock = { throw IllegalStateException("alarms read failed") }
        val thrown = try { e.repo.sendBoth("row1", CommandType.DISABLE, null); null } catch (t: Throwable) { t }
        // The caller sees the create's failure, with both cleanup failures attached.
        assertEquals("boom", thrown!!.message)
        assertEquals(listOf("cleanup write failed", "alarms read failed"), thrown.allSuppressed().map { it.message })
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test
    fun sendBothCreateFailureClearsPendingWhenTheRescheduleFails() = runTest {
        val e = env(rows = listOf(linked()))
        e.remote.createFailure = IllegalStateException("boom")
        e.scheduler.rescheduleBlock = { throw IllegalStateException("alarms read failed") }
        val thrown = try { e.repo.sendBoth("row1", CommandType.DISABLE, null); null } catch (t: Throwable) { t }
        assertEquals("boom", thrown!!.message)
        assertEquals("alarms read failed", thrown.allSuppressed().single().message)
        assertNull(e.rows().single().link!!.pendingBoth)
    }

    @Test
    fun sendBothThatFailsToCreateLeavesANewerPendingAlone() = runTest {
        val e = env(rows = listOf(linked(joinerEnabled = false)))
        val newer = PendingBoth(CommandType.DISABLE, null, "newer", now)
        e.remote.onCreate = { runBlocking { e.fake.updateLink("row1") { it.copy(pendingBoth = newer) } } }
        e.remote.createFailure = IllegalStateException("boom")
        try { e.repo.sendBoth("row1", CommandType.ENABLE, null); org.junit.Assert.fail() } catch (_: IllegalStateException) {}
        assertEquals(newer, e.rows().single().link!!.pendingBoth)
    }

    @Test
    fun sendBothWhoseRescheduleThrowsStillCreatesAndWatches() = runTest {
        val e = env(rows = listOf(linked()))
        e.scheduler.rescheduleBlock = { throw IllegalStateException("alarms read failed") }
        // The command went out, so the caller isn't told the send failed.
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        e.scheduler.rescheduleBlock = null
        assertEquals(CommandType.DISABLE, e.remote.created.single().type)
        assertEquals("c1", e.rows().single().link!!.pendingBoth?.commandId)
        runCurrent()
        e.remote.updatesFor("c1").emit(CommandStatus.REJECTED to "no")
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
        assertEquals("no", e.notifier.rejected.single().reason)
    }

    @Test
    fun sendBothWhoseCallerIsCancelledMidRescheduleStillCreatesAndWatches() = runTest {
        val e = env(rows = listOf(linked()))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        e.scheduler.rescheduleBlock = { gate.await() }
        val caller = launch { e.repo.sendBoth("row1", CommandType.DISABLE, null) }
        runCurrent()
        caller.cancel()
        e.scheduler.rescheduleBlock = null
        gate.complete(Unit)
        runCurrent()
        assertTrue(caller.isCancelled)
        assertEquals(CommandType.DISABLE, e.remote.created.single().type)
        assertEquals("c1", e.rows().single().link!!.pendingBoth?.commandId)
        e.remote.updatesFor("c1").emit(CommandStatus.REJECTED to "no")
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
        assertEquals("no", e.notifier.rejected.single().reason)
    }

    @Test
    fun sendBothWhoseCallerIsCancelledAsThePendingWriteResumesStillCreatesAndWatches() = runTest {
        val e = env(rows = listOf(linked()))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        // The pendingBoth write has landed, but updateLink hasn't returned when the caller is cancelled.
        e.fake.afterUpdateLink = { e.fake.afterUpdateLink = null; gate.await() }
        val caller = launch { e.repo.sendBoth("row1", CommandType.DISABLE, null) }
        runCurrent()
        assertEquals("c1", e.rows().single().link!!.pendingBoth?.commandId)
        caller.cancel()
        gate.complete(Unit)
        runCurrent()
        assertTrue(caller.isCancelled)
        assertEquals(CommandType.DISABLE, e.remote.created.single().type)
        e.remote.updatesFor("c1").emit(CommandStatus.REJECTED to "no")
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
        assertEquals("no", e.notifier.rejected.single().reason)
    }

    @Test
    fun pendingFromBeforeARestartExpiresOnItsOriginalWindow() = runTest {
        val created = now.minusSeconds(23 * 3600)
        val e = env(rows = listOf(linked(pending = PendingBoth(CommandType.DISABLE, null, "c5", created))))
        e.repo.start()
        runCurrent()
        e.remote.updatesFor("c5").emit(CommandStatus.PENDING to null)
        advanceTimeBy(59.minutes)
        runCurrent()
        assertEquals("c5", e.rows().single().link!!.pendingBoth?.commandId)
        advanceTimeBy(1.minutes + 1.seconds)
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
        assertEquals("expired", e.notifier.rejected.single().reason)
    }

    @Test
    fun pendingAlreadyPastItsWindowExpiresAtStart() = runTest {
        val e = env(rows = listOf(linked(pending = PendingBoth(CommandType.DISABLE, null, "c5", now.minusSeconds(25 * 3600)))))
        e.repo.start()
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
        assertEquals("expired", e.notifier.rejected.single().reason)
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
    fun appliedHandsOverToLinkSyncAndOnlyClearsAfterTheFallback() = runTest {
        val e = env(rows = listOf(linked()))
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        val before = e.scheduler.rescheduleCount
        e.remote.updatesFor("c1").emit(CommandStatus.APPLIED to null)
        advanceTimeBy(29.minutes)
        runCurrent()
        assertEquals(listOf("row1" to "c1"), e.noteCalls)
        assertEquals("c1", e.rows().single().link!!.pendingBoth?.commandId)
        advanceTimeBy(2.minutes)
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
        assertEquals(before + 1, e.scheduler.rescheduleCount)
        assertTrue(e.notifier.rejected.isEmpty())
    }

    @Test
    fun appliedFallbackKeepsPendingWhileLinkSyncSawNoNewerDocUntilTheTtl() = runTest {
        val e = env(rows = listOf(linked()))
        e.settled = false
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        e.remote.updatesFor("c1").emit(CommandStatus.APPLIED to null)
        advanceTimeBy(5.hours)
        runCurrent()
        assertEquals("c1", e.rows().single().link!!.pendingBoth?.commandId)
        e.settled = true
        advanceTimeBy(31.minutes)
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
    }

    @Test
    fun appliedFallbackClearsAtTheTtlEvenIfNeverSettled() = runTest {
        val e = env(rows = listOf(linked()))
        e.settled = false
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        e.remote.updatesFor("c1").emit(CommandStatus.APPLIED to null)
        advanceTimeBy(23.hours)
        runCurrent()
        assertEquals("c1", e.rows().single().link!!.pendingBoth?.commandId)
        advanceTimeBy(2.hours)
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
    }

    @Test
    fun appliedDoesNotClearNewerCommand() = runTest {
        val e = env(rows = listOf(linked()))
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        e.remote.createdId = "c2"
        e.repo.sendBoth("row1", CommandType.ENABLE, null)
        runCurrent()
        e.remote.updatesFor("c1").emit(CommandStatus.APPLIED to null)
        runCurrent()
        assertEquals("c2", e.rows().single().link!!.pendingBoth?.commandId)
    }

    @Test
    fun startWithAlreadyAppliedCommandNotesItAgain() = runTest {
        val e = env(rows = listOf(linked(pending = PendingBoth(CommandType.DISABLE, null, "c5"))))
        e.remote.updatesFor("c5").emit(CommandStatus.APPLIED to null)
        e.repo.start()
        runCurrent()
        assertEquals(listOf("row1" to "c5"), e.noteCalls)
        assertEquals("c5", e.rows().single().link!!.pendingBoth?.commandId)
    }

    @Test
    fun rejoinWhenCancelRemovalFindsRowGoneCreatesNewRow() = runTest {
        val e = env(rows = listOf(linked()))
        e.removals.add("row1", RemovalReason.LEFT)
        e.cancelResult = false
        val r = e.repo.join("o1") as LinkResult.Joined
        assertEquals(listOf("row1"), e.cancelCalls)
        assertTrue(r.alarmId != "row1")
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
    fun openInviteSurvivesARestartUntilAnswered() = runTest {
        val e = env()
        e.repo.invite("a1")
        runCurrent()
        val restarted = e.makeRepo()
        restarted.start()
        runCurrent()
        assertEquals(setOf("a1"), restarted.openInvites.first())
        e.remote.updatesFor("inv1").emit(CommandStatus.PENDING to null)
        runCurrent()
        assertEquals(setOf("a1"), restarted.openInvites.first())
        e.remote.updatesFor("inv1").emit(CommandStatus.REJECTED to "declined")
        runCurrent()
        assertEquals(emptySet<String>(), restarted.openInvites.first())
        // And a third start finds nothing left to restore.
        val again = e.makeRepo()
        again.start()
        runCurrent()
        assertEquals(emptySet<String>(), again.openInvites.first())
    }

    @Test
    fun aListenerFailureDuringAnOpenInviteKeepsItOpenAndListensAgain() = runTest {
        val e = env()
        e.sender.failingCollects = 1
        e.repo.invite("a1")
        runCurrent()
        assertEquals(1, e.sender.collects)
        assertEquals(setOf("a1"), e.repo.openInvites.first())
        // Still persisted: a restart now would restore it.
        val restarted = e.makeRepo()
        restarted.start()
        runCurrent()
        assertEquals(setOf("a1"), restarted.openInvites.first())
        advanceTimeBy(5.seconds + 1.seconds)
        runCurrent()
        assertEquals(2, e.sender.collects)
        e.sender.status.emit(CommandStatus.APPLIED to null)
        runCurrent()
        assertEquals(emptySet<String>(), e.repo.openInvites.first())
        val again = e.makeRepo()
        again.start()
        runCurrent()
        assertEquals(emptySet<String>(), again.openInvites.first())
    }

    @Test
    fun aListenerFailureDuringARestoredInviteKeepsItOpenAndListensAgain() = runTest {
        var observes = 0
        val e = Env(this, emptyList(), state(ownerAlarm()), hh, wrap = { f ->
            object : CommandRemote by f {
                override fun observe(householdId: String, commandId: String) =
                    if (observes++ == 0) kotlinx.coroutines.flow.flowOf(CommandStatus.PENDING to null)
                    else f.observe(householdId, commandId)
            }
        })
        e.repo.invite("a1")
        runCurrent()
        val restarted = e.makeRepo()
        restarted.start()
        runCurrent()
        assertEquals(1, observes)
        assertEquals(setOf("a1"), restarted.openInvites.first())
        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(2, observes)
        assertEquals(setOf("a1"), restarted.openInvites.first())
        e.remote.updatesFor("inv1").emit(CommandStatus.REJECTED to "declined")
        runCurrent()
        assertEquals(emptySet<String>(), restarted.openInvites.first())
    }

    @Test
    fun repeatedListenerFailuresOnAnOpenInviteBackOffAndStopOnceTheHouseholdChanges() = runTest {
        val e = env()
        val times = mutableListOf<Long>()
        e.sender.failingCollects = Int.MAX_VALUE
        e.sender.onCollect = { times += testScheduler.currentTime }
        e.repo.invite("a1")
        advanceTimeBy(140.seconds)
        assertEquals(listOf(0L, 5_000L, 15_000L, 35_000L, 75_000L, 135_000L), times)
        assertEquals(setOf("a1"), e.repo.openInvites.first())
        e.household.value = Household("h2", "me", "other")
        advanceTimeBy(1.hours)
        assertEquals(6, times.size)
        // An invite into the old household is moot: it is dropped, and not restored on the next start.
        assertEquals(emptySet<String>(), e.repo.openInvites.first())
        val restarted = e.makeRepo()
        restarted.start()
        runCurrent()
        assertEquals(emptySet<String>(), restarted.openInvites.first())
    }

    @Test
    fun anAnsweredInviteIsNotRestored() = runTest {
        val e = env()
        e.repo.invite("a1")
        runCurrent()
        e.sender.status.emit(CommandStatus.APPLIED to null)
        runCurrent()
        val restarted = e.makeRepo()
        restarted.start()
        runCurrent()
        assertEquals(emptySet<String>(), restarted.openInvites.first())
    }

    @Test
    fun aRestoredInviteExpiresWithItsCommand() = runTest {
        val e = env()
        e.repo.invite("a1")
        runCurrent()
        val restarted = e.makeRepo(clock = Clock.fixed(now.plusSeconds(23 * 3600), ZoneOffset.UTC))
        restarted.start()
        runCurrent()
        assertEquals(setOf("a1"), restarted.openInvites.first())
        advanceTimeBy(1.hours + 1.seconds)
        runCurrent()
        assertEquals(emptySet<String>(), restarted.openInvites.first())
        // An expired entry is not restored at all.
        val late = e.makeRepo(clock = Clock.fixed(now.plusSeconds(25 * 3600), ZoneOffset.UTC))
        late.start()
        runCurrent()
        assertEquals(emptySet<String>(), late.openInvites.first())
    }

    @Test
    fun linksExcludeRowsPendingRemoval() = runTest {
        val e = env(rows = listOf(linked("r1", ownerAlarmId = "b"), linked("r2", ownerAlarmId = "a"), linked("r3", ownerAlarmId = "c")))
        e.removals.add("r3", RemovalReason.OWNER_DELETED)
        assertEquals(
            listOf(PublishedLink("a", LinkMode.NOTIFY, 6, 30, weekdays), PublishedLink("b", LinkMode.NOTIFY, 6, 30, weekdays)),
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
        assertEquals(listOf(PublishedLink("a", LinkMode.NOTIFY, 6, 30, weekdays)), e.repo.links.first())
    }

    @Test
    fun linksReEmitWhenACopiedScheduleChanges() = runTest {
        val e = env(rows = listOf(linked("r1", ownerAlarmId = "a")))
        val seen = mutableListOf<List<PublishedLink>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { e.repo.links.toList(seen) }
        runCurrent()
        for (change in listOf<(Alarm) -> Alarm>(
            { it.copy(hour = 5) }, { it.copy(minute = 15) }, { it.copy(days = setOf(DayOfWeek.SUNDAY)) },
        )) {
            e.fake.upsertLinked(change(e.rows().single()))
            runCurrent()
        }
        assertEquals(
            listOf(
                PublishedLink("a", LinkMode.NOTIFY, 6, 30, weekdays),
                PublishedLink("a", LinkMode.NOTIFY, 5, 30, weekdays),
                PublishedLink("a", LinkMode.NOTIFY, 5, 15, weekdays),
                PublishedLink("a", LinkMode.NOTIFY, 5, 15, setOf(DayOfWeek.SUNDAY)),
            ),
            seen.map { it.single() },
        )
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
    fun aListenerFailureDuringABothCommandKeepsItPendingAndAppliedStillHandsOver() = runTest {
        var calls = 0
        val e = Env(this, listOf(linked()), state(ownerAlarm()), hh, wrap = { f ->
            object : CommandRemote by f {
                // The real observe ends without an answer when its listener fails.
                override fun observe(householdId: String, commandId: String) =
                    if (calls++ == 0) kotlinx.coroutines.flow.flowOf(CommandStatus.PENDING to null) else f.observe(householdId, commandId)
            }
        })
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(2, calls)
        assertEquals("c1", e.rows().single().link!!.pendingBoth?.commandId)
        assertTrue(e.notifier.rejected.isEmpty())
        e.remote.updatesFor("c1").emit(CommandStatus.APPLIED to null)
        runCurrent()
        assertEquals(listOf("row1" to "c1"), e.noteCalls)
        advanceTimeBy(31.minutes)
        runCurrent()
        assertNull(e.rows().single().link!!.pendingBoth)
        assertTrue(e.notifier.rejected.isEmpty())
    }

    /** An Env whose observe always ends at PENDING (every listen fails), recording when each one started. */
    private fun TestScope.failingListens(times: MutableList<Long>) = Env(this, listOf(linked()), state(ownerAlarm()), hh, wrap = { f ->
        object : CommandRemote by f {
            override fun observe(householdId: String, commandId: String) =
                kotlinx.coroutines.flow.flowOf(CommandStatus.PENDING to null).also { times += testScheduler.currentTime }
        }
    })

    @Test
    fun repeatedListenerFailuresOnABothCommandBackOff() = runTest {
        val times = mutableListOf<Long>()
        val e = failingListens(times)
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        advanceTimeBy(200.seconds)
        assertEquals(listOf(0L, 5_000L, 15_000L, 35_000L, 75_000L, 135_000L, 195_000L), times)
        assertTrue(e.notifier.rejected.isEmpty())
    }

    @Test
    fun aBothWatchStopsListeningOnceTheHouseholdChanges() = runTest {
        val times = mutableListOf<Long>()
        val e = failingListens(times)
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        // Unknown (a household listener gap) only pauses it.
        e.household.value = null
        advanceTimeBy(1.hours)
        assertEquals(1, times.size)
        e.household.value = Household("h2", "me", "other")
        advanceTimeBy(1.hours)
        assertEquals(1, times.size)
        // Not answered: no rejection posted, and the row is LinkSync's to remove.
        assertTrue(e.notifier.rejected.isEmpty())
        assertEquals("c1", e.rows().single().link!!.pendingBoth?.commandId)
    }

    @Test
    fun aBothWatchClearsItsCommandWhenRePairedWithTheSamePartner() = runTest {
        val times = mutableListOf<Long>()
        val e = failingListens(times)
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        val before = e.scheduler.rescheduleCount
        // Same partner, new household: the row survives, but the command is out of reach.
        e.household.value = Household("h2", "me", "them")
        advanceTimeBy(1.hours)
        assertEquals(1, times.size)
        assertNull(e.rows().single().link!!.pendingBoth)
        assertEquals(before + 1, e.scheduler.rescheduleCount)
        assertEquals("the pairing changed", e.notifier.rejected.single().reason)
    }

    @Test
    fun aBothWatchStopsListeningOnceTheRowNoLongerHoldsItsCommand() = runTest {
        val times = mutableListOf<Long>()
        val e = failingListens(times)
        e.repo.sendBoth("row1", CommandType.DISABLE, null)
        runCurrent()
        e.fake.updateLink("row1") { it.copy(pendingBoth = null) }
        advanceTimeBy(1.hours)
        assertEquals(1, times.size)
        assertTrue(e.notifier.rejected.isEmpty())
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

    @Test
    fun householdWaitDoesNotHoldJoinLock() = runTest {
        val silent = MutableSharedFlow<Household?>()
        val e = Env(this, emptyList(), state(ownerAlarm()), null, householdFlow = silent)
        val a = async { e.repo.join("o1") }
        runCurrent()
        advanceTimeBy(1.seconds)
        val b = async { e.repo.join("o1") }
        runCurrent()
        // b's own 2 s wait ends at t=3 s; if it queued behind a's wait for the lock it would end at t=4 s.
        advanceTimeBy(2.1.seconds)
        runCurrent()
        assertTrue(b.isCompleted)
        assertEquals(LinkResult.Failed("not paired"), b.await())
        assertEquals(LinkResult.Failed("not paired"), a.await())
    }
}
