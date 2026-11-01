package me.shawnsquire.tandem.ui.alarms

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeLinkRepository
import me.shawnsquire.tandem.fakes.FakePairingRepository
import me.shawnsquire.tandem.fakes.FakePartnerRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth
import me.shawnsquire.tandem.sync.PartnerAlarm
import me.shawnsquire.tandem.sync.PublishedLink
import me.shawnsquire.tandem.sync.PartnerState
import me.shawnsquire.tandem.ui.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

class AlarmsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    // Monday 2026-11-02 05:00 UTC.
    private val now = Instant.parse("2026-11-02T05:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val weekdays = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    private fun alarm(
        id: String, hour: Int = 6, minute: Int = 30, label: String = id,
        days: Set<DayOfWeek> = weekdays, enabled: Boolean = true, skip: Instant? = null,
    ) = Alarm(id, label, hour, minute, days, enabled, skip, "file:///ring-$id", "/wall-$id")

    private class Env(initial: List<Alarm>, clock: Clock) {
        val repo = FakeAlarmRepository(initial)
        val scheduler = FakeAlarmScheduler()
        val pairing = FakePairingRepository()
        val partner = FakePartnerRepository(null)
        val purged = mutableListOf<Alarm>()
        val ticker = MutableStateFlow(Unit)
        val links = FakeLinkRepository()
        val vm = AlarmsViewModel(repo, AlarmEdits(repo, scheduler), links, pairing, partner, clock, ticker) { purged += it }
    }

    private fun env(vararg alarms: Alarm) = Env(alarms.toList(), clock)

    @Test fun skipNextPinsExactNextOccurrence() = runTest {
        val e = env(alarm("w"))
        e.vm.skipNext("w")
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), e.repo.get("w")!!.skippedOccurrenceAt)
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test fun skipNextShowsSkippedRowWithFollowingOccurrence() = runTest {
        val e = env(alarm("w"))
        e.vm.state.test {
            awaitItem() // initial / first computed
            e.vm.skipNext("w")
            val row = expectMostRecentItem().rows.single()
            assertTrue(row.skipped)
            assertEquals(Instant.parse("2026-11-03T06:30:00Z"), row.nextRing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun unskipClearsAndReschedules() = runTest {
        val e = env(alarm("w", skip = Instant.parse("2026-11-02T06:30:00Z")))
        e.vm.unskip("w")
        assertNull(e.repo.get("w")!!.skippedOccurrenceAt)
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test fun skipOnOneShotTurnsItOff() = runTest {
        val e = env(alarm("o", days = emptySet()))
        e.vm.skipNext("o")
        val a = e.repo.get("o")!!
        assertFalse(a.enabled)
        assertNull(a.skippedOccurrenceAt)
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test fun skipOnDisabledDoesNothing() = runTest {
        val e = env(alarm("w", enabled = false))
        e.vm.skipNext("w")
        assertNull(e.repo.get("w")!!.skippedOccurrenceAt)
        assertEquals(0, e.scheduler.rescheduleCount)
    }

    @Test fun toggleEnabledReschedules() = runTest {
        val e = env(alarm("w"))
        e.vm.setEnabled("w", false)
        assertFalse(e.repo.get("w")!!.enabled)
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test fun rowsSortedByTimeThenLabel() = runTest {
        val e = env(
            alarm("c", hour = 9, minute = 0, label = "Zed"),
            alarm("b", hour = 6, minute = 45, label = "B"),
            alarm("a2", hour = 6, minute = 30, label = "beta"),
            alarm("a1", hour = 6, minute = 30, label = "Alpha"),
        )
        e.vm.state.test {
            val rows = expectMostRecentItem().rows
            assertEquals(listOf("a1", "a2", "b", "c"), rows.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun rowContentAndDisabledHasNoNextRing() = runTest {
        val e = env(alarm("w"), alarm("off", enabled = false, hour = 7))
        e.vm.state.test {
            val rows = expectMostRecentItem().rows
            val w = rows.first { it.id == "w" }
            assertEquals("Mon–Fri", w.daysText)
            assertEquals("Rings in 1 h 30 min", w.ringsInText)
            val off = rows.first { it.id == "off" }
            assertNull(off.nextRing)
            assertNull(off.ringsInText)
            assertFalse(off.skipped)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun staleSkipIsNotShown() = runTest {
        val e = env(alarm("w", skip = Instant.parse("2026-10-30T06:30:00Z")))
        e.vm.state.test {
            val row = expectMostRecentItem().rows.single()
            assertFalse(row.skipped)
            assertEquals(Instant.parse("2026-11-02T06:30:00Z"), row.nextRing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun deleteThenUndoRestoresAndDoesNotPurge() = runTest {
        val w = alarm("w")
        val e = env(w)
        e.vm.pendingDeleteId.test {
            assertNull(awaitItem())
            e.vm.delete("w")
            assertEquals("w", awaitItem())
            assertNull(e.repo.get("w"))
            e.vm.undoDelete("w")
            assertNull(awaitItem())
            assertEquals(w, e.repo.get("w"))
            e.vm.onDeleteSnackbarDismissed("w") // dismissal after undo must not purge
            assertTrue(e.purged.isEmpty())
            assertEquals(2, e.scheduler.rescheduleCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun deleteThenDismissPurgesOnce() = runTest {
        val w = alarm("w")
        val e = env(w)
        e.vm.delete("w")
        assertEquals(1, e.scheduler.rescheduleCount)
        e.vm.onDeleteSnackbarDismissed("w")
        e.vm.onDeleteSnackbarDismissed("w")
        assertEquals(listOf(w), e.purged)
        assertNull(e.repo.get("w"))
    }

    @Test fun secondDeletePurgesFirstAndStaleUndoIsNoOp() = runTest {
        val e = env(alarm("a"), alarm("b"))
        e.vm.delete("a")
        e.vm.delete("b")
        assertEquals(listOf("a"), e.purged.map { it.id })
        e.vm.undoDelete("a") // stale Undo from the first snackbar
        assertNull(e.repo.get("a"))
        assertNull(e.repo.get("b"))
        e.vm.onDeleteSnackbarDismissed("a")
        assertEquals(1, e.purged.size)
        e.vm.onDeleteSnackbarDismissed("b")
        assertEquals(listOf("a", "b"), e.purged.map { it.id })
    }

    @Test fun pendingDeleteSurvivesUntilReportedAndClearsOnDismiss() = runTest {
        val e = env(alarm("w"))
        assertNull(e.vm.pendingDeleteId.value)
        e.vm.delete("w")
        // A screen that left composition never reports; the state must still be there for the next one.
        assertEquals("w", e.vm.pendingDeleteId.value)
        assertTrue(e.purged.isEmpty())
        e.vm.onDeleteSnackbarDismissed("w")
        assertNull(e.vm.pendingDeleteId.value)
        assertEquals(1, e.purged.size)
    }

    @Test fun secondDeleteMovesPendingIdAndStaleUndoKeepsIt() = runTest {
        val e = env(alarm("a"), alarm("b"))
        e.vm.delete("a")
        e.vm.delete("b")
        assertEquals("b", e.vm.pendingDeleteId.value)
        e.vm.undoDelete("a")
        e.vm.onDeleteSnackbarDismissed("a")
        assertEquals("b", e.vm.pendingDeleteId.value)
    }

    // Joined rows.

    private fun linkedAlarm(
        id: String, hour: Int = 6, minute: Int = 30, label: String = id, enabled: Boolean = true,
        skip: Instant? = null, mode: LinkMode = LinkMode.RING, ownerSkip: Instant? = null,
        joinerEnabled: Boolean = true, pending: PendingBoth? = null, overrideLabel: String? = null,
        ownerAlarmId: String = "o-$id",
    ) = Alarm(
        id, label, hour, minute, weekdays, enabled, skip, null, null,
        link = AlarmLink("uid", ownerAlarmId, mode, ownerSkip, joinerEnabled, pending, overrideLabel, null, null),
    )

    private fun partnerWith(vararg alarms: PartnerAlarm) = PartnerState("Sam", alarms.toList(), null, null)

    private fun partnerAlarm(id: String, solo: Instant? = null) = PartnerAlarm(
        id, "x", 6, 30, weekdays, true, null, null, soloSkippedOccurrenceAt = solo,
    )

    private val future = Instant.parse("2026-11-02T06:30:00Z")
    private val past = Instant.parse("2026-11-02T04:00:00Z")

    @Test fun joinedRowCarriesModeAndPartnerName() {
        val rows = buildAlarmsState(
            listOf(linkedAlarm("j", mode = LinkMode.NOTIFY), alarm("own", hour = 7)), now, ZoneOffset.UTC, "Sam",
        ).rows
        val j = rows.first { it.id == "j" }.linked!!
        assertEquals("Sam", j.partnerName)
        assertEquals(LinkMode.NOTIFY, j.mode)
        assertFalse(j.ownerSkipped || j.skippedForYou || j.ownerSkippingSolo || j.pendingBoth)
        assertNull(rows.first { it.id == "own" }.linked)
    }

    @Test fun joinedAndOwnRowsSortTogetherByTime() {
        val rows = buildAlarmsState(
            listOf(alarm("late", hour = 9), linkedAlarm("j", hour = 7), alarm("early", hour = 6)), now, ZoneOffset.UTC,
        ).rows
        assertEquals(listOf("early", "j", "late"), rows.map { it.id })
    }

    @Test fun overrideLabelWinsOverCopiedLabel() {
        val rows = buildAlarmsState(
            listOf(linkedAlarm("a", label = "Owner", overrideLabel = "Mine"), linkedAlarm("b", label = "Owner")),
            now, ZoneOffset.UTC,
        ).rows
        assertEquals("Mine", rows.first { it.id == "a" }.label)
        assertEquals("Owner", rows.first { it.id == "b" }.label)
    }

    @Test fun ownerSkipShowsOnlyWhileInTheFuture() {
        val on = buildAlarmsState(listOf(linkedAlarm("j", ownerSkip = future)), now, ZoneOffset.UTC).rows.single()
        assertTrue(on.linked!!.ownerSkipped)
        assertTrue(on.skipped)
        val stale = buildAlarmsState(listOf(linkedAlarm("j", ownerSkip = past)), now, ZoneOffset.UTC).rows.single()
        assertFalse(stale.linked!!.ownerSkipped)
        assertFalse(stale.skipped)
    }

    @Test fun joinerOwnSkipIsSkippedForYou() {
        val row = buildAlarmsState(listOf(linkedAlarm("j", skip = future)), now, ZoneOffset.UTC).rows.single()
        assertTrue(row.linked!!.skippedForYou)
        assertFalse(row.linked!!.ownerSkipped)
        assertTrue(row.skipped)
    }

    @Test fun ownerSoloSkipComesFromMatchingPartnerAlarm() {
        val partnerAlarms = mapOf("o-j" to partnerAlarm("o-j", solo = future), "o-k" to partnerAlarm("o-k", solo = past))
        val rows = buildAlarmsState(
            listOf(linkedAlarm("j"), linkedAlarm("k"), linkedAlarm("m", ownerAlarmId = "other")),
            now, ZoneOffset.UTC, partnerAlarms = partnerAlarms,
        ).rows
        assertTrue(rows.first { it.id == "j" }.linked!!.ownerSkippingSolo)
        assertFalse(rows.first { it.id == "k" }.linked!!.ownerSkippingSolo)
        assertFalse(rows.first { it.id == "m" }.linked!!.ownerSkippingSolo)
        // The joiner still rings, so the row is neither skipped nor off.
        assertFalse(rows.first { it.id == "j" }.skipped)
        assertTrue(rows.first { it.id == "j" }.enabled)
    }

    @Test fun enabledComesFromIsArmedNotTheCopiedField() {
        val rows = buildAlarmsState(
            listOf(
                linkedAlarm("mine-off", joinerEnabled = false),
                linkedAlarm("owner-off", enabled = false),
                linkedAlarm("owner-off-pending-on", enabled = false, pending = PendingBoth(CommandType.ENABLE, null, "c")),
                linkedAlarm("pending-off", pending = PendingBoth(CommandType.DISABLE, null, "c")),
                linkedAlarm("on"),
            ),
            now, ZoneOffset.UTC,
        ).rows.associateBy { it.id }
        assertFalse(rows.getValue("mine-off").enabled)
        assertNull(rows.getValue("mine-off").nextRing)
        assertFalse(rows.getValue("owner-off").enabled)
        assertTrue(rows.getValue("owner-off-pending-on").enabled)
        assertFalse(rows.getValue("pending-off").enabled)
        assertTrue(rows.getValue("on").enabled)
    }

    @Test fun skipStatesAreHiddenWhileNotArmed() {
        val row = buildAlarmsState(
            listOf(linkedAlarm("j", joinerEnabled = false, skip = future, ownerSkip = future)), now, ZoneOffset.UTC,
        ).rows.single()
        assertFalse(row.skipped)
        assertFalse(row.linked!!.ownerSkipped || row.linked!!.skippedForYou)
    }

    @Test fun pendingBothIsExposed() {
        val row = buildAlarmsState(
            listOf(linkedAlarm("j", pending = PendingBoth(CommandType.SKIP_NEXT, future, "c"))), now, ZoneOffset.UTC,
        ).rows.single()
        assertTrue(row.linked!!.pendingBoth)
    }

    @Test fun viewModelResolvesPartnerNameAndSolo() = runTest {
        val e = env(linkedAlarm("j"))
        e.partner.set(partnerWith(partnerAlarm("o-j", solo = future)))
        e.vm.state.test {
            // Unpaired: the published display name is used.
            var linked = expectMostRecentItem().rows.single().linked!!
            assertEquals("Sam", linked.partnerName)
            assertTrue(linked.ownerSkippingSolo)
            e.pairing.join("ABC123") // paired with partnerName "Alex"
            linked = expectMostRecentItem().rows.single().linked!!
            assertEquals("Alex", linked.partnerName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun linkedRowsIgnoreSetEnabledSkipAndUnskip() = runTest {
        val e = env(linkedAlarm("j", skip = future))
        e.vm.setEnabled("j", false)
        e.vm.skipNext("j")
        e.vm.unskip("j")
        assertEquals(linkedAlarm("j", skip = future), e.repo.get("j"))
        assertEquals(0, e.scheduler.rescheduleCount)
    }

    // The joined row's switch: "just me or both of us?".

    private val ownerNext = Instant.parse("2026-11-03T06:30:00Z")

    private fun ownerAlarm(next: Instant? = ownerNext) = PartnerAlarm("o-j", "x", 6, 30, weekdays, true, next, null)

    @Test fun linkedSwitchJustMeTurnsMyCopyOffAndOn() = runTest {
        val e = env(linkedAlarm("j"))
        e.vm.setLinkedJustMe("j")
        e.repo.upsert(linkedAlarm("j", joinerEnabled = false))
        e.vm.setLinkedJustMe("j")
        assertEquals(
            listOf<FakeLinkRepository.Call>(
                FakeLinkRepository.Call.SetJoinerEnabled("j", false),
                FakeLinkRepository.Call.SetJoinerEnabled("j", true),
            ),
            e.links.calls,
        )
        assertEquals(0, e.scheduler.rescheduleCount)
    }

    @Test fun linkedSwitchJustMeCannotTurnOnWhileTheOwnerIsOff() = runTest {
        val e = env(linkedAlarm("j", enabled = false, joinerEnabled = false))
        e.vm.setLinkedJustMe("j")
        assertTrue(e.links.calls.isEmpty())
        e.vm.state.test {
            assertEquals(false, expectMostRecentItem().rows.single().linked!!.switchOptions.justMe)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun linkedSwitchBothOffPinsToTheRowsOwnOccurrenceEvenAfterAnOwnerSoloSkip() = runTest {
        val e = env(linkedAlarm("j"))
        // The owner's published next skips their solo-skipped 06:30; the row's own does not.
        e.partner.set(partnerWith(PartnerAlarm("o-j", "x", 6, 30, weekdays, true, ownerNext, null, soloSkippedOccurrenceAt = future)))
        e.vm.setLinkedBoth("j")
        assertEquals(
            listOf<FakeLinkRepository.Call>(FakeLinkRepository.Call.SendBoth("j", CommandType.DISABLE, future)),
            e.links.calls,
        )
    }

    @Test fun linkedSwitchBothOffNeedsAnOccurrence() = runTest {
        val e = env(linkedAlarm("j", skip = future).copy(days = emptySet()))
        e.vm.setLinkedBoth("j")
        assertTrue(e.links.calls.isEmpty())
        e.vm.state.test {
            assertEquals(false, expectMostRecentItem().rows.single().linked!!.switchOptions.both)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun linkedSwitchBothOnSendsEnable() = runTest {
        val e = env(linkedAlarm("j", enabled = false))
        e.vm.setLinkedBoth("j")
        assertEquals(
            listOf<FakeLinkRepository.Call>(FakeLinkRepository.Call.SendBoth("j", CommandType.ENABLE, null)),
            e.links.calls,
        )
    }

    @Test fun linkedSwitchIgnoresOwnRows() = runTest {
        val e = env(alarm("w"))
        e.vm.setLinkedJustMe("w")
        e.vm.setLinkedBoth("w")
        assertTrue(e.links.calls.isEmpty())
        assertTrue(e.repo.get("w")!!.enabled)
    }

    // --- Own rows the partner joined ---

    private val joinedLinks = listOf(PublishedLink("w", LinkMode.RING))

    @Test fun joinedBadgeOnlyWhenPartnerLinksIncludeTheAlarm() {
        val rows = buildAlarmsState(
            listOf(alarm("w"), alarm("x", hour = 7)), now, ZoneOffset.UTC, "Sam", partnerLinks = joinedLinks,
        ).rows.associateBy { it.id }
        assertEquals("Sam", rows.getValue("w").partnerJoined)
        assertNull(rows.getValue("x").partnerJoined)
    }

    @Test fun noBadgeWithoutPartnerState() = runTest {
        val e = env(alarm("w"))
        e.vm.state.test {
            assertNull(expectMostRecentItem().rows.single().partnerJoined)
            e.partner.set(PartnerState("Sam", emptyList(), null, null, links = joinedLinks))
            assertEquals("Sam", expectMostRecentItem().rows.single().partnerJoined)
            e.partner.set(null)
            assertNull(expectMostRecentItem().rows.single().partnerJoined)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun skipJustMeWritesSoloSkipOnRepeatingAlarm() = runTest {
        val e = env(alarm("w"))
        e.vm.skipJustMe("w")
        val a = e.repo.get("w")!!
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), a.soloSkippedOccurrenceAt)
        assertNull(a.skippedOccurrenceAt)
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test fun skipJustMeOnOneShotKeepsItEnabled() = runTest {
        val e = env(alarm("o", days = emptySet()))
        e.vm.skipJustMe("o")
        val a = e.repo.get("o")!!
        assertTrue(a.enabled)
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), a.soloSkippedOccurrenceAt)
        assertNull(a.skippedOccurrenceAt)
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test fun skipJustMeIgnoresDisabledAndJoinedRows() = runTest {
        val e = env(alarm("off", enabled = false), linkedAlarm("j"))
        e.vm.skipJustMe("off")
        e.vm.skipJustMe("j")
        assertNull(e.repo.get("off")!!.soloSkippedOccurrenceAt)
        assertNull(e.repo.get("j")!!.soloSkippedOccurrenceAt)
        assertEquals(0, e.scheduler.rescheduleCount)
    }

    @Test fun skipBothKeepsTodaysBehaviour() = runTest {
        val e = env(alarm("w"), alarm("o", days = emptySet(), hour = 7))
        e.vm.skipNext("w")
        e.vm.skipNext("o")
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), e.repo.get("w")!!.skippedOccurrenceAt)
        assertNull(e.repo.get("w")!!.soloSkippedOccurrenceAt)
        assertFalse(e.repo.get("o")!!.enabled)
        assertEquals(2, e.scheduler.rescheduleCount)
    }

    @Test fun soloSkipShowsAsSkippedForYouWhileASharedSkipStaysSkipped() {
        val rows = buildAlarmsState(
            listOf(
                alarm("solo").copy(soloSkippedOccurrenceAt = future),
                alarm("shared", skip = future),
                alarm("plain"),
                alarm("stale").copy(soloSkippedOccurrenceAt = past),
            ),
            now, ZoneOffset.UTC,
        ).rows.associateBy { it.id }
        assertTrue(rows.getValue("solo").skipped)
        assertTrue(rows.getValue("solo").skippedSolo)
        assertEquals(Instant.parse("2026-11-03T06:30:00Z"), rows.getValue("solo").nextRing)
        assertTrue(rows.getValue("shared").skipped)
        assertFalse(rows.getValue("shared").skippedSolo)
        assertFalse(rows.getValue("plain").skipped)
        assertFalse(rows.getValue("stale").skipped)
        assertFalse(rows.getValue("stale").skippedSolo)
    }

    @Test fun unskipClearsBothSkipsWithOneReschedule() = runTest {
        val e = env(alarm("w", skip = future).copy(soloSkippedOccurrenceAt = future))
        e.vm.unskip("w")
        val a = e.repo.get("w")!!
        assertNull(a.skippedOccurrenceAt)
        assertNull(a.soloSkippedOccurrenceAt)
        assertEquals(1, e.scheduler.rescheduleCount)
    }

    @Test fun switchOnAJoinedOwnAlarmIsStillASingleWrite() = runTest {
        val e = env(alarm("w"))
        e.partner.set(PartnerState("Sam", emptyList(), null, null, links = joinedLinks))
        e.vm.setEnabled("w", false)
        assertFalse(e.repo.get("w")!!.enabled)
        assertNull(e.repo.get("w")!!.soloSkippedOccurrenceAt)
        assertEquals(1, e.scheduler.rescheduleCount)
        assertTrue(e.links.calls.isEmpty())
    }

    // An own one-shot skipped "just me" is on with no next ring; the screen shows the skipped text, not "Off".

    @Test fun soloSkippedOwnOneShotIsOnWithNoNextRing() {
        val oneShot = alarm("w", days = emptySet()).copy(soloSkippedOccurrenceAt = future)
        val row = buildAlarmsState(listOf(oneShot), now, ZoneOffset.UTC).rows.single()
        assertTrue(row.enabled)
        assertNull(row.nextRing)
        assertNull(row.ringsInText)
        assertTrue(row.skipped)
        assertTrue(row.skippedSolo)
    }

    // A failing switch action must not crash the app.

    private class ThrowingLinks(private val delegate: FakeLinkRepository = FakeLinkRepository()) :
        me.shawnsquire.tandem.sync.LinkRepository by delegate {
        override suspend fun setJoinerEnabled(alarmId: String, enabled: Boolean) = throw java.io.IOException("boom")
        override suspend fun sendBoth(alarmId: String, type: CommandType, occurrenceAt: Instant?) =
            throw java.io.IOException("boom")
    }

    @Test fun linkedSwitchActionsSwallowFailures() = runTest {
        val repo = FakeAlarmRepository(listOf(linkedAlarm("j")))
        val partner = FakePartnerRepository(partnerWith(ownerAlarm()))
        val vm = AlarmsViewModel(
            repo, AlarmEdits(repo, FakeAlarmScheduler()), ThrowingLinks(), FakePairingRepository(), partner, clock,
            MutableStateFlow(Unit),
        ) {}
        vm.setLinkedJustMe("j")
        vm.setLinkedBoth("j")
        assertEquals(linkedAlarm("j"), repo.get("j"))
    }
}
