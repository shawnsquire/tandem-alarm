package me.shawnsquire.tandem.ui.alarms.linked

import app.cash.turbine.test
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeLinkRepository
import me.shawnsquire.tandem.fakes.FakeLinkRepository.Call
import me.shawnsquire.tandem.fakes.FakePairingRepository
import me.shawnsquire.tandem.fakes.FakePartnerRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth
import me.shawnsquire.tandem.sync.PartnerAlarm
import me.shawnsquire.tandem.sync.PartnerState
import me.shawnsquire.tandem.ui.MainDispatcherRule
import me.shawnsquire.tandem.ui.alarms.AlarmEdits
import me.shawnsquire.tandem.ui.partner.CommandUiStatus
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

class LinkedAlarmViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    // Monday 2026-11-02 05:00 UTC.
    private val now = Instant.parse("2026-11-02T05:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val ownerNext = Instant.parse("2026-11-03T06:30:00Z")
    private val weekdays = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    private fun link(
        label: String? = null, ringtone: String? = null, wallpaper: String? = null, pending: PendingBoth? = null,
    ) = AlarmLink("u", "o1", LinkMode.RING, null, true, pending, label, ringtone, wallpaper)

    private fun joined(
        enabled: Boolean = true, days: Set<DayOfWeek> = weekdays, link: AlarmLink = link(),
    ) = Alarm("j", "Gym", 6, 30, days, enabled, null, null, null, link = link)

    private fun partnerState(next: Instant? = ownerNext) = PartnerState(
        "Shawn",
        listOf(PartnerAlarm("o1", "Gym", 6, 30, weekdays, true, next, null)),
        ringing = null, lastSeen = null,
    )

    private inner class Env(row: Alarm?, partner: PartnerState? = partnerState()) {
        val repo = FakeAlarmRepository(listOfNotNull(row))
        val scheduler = FakeAlarmScheduler()
        val links = FakeLinkRepository()
        val deletedRingtones = mutableListOf<String>()
        val deletedWallpapers = mutableListOf<String>()
        var ringtoneImport: () -> String = { "/files/new.mp3" }
        var wallpaperImport: () -> String = { "/files/new.jpg" }
        val vm = LinkedAlarmViewModel(
            alarmId = "j",
            repository = repo,
            links = links,
            actions = LinkedAlarmActions(links, AlarmEdits(repo, scheduler)),
            pairing = FakePairingRepository(),
            partnerRepository = FakePartnerRepository(partner),
            clock = clock,
            ringtoneTitle = { it ?: "Default" },
            importRingtone = { ringtoneImport() },
            deleteRingtoneFile = { deletedRingtones += it },
            importWallpaper = { wallpaperImport() },
            deleteWallpaperFile = { deletedWallpapers += it },
            rejectedReason = "rejected",
        )
    }

    private suspend fun Env.latest(): LinkedSheetState {
        var s: LinkedSheetState? = null
        vm.state.test { s = expectMostRecentItem(); cancelAndIgnoreRemainingEvents() }
        return s!!
    }

    @Test fun stateShowsTheJoinedRow() = runTest {
        val e = Env(joined(link = link(label = "Wake up")))
        val s = e.latest()
        assertTrue(s.loaded)
        assertFalse(s.closed)
        assertEquals("Wake up", s.label)
        assertEquals("Shawn", s.partnerName)
        assertEquals(LinkMode.RING, s.mode)
        assertTrue(s.armed)
        assertTrue(s.bothAvailable)
    }

    @Test fun closesWhenTheRowIsGone() = runTest {
        val e = Env(null)
        assertTrue(e.latest().closed)
    }

    @Test fun setModeCallsTheRepository() = runTest {
        val e = Env(joined())
        e.vm.setMode(LinkMode.NOTIFY)
        assertEquals(listOf<Call>(Call.SetMode("j", LinkMode.NOTIFY)), e.links.calls)
    }

    @Test fun skipJustMeRepeatingSkipsTheRowsNextOccurrence() = runTest {
        val e = Env(joined())
        e.vm.skipJustMe()
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), e.repo.get("j")!!.skippedOccurrenceAt)
        assertEquals(1, e.scheduler.rescheduleCount)
        assertTrue(e.links.calls.isEmpty())
    }

    @Test fun skipJustMeOneShotTurnsItOffForMe() = runTest {
        val e = Env(joined(days = emptySet()))
        e.vm.skipJustMe()
        assertEquals(listOf<Call>(Call.SetJoinerEnabled("j", false)), e.links.calls)
        assertNull(e.repo.get("j")!!.skippedOccurrenceAt)
    }

    @Test fun skipBothPinsToThePartnerTabsOccurrence() = runTest {
        val e = Env(joined())
        e.vm.skipBoth()
        assertEquals(listOf<Call>(Call.SendBoth("j", CommandType.SKIP_NEXT, ownerNext)), e.links.calls)
    }

    @Test fun bothOfUsIsUnavailableWithoutAPin() = runTest {
        val e = Env(joined(), partnerState(next = null))
        assertFalse(e.latest().bothAvailable)
        e.vm.skipBoth()
        assertTrue(e.links.calls.isEmpty())
        val none = Env(joined(), partner = null)
        assertFalse(none.latest().bothAvailable)
        none.vm.skipBoth()
        assertTrue(none.links.calls.isEmpty())
    }

    @Test fun unskipClearsMySkipAndIsOfferedInsteadOfSkip() = runTest {
        val e = Env(joined().copy(skippedOccurrenceAt = Instant.parse("2026-11-02T06:30:00Z")))
        assertTrue(e.latest().skippedForYou)
        e.vm.unskip()
        assertNull(e.repo.get("j")!!.skippedOccurrenceAt)
        assertFalse(e.latest().skippedForYou)
    }

    @Test fun skipIsNotOfferedWhenTheRowIsNotArmed() = runTest {
        val e = Env(joined(enabled = false))
        assertFalse(e.latest().armed)
        e.vm.skipJustMe()
        e.vm.skipBoth()
        assertNull(e.repo.get("j")!!.skippedOccurrenceAt)
        assertTrue(e.links.calls.isEmpty())
    }

    @Test fun bothOfUsStatusFollowsPendingThenTheOwnersState() = runTest {
        // The fake only records the call, so the row carries the pending command the real sendBoth would write.
        val e = Env(joined(link = link(pending = PendingBoth(CommandType.SKIP_NEXT, ownerNext, "c1"))))
        e.vm.skipBoth()
        assertEquals(CommandUiStatus.Pending, e.latest().bothStatus)
        e.repo.updateLink("j") { it.copy(pendingBoth = null, ownerSkippedOccurrenceAt = ownerNext) }
        assertEquals(CommandUiStatus.Applied, e.latest().bothStatus)
    }

    @Test fun bothOfUsStatusIsRejectedWhenPendingClearsWithoutEffect() = runTest {
        val e = Env(joined())
        e.vm.skipBoth()
        assertEquals(CommandUiStatus.Rejected("rejected"), e.latest().bothStatus)
    }

    @Test fun messageKeepsTheOtherOverrides() = runTest {
        val e = Env(joined(link = link(ringtone = "content://sys/1", wallpaper = "/files/w.jpg")))
        e.vm.setMessage("  Rise and shine ")
        assertEquals(
            listOf<Call>(Call.SetOverrides("j", "Rise and shine", "content://sys/1", "/files/w.jpg")),
            e.links.calls,
        )
    }

    @Test fun blankMessageResetsToThePartnersAndUnchangedIsIgnored() = runTest {
        val e = Env(joined(link = link(label = "Hi", wallpaper = "/files/w.jpg")))
        e.vm.setMessage("Hi")
        assertTrue(e.links.calls.isEmpty())
        e.vm.setMessage("   ")
        assertEquals(listOf<Call>(Call.SetOverrides("j", null, null, "/files/w.jpg")), e.links.calls)
    }

    @Test fun systemRingtoneIsStoredAsReturnedAndOtherFieldsKept() = runTest {
        val e = Env(joined(link = link(label = "Hi", wallpaper = "/files/w.jpg")))
        e.vm.onSystemRingtonePicked("content://media/internal/audio/media/9")
        assertEquals(
            listOf<Call>(Call.SetOverrides("j", "Hi", "content://media/internal/audio/media/9", "/files/w.jpg")),
            e.links.calls,
        )
        assertTrue(e.deletedRingtones.isEmpty())
    }

    @Test fun pickedFileIsImportedAndTheOldImportDeleted() = runTest {
        val e = Env(joined(link = link(label = "Hi", ringtone = "/files/old.mp3")))
        e.vm.onRingtoneFilePicked("content://doc/1")
        assertEquals(listOf<Call>(Call.SetOverrides("j", "Hi", "/files/new.mp3", null)), e.links.calls)
        assertEquals(listOf("/files/old.mp3"), e.deletedRingtones)
    }

    @Test fun resettingRingtoneDeletesTheOldImport() = runTest {
        val e = Env(joined(link = link(ringtone = "/files/old.mp3", wallpaper = "/files/w.jpg")))
        e.vm.useOwnersRingtone()
        assertEquals(listOf<Call>(Call.SetOverrides("j", null, null, "/files/w.jpg")), e.links.calls)
        assertEquals(listOf("/files/old.mp3"), e.deletedRingtones)
    }

    @Test fun ringtoneImportFailureShowsAnErrorAndChangesNothing() = runTest {
        val e = Env(joined(link = link(ringtone = "/files/old.mp3")))
        e.ringtoneImport = { throw java.io.IOException("bad") }
        e.vm.onRingtoneFilePicked("content://doc/1")
        assertTrue(e.links.calls.isEmpty())
        assertTrue(e.deletedRingtones.isEmpty())
        assertEquals(LinkedError.RingtoneImport, e.latest().error)
        e.vm.dismissError()
        assertNull(e.latest().error)
    }

    @Test fun wallpaperReplaceAndResetDeleteTheOldImage() = runTest {
        val e = Env(joined(link = link(label = "Hi", ringtone = "content://sys/1", wallpaper = "/files/old.jpg")))
        e.vm.onWallpaperPicked("content://img/1")
        assertEquals(
            listOf<Call>(Call.SetOverrides("j", "Hi", "content://sys/1", "/files/new.jpg")),
            e.links.calls,
        )
        assertEquals(listOf("/files/old.jpg"), e.deletedWallpapers)
        val reset = Env(joined(link = link(label = "Hi", wallpaper = "/files/old.jpg")))
        reset.vm.useOwnersWallpaper()
        assertEquals(listOf<Call>(Call.SetOverrides("j", "Hi", null, null)), reset.links.calls)
        assertEquals(listOf("/files/old.jpg"), reset.deletedWallpapers)
    }

    @Test fun wallpaperImportFailureShowsAnErrorAndChangesNothing() = runTest {
        val e = Env(joined())
        e.wallpaperImport = { throw java.io.IOException("bad") }
        e.vm.onWallpaperPicked("content://img/1")
        assertTrue(e.links.calls.isEmpty())
        assertEquals(LinkedError.WallpaperImport, e.latest().error)
    }

    @Test fun leaveCallsTheRepositoryThenCloses() = runTest {
        val e = Env(joined())
        var left = false
        e.vm.leave { left = true }
        assertEquals(listOf<Call>(Call.Leave("j")), e.links.calls)
        assertTrue(left)
    }

    @Test fun leaveConfirmIsAnInPageStep() = runTest {
        val e = Env(joined())
        e.vm.askLeave()
        assertTrue(e.latest().confirmingLeave)
        e.vm.cancelLeave()
        assertFalse(e.latest().confirmingLeave)
        assertTrue(e.links.calls.isEmpty())
    }

    // A pending command the sheet did not send itself (the Alarms switch, a restart, a recreated VM).

    @Test fun adoptsAPendingDisableAndSettlesItToApplied() = runTest {
        val e = Env(joined(link = link(pending = PendingBoth(CommandType.DISABLE, ownerNext, "c1"))))
        assertEquals(CommandUiStatus.Pending, e.latest().bothStatus)
        e.repo.upsert(joined(enabled = false))
        assertEquals(CommandUiStatus.Applied, e.latest().bothStatus)
    }

    @Test fun adoptsAPendingEnableAndSettlesItToApplied() = runTest {
        val e = Env(joined(enabled = false, link = link(pending = PendingBoth(CommandType.ENABLE, null, "c1"))))
        assertEquals(CommandUiStatus.Pending, e.latest().bothStatus)
        e.repo.upsert(joined(enabled = true))
        assertEquals(CommandUiStatus.Applied, e.latest().bothStatus)
    }

    @Test fun anAdoptedCommandThatClearsWithoutEffectIsRejected() = runTest {
        val e = Env(joined(link = link(pending = PendingBoth(CommandType.DISABLE, ownerNext, "c1"))))
        assertEquals(CommandUiStatus.Pending, e.latest().bothStatus)
        e.repo.updateLink("j") { it.copy(pendingBoth = null) }
        assertEquals(CommandUiStatus.Rejected("rejected"), e.latest().bothStatus)
    }

    @Test fun closingTheSheetKeepsAPendingStatusAndClearsASettledOne() = runTest {
        val e = Env(joined(link = link(pending = PendingBoth(CommandType.DISABLE, ownerNext, "c1"))))
        e.latest()
        e.vm.onSheetClosed()
        assertEquals(CommandUiStatus.Pending, e.latest().bothStatus)
        e.repo.upsert(joined(enabled = false))
        e.vm.onSheetClosed()
        assertNull(e.latest().bothStatus)
    }

    // A settled outcome is latched, so later changes to the row do not rewrite it.

    private val pendingSkip = PendingBoth(CommandType.SKIP_NEXT, ownerNext, "c1")

    /** Sends "both of us" on a row already carrying the pending command (the real sendBoth writes it). */
    private suspend fun Env.sendSkipBothAndSettle(applyToRow: suspend () -> Unit) {
        vm.skipBoth()
        assertEquals(CommandUiStatus.Pending, latest().bothStatus)
        applyToRow()
    }

    @Test fun aSettledOutcomeStaysAppliedWhenTheOwnerSkipIsLaterCleared() = runTest {
        val e = Env(joined(link = link(pending = pendingSkip)))
        e.sendSkipBothAndSettle {
            e.repo.updateLink("j") { it.copy(pendingBoth = null, ownerSkippedOccurrenceAt = ownerNext) }
        }
        assertEquals(CommandUiStatus.Applied, e.latest().bothStatus)
        e.repo.updateLink("j") { it.copy(ownerSkippedOccurrenceAt = null) }
        assertEquals(CommandUiStatus.Applied, e.latest().bothStatus)
    }

    @Test fun aOneShotSkipStaysAppliedWhenTheOwnerTurnsTheAlarmBackOn() = runTest {
        val e = Env(joined(days = emptySet(), link = link(pending = pendingSkip)))
        e.sendSkipBothAndSettle {
            e.repo.upsert(joined(days = emptySet(), enabled = false))
        }
        assertEquals(CommandUiStatus.Applied, e.latest().bothStatus)
        e.repo.upsert(joined(days = emptySet(), enabled = true))
        assertEquals(CommandUiStatus.Applied, e.latest().bothStatus)
    }

    @Test fun aSettledOutcomeIsNotShownAfterTheSheetReopens() = runTest {
        val e = Env(joined(link = link(pending = pendingSkip)))
        e.sendSkipBothAndSettle {
            e.repo.updateLink("j") { it.copy(pendingBoth = null, ownerSkippedOccurrenceAt = ownerNext) }
        }
        assertEquals(CommandUiStatus.Applied, e.latest().bothStatus)
        e.vm.onSheetOpened()
        assertNull(e.latest().bothStatus)
    }

    @Test fun reopeningKeepsAStillPendingStatus() = runTest {
        val e = Env(joined(link = link(pending = PendingBoth(CommandType.DISABLE, ownerNext, "c1"))))
        assertEquals(CommandUiStatus.Pending, e.latest().bothStatus)
        e.vm.onSheetOpened()
        assertEquals(CommandUiStatus.Pending, e.latest().bothStatus)
    }

    @Test fun aClosedStateDoesNotSurviveTheSubscriptionForARejoinedRow() = runTest {
        val e = Env(joined())
        e.vm.state.test {
            e.repo.delete("j")
            assertTrue(expectMostRecentItem().closed)
            cancelAndIgnoreRemainingEvents()
        }
        advanceTimeBy(6_000)
        e.repo.upsert(joined())
        assertFalse(e.vm.state.value.closed)
        e.vm.onSheetOpened()
        assertFalse(e.latest().closed)
    }
}
