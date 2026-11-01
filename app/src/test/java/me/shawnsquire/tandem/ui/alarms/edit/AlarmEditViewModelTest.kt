package me.shawnsquire.tandem.ui.alarms.edit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeLinkRepository
import me.shawnsquire.tandem.fakes.FakePairingRepository
import me.shawnsquire.tandem.fakes.FakePartnerRepository
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.LateRing
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.sync.LinkRepository
import me.shawnsquire.tandem.sync.PartnerState
import me.shawnsquire.tandem.sync.PublishedLink
import me.shawnsquire.tandem.ui.partner.CommandUiStatus
import kotlinx.coroutines.flow.flowOf
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.ui.MainDispatcherRule
import me.shawnsquire.tandem.ui.alarms.AlarmEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant

class AlarmEditViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val skip = Instant.parse("2026-11-03T06:30:00Z")
    private val existing = Alarm(
        "a1", "Work", 6, 30, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), false, skip,
        "file:///old-ring", "/old-wall",
    )

    /** Holds every upsert until [gate] completes, so tests can act mid-save. */
    private class GatedRepo(val inner: FakeAlarmRepository, val gate: CompletableDeferred<Unit>) : AlarmRepository by inner {
        override suspend fun upsert(alarm: Alarm) { gate.await(); inner.upsert(alarm) }
    }

    private class Env(
        id: String?,
        initial: List<Alarm>,
        gate: CompletableDeferred<Unit>? = null,
        linkOverride: ((FakeLinkRepository) -> LinkRepository)? = null,
        schedulerOverride: ((FakeAlarmRepository) -> me.shawnsquire.tandem.alarm.AlarmScheduler)? = null,
    ) {
        val repo = FakeAlarmRepository(initial)
        private val vmRepo: AlarmRepository = if (gate == null) repo else GatedRepo(repo, gate)
        val scheduler = FakeAlarmScheduler()
        val titleCalls = mutableListOf<String?>()
        val importedRingtones = mutableListOf<String>()
        val importedWallpapers = mutableListOf<String>()
        val deletedRingtones = mutableListOf<String>()
        val deletedWallpapers = mutableListOf<String>()
        var failImport = false
        val links = FakeLinkRepository()
        val pairing = FakePairingRepository()
        val partner = FakePartnerRepository(null)
        val vm = AlarmEditViewModel(
            alarmId = id,
            repository = vmRepo,
            edits = AlarmEdits(vmRepo, schedulerOverride?.invoke(repo) ?: scheduler),
            ringtoneTitle = { titleCalls += it; "title:${it ?: "default"}" },
            importRingtone = {
                importedRingtones += it
                if (failImport) error("boom")
                "file:///imported-${importedRingtones.size}"
            },
            deleteRingtoneFile = { deletedRingtones += it },
            importWallpaper = {
                importedWallpapers += it
                if (failImport) error("boom")
                "/wall-${importedWallpapers.size}"
            },
            deleteWallpaperFile = { deletedWallpapers += it },
            newId = { "new-id" },
            links = linkOverride?.invoke(links) ?: links,
            pairing = pairing,
            partnerRepository = partner,
            inviteFailedReason = "invite failed",
        )
    }

    private fun edit() = Env("a1", listOf(existing))
    private fun fresh() = Env(null, emptyList())

    @Test fun newAlarmHasDefaultsAndSaves() = runTest {
        val e = fresh()
        val s = e.vm.state.value
        assertTrue(s.loaded && s.isNew)
        assertEquals(7, s.hour); assertEquals(0, s.minute); assertTrue(s.days.isEmpty())
        e.vm.setTime(9, 15); e.vm.setLabel(" Gym "); e.vm.toggleDay(DayOfWeek.FRIDAY)
        e.vm.save()
        val saved = e.repo.get("new-id")!!
        assertEquals(Alarm("new-id", "Gym", 9, 15, setOf(DayOfWeek.FRIDAY), true, null, null, null), saved)
        assertEquals(1, e.scheduler.rescheduleCount)
        assertTrue(e.vm.state.value.closed)
    }

    @Test fun editLoadsAndSavePreservesIdAndReenables() = runTest {
        val e = edit()
        assertEquals("Work", e.vm.state.value.label)
        e.vm.setLabel("Office")
        e.vm.save()
        val saved = e.repo.get("a1")!!
        assertEquals("Office", saved.label)
        assertTrue(saved.enabled)
    }

    @Test fun missingAlarmCloses() = runTest {
        val e = Env("nope", emptyList())
        assertTrue(e.vm.state.value.closed)
    }

    @Test fun changingTimeClearsSkip() = runTest {
        val e = edit()
        e.vm.setTime(7, 0)
        e.vm.save()
        assertNull(e.repo.get("a1")!!.skippedOccurrenceAt)
    }

    @Test fun changingDaysClearsSkip() = runTest {
        val e = edit()
        e.vm.toggleDay(DayOfWeek.WEDNESDAY)
        e.vm.save()
        assertNull(e.repo.get("a1")!!.skippedOccurrenceAt)
    }

    @Test fun unchangedTimeAndDaysKeepSkip() = runTest {
        val e = edit()
        e.vm.setLabel("x")
        e.vm.save()
        assertEquals(skip, e.repo.get("a1")!!.skippedOccurrenceAt)
    }

    @Test fun labelEditKeepsSoloSkip() = runTest {
        val solo = Instant.parse("2026-11-04T06:30:00Z")
        val e = Env("a1", listOf(existing.copy(soloSkippedOccurrenceAt = solo)))
        e.vm.setLabel("x")
        e.vm.save()
        val saved = e.repo.get("a1")!!
        assertEquals("x", saved.label)
        assertEquals(solo, saved.soloSkippedOccurrenceAt)
    }

    @Test fun timeEditClearsSoloSkip() = runTest {
        val e = Env("a1", listOf(existing.copy(soloSkippedOccurrenceAt = Instant.parse("2026-11-04T06:30:00Z"))))
        e.vm.setTime(7, 0)
        e.vm.save()
        assertNull(e.repo.get("a1")!!.soloSkippedOccurrenceAt)
    }

    @Test fun labelEditKeepsLateRingAndTimeEditClearsIt() = runTest {
        val late = LateRing(Instant.parse("2026-11-04T06:30:00Z"), false)
        val keep = Env("a1", listOf(existing.copy(lateRing = late)))
        keep.vm.setLabel("x")
        keep.vm.save()
        assertEquals(late, keep.repo.get("a1")!!.lateRing)
        val clear = Env("a1", listOf(existing.copy(lateRing = late)))
        clear.vm.setTime(7, 0)
        clear.vm.save()
        assertNull(clear.repo.get("a1")!!.lateRing)
    }

    @Test fun titleIsLookedUpForCurrentUri() = runTest {
        val e = edit()
        assertEquals(listOf<String?>("file:///old-ring"), e.titleCalls)
        assertEquals("title:file:///old-ring", e.vm.state.value.ringtoneTitle)
    }

    @Test fun ringtoneFilePickImportsAndStoresReturnedUri() = runTest {
        val e = edit()
        e.vm.onRingtoneFilePicked("content://doc/1")
        assertEquals(listOf("content://doc/1"), e.importedRingtones)
        assertEquals("file:///imported-1", e.vm.state.value.ringtoneUri)
        assertEquals("title:file:///imported-1", e.vm.state.value.ringtoneTitle)
        e.vm.save()
        assertEquals("file:///imported-1", e.repo.get("a1")!!.ringtoneUri)
        assertEquals(listOf("file:///old-ring"), e.deletedRingtones)
    }

    @Test fun systemPickStoresUriAsReturnedWithoutImport() = runTest {
        val e = edit()
        e.vm.onSystemRingtonePicked("content://media/internal/audio/media/9")
        assertTrue(e.importedRingtones.isEmpty())
        assertEquals("content://media/internal/audio/media/9", e.vm.state.value.ringtoneUri)
        e.vm.save()
        assertEquals("content://media/internal/audio/media/9", e.repo.get("a1")!!.ringtoneUri)
        assertEquals(listOf("file:///old-ring"), e.deletedRingtones)
    }

    @Test fun replacingImportedRingtoneTwiceDeletesIntermediate() = runTest {
        val e = edit()
        e.vm.onRingtoneFilePicked("content://doc/1")
        e.vm.onRingtoneFilePicked("content://doc/2")
        assertEquals(listOf("file:///imported-1"), e.deletedRingtones)
        e.vm.onSystemRingtonePicked("content://media/x")
        assertEquals(listOf("file:///imported-1", "file:///imported-2"), e.deletedRingtones)
    }

    @Test fun cancelDeletesSessionRingtoneButNotOriginal() = runTest {
        val e = edit()
        e.vm.onRingtoneFilePicked("content://doc/1")
        e.vm.cancel()
        assertEquals(listOf("file:///imported-1"), e.deletedRingtones)
        assertTrue(e.vm.state.value.closed)
        assertEquals(existing, e.repo.get("a1"))
        assertEquals(0, e.scheduler.rescheduleCount)
    }

    @Test fun wallpaperPickAndSaveDeletesOriginal() = runTest {
        val e = edit()
        e.vm.onWallpaperPicked("content://img/1")
        assertEquals("/wall-1", e.vm.state.value.wallpaperPath)
        e.vm.save()
        assertEquals("/wall-1", e.repo.get("a1")!!.wallpaperPath)
        assertEquals(listOf("/old-wall"), e.deletedWallpapers)
        assertTrue(e.deletedRingtones.isEmpty())
    }

    @Test fun removeWallpaperThenSaveDeletesOriginalOnly() = runTest {
        val e = edit()
        e.vm.removeWallpaper()
        assertNull(e.vm.state.value.wallpaperPath)
        assertTrue(e.deletedWallpapers.isEmpty())
        e.vm.save()
        assertNull(e.repo.get("a1")!!.wallpaperPath)
        assertEquals(listOf("/old-wall"), e.deletedWallpapers)
    }

    @Test fun replacingAndRemovingSessionWallpaperDeletesItImmediately() = runTest {
        val e = edit()
        e.vm.onWallpaperPicked("content://img/1")
        e.vm.onWallpaperPicked("content://img/2")
        assertEquals(listOf("/wall-1"), e.deletedWallpapers)
        e.vm.removeWallpaper()
        assertEquals(listOf("/wall-1", "/wall-2"), e.deletedWallpapers)
    }

    @Test fun cancelDeletesSessionWallpaperNotOriginal() = runTest {
        val e = edit()
        e.vm.onWallpaperPicked("content://img/1")
        e.vm.cancel()
        assertEquals(listOf("/wall-1"), e.deletedWallpapers)
        e.vm.discardSession()
        assertEquals(listOf("/wall-1"), e.deletedWallpapers)
    }

    @Test fun importFailureSurfacesErrorAndLeavesStateUnchanged() = runTest {
        val e = edit()
        e.failImport = true
        val before = e.vm.state.value
        e.vm.onRingtoneFilePicked("content://doc/1")
        assertEquals(EditError.RingtoneImport, e.vm.state.value.error)
        assertEquals(before.copy(error = EditError.RingtoneImport), e.vm.state.value)
        e.vm.dismissError()
        e.vm.onWallpaperPicked("content://img/1")
        assertEquals(EditError.WallpaperImport, e.vm.state.value.error)
        assertEquals(before.copy(error = EditError.WallpaperImport), e.vm.state.value)
        assertFalse(e.vm.state.value.busy)
        assertTrue(e.deletedRingtones.isEmpty() && e.deletedWallpapers.isEmpty())
    }

    @Test fun clearedWithoutSaveDeletesSessionImports() = runTest {
        val e = edit()
        e.vm.onRingtoneFilePicked("content://doc/1")
        e.vm.onWallpaperPicked("content://img/1")
        e.vm.onCleared()
        assertEquals(listOf("file:///imported-1"), e.deletedRingtones)
        assertEquals(listOf("/wall-1"), e.deletedWallpapers)
    }

    @Test fun clearedAfterSaveKeepsSavedFiles() = runTest {
        val e = edit()
        e.vm.onRingtoneFilePicked("content://doc/1")
        e.vm.onWallpaperPicked("content://img/1")
        e.vm.save()
        val deletedAtSave = e.deletedRingtones.toList() to e.deletedWallpapers.toList()
        e.vm.onCleared()
        assertEquals(deletedAtSave, e.deletedRingtones.toList() to e.deletedWallpapers.toList())
        assertFalse("file:///imported-1" in e.deletedRingtones)
        assertFalse("/wall-1" in e.deletedWallpapers)
        assertEquals("file:///imported-1", e.repo.get("a1")!!.ringtoneUri)
    }

    @Test fun clearedAfterCancelDoesNotDeleteTwice() = runTest {
        val e = edit()
        e.vm.onRingtoneFilePicked("content://doc/1")
        e.vm.cancel()
        e.vm.onCleared()
        assertEquals(listOf("file:///imported-1"), e.deletedRingtones)
    }

    @Test fun saveKeepsPartnerSkipAppliedWhileEditorWasOpen() = runTest {
        val e = Env("a1", listOf(existing.copy(skippedOccurrenceAt = null)))
        val partnerSkip = Instant.parse("2026-11-10T06:30:00Z")
        e.repo.setSkip("a1", partnerSkip)
        e.vm.setLabel("renamed")
        e.vm.save()
        assertEquals(partnerSkip, e.repo.get("a1")!!.skippedOccurrenceAt)
        assertEquals("renamed", e.repo.get("a1")!!.label)
    }

    @Test fun saveAfterRowDeletedElsewhereUpsertsWithNoSkip() = runTest {
        val e = edit()
        e.repo.delete("a1")
        e.vm.save()
        val saved = e.repo.get("a1")!!
        assertNull(saved.skippedOccurrenceAt)
        assertTrue(e.deletedRingtones.isEmpty() && e.deletedWallpapers.isEmpty())
    }

    @Test fun leavingDuringSaveDeletesNothingAndSavedFilesSurvive() = runTest {
        val gate = CompletableDeferred<Unit>()
        val e = Env("a1", listOf(existing), gate)
        e.vm.onWallpaperPicked("content://img/1")
        e.vm.onRingtoneFilePicked("content://doc/1")
        e.vm.save() // suspends in the gated upsert
        e.vm.cancel()
        e.vm.removeWallpaper()
        e.vm.discardSession()
        e.vm.onSystemRingtonePicked("content://media/x")
        e.vm.onWallpaperPicked("content://img/2")
        e.vm.onRingtoneFilePicked("content://doc/2")
        assertFalse(e.vm.state.value.closed)
        gate.complete(Unit)
        val saved = e.repo.get("a1")!!
        assertEquals("/wall-1", saved.wallpaperPath)
        assertEquals("file:///imported-1", saved.ringtoneUri)
        assertEquals(listOf("/old-wall"), e.deletedWallpapers)
        assertEquals(listOf("file:///old-ring"), e.deletedRingtones)
        assertTrue(e.vm.state.value.closed)
    }

    @Test fun clearedDuringSaveThatCommitsKeepsSavedFiles() = runTest {
        val gate = CompletableDeferred<Unit>()
        val e = Env("a1", listOf(existing), gate)
        e.vm.onWallpaperPicked("content://img/1")
        e.vm.save()
        e.vm.onCleared()
        assertTrue(e.deletedWallpapers.isEmpty())
        gate.complete(Unit)
        assertEquals("/wall-1", e.repo.get("a1")!!.wallpaperPath)
        assertFalse("/wall-1" in e.deletedWallpapers)
    }

    @Test fun failedSaveKeepsSessionFilesForRetry() = runTest {
        val gate = CompletableDeferred<Unit>()
        gate.completeExceptionally(IllegalStateException("db"))
        val e = Env("a1", listOf(existing), gate)
        e.vm.onWallpaperPicked("content://img/1")
        e.vm.save()
        assertEquals(EditError.Save, e.vm.state.value.error)
        assertFalse(e.vm.state.value.busy)
        assertTrue(e.deletedWallpapers.isEmpty())
        e.vm.cancel()
        assertEquals(listOf("/wall-1"), e.deletedWallpapers)
    }

    @Test fun rescheduleFailingAfterCommitKeepsSavedFilesEvenIfRowChangedMeanwhile() = runTest {
        val e = Env("a1", listOf(existing)) { repo ->
            object : me.shawnsquire.tandem.alarm.AlarmScheduler {
                override suspend fun rescheduleAll() {
                    repo.setSkip("a1", Instant.parse("2026-12-01T06:30:00Z")) // a partner write lands in between
                    error("scheduler down")
                }
            }
        }
        e.vm.onWallpaperPicked("content://img/1")
        e.vm.onRingtoneFilePicked("content://doc/1")
        e.vm.save()
        // Committed: the stored row uses the new files, so nothing it references may be deleted.
        assertEquals("/wall-1", e.repo.get("a1")!!.wallpaperPath)
        assertFalse("/wall-1" in e.deletedWallpapers)
        assertFalse("file:///imported-1" in e.deletedRingtones)
        assertEquals(listOf("/old-wall"), e.deletedWallpapers)
        e.vm.cancel()
        e.vm.onCleared()
        assertFalse("/wall-1" in e.deletedWallpapers)
        assertFalse("file:///imported-1" in e.deletedRingtones)
    }

    private fun failingEnv(): Env {
        val gate = CompletableDeferred<Unit>().also { it.completeExceptionally(IllegalStateException("db")) }
        return Env("a1", listOf(existing), gate)
    }

    @Test fun labelOnlyEditWithFailedUpsertShowsErrorAndKeepsEditorOpen() = runTest {
        val e = failingEnv()
        e.vm.setLabel("new label")
        e.vm.save()
        assertEquals(EditError.Save, e.vm.state.value.error)
        assertFalse(e.vm.state.value.closed)
        assertFalse(e.vm.state.value.busy)
        assertTrue(e.deletedRingtones.isEmpty() && e.deletedWallpapers.isEmpty())
        assertEquals("Work", e.repo.get("a1")!!.label)
    }

    @Test fun timeOnlyEditWithFailedUpsertShowsError() = runTest {
        val e = failingEnv()
        e.vm.setTime(8, 15)
        e.vm.save()
        assertEquals(EditError.Save, e.vm.state.value.error)
        assertFalse(e.vm.state.value.closed)
        assertTrue(e.deletedRingtones.isEmpty() && e.deletedWallpapers.isEmpty())
    }

    // --- Invite / joined row ---

    private suspend fun paired(e: Env) = e.pairing.join("ABC123") // partnerName "Alex"

    @Test fun inviteIsOfferedForASavedOwnAlarmWhilePaired() = runTest {
        val e = edit()
        assertEquals(EditInvite.Hidden, e.vm.state.value.invite) // unpaired
        paired(e)
        assertEquals(EditInvite.Invite, e.vm.state.value.invite)
        assertEquals("Alex", e.vm.state.value.partnerName)
        e.pairing.unpair()
        assertEquals(EditInvite.Hidden, e.vm.state.value.invite)
    }

    @Test fun inviteIsHiddenForANewAlarm() = runTest {
        val e = fresh()
        paired(e)
        assertEquals(EditInvite.Hidden, e.vm.state.value.invite)
    }

    @Test fun invitingCollectsTheLinkRepositoryAndSettlesBackWhenNotOpen() = runTest {
        val e = edit()
        paired(e)
        e.vm.invite()
        assertEquals(listOf<FakeLinkRepository.Call>(FakeLinkRepository.Call.Invite("a1")), e.links.calls)
        // The fake's flow ends APPLIED; the row stays Invited until the partner joins or the invite closes.
        assertEquals(EditInvite.Invited, e.vm.state.value.invite)
        assertNull(e.vm.state.value.inviteStatus)
    }

    @Test fun invitedStaysThroughTheGapBetweenTheInviteClosingAndTheLinksArriving() = runTest {
        val e = edit()
        paired(e)
        e.vm.invite()
        e.links.openInvites.value = setOf("a1")
        e.links.openInvites.value = emptySet() // accepted: the invite closes before the partner's links land
        assertEquals(EditInvite.Invited, e.vm.state.value.invite)
        e.vm.invite()
        assertEquals(1, e.links.calls.count { it is FakeLinkRepository.Call.Invite })
        e.partner.set(PartnerState("Alex", emptyList(), null, null, links = listOf(PublishedLink("a1", LinkMode.RING))))
        assertEquals(EditInvite.Joined, e.vm.state.value.invite)
    }

    @Test fun theInviteClosingBeforeAppliedLandsDoesNotReleaseTheHold() = runTest {
        val gate = kotlinx.coroutines.flow.MutableSharedFlow<Pair<CommandStatus, String?>>()
        var started = 0
        val e = Env("a1", listOf(existing), linkOverride = { fake ->
            object : LinkRepository by fake { override fun invite(alarmId: String) = gate.also { started++ } }
        })
        paired(e)
        e.vm.invite()
        gate.emit(CommandStatus.PENDING to null)
        e.links.openInvites.value = setOf("a1")
        e.links.openInvites.value = emptySet() // accepted: the invite closes before APPLIED arrives
        assertEquals(EditInvite.Invited, e.vm.state.value.invite)
        gate.emit(CommandStatus.APPLIED to null)
        assertEquals(EditInvite.Invited, e.vm.state.value.invite)
        e.vm.invite()
        assertEquals(1, started)
        e.partner.set(PartnerState("Alex", emptyList(), null, null, links = listOf(PublishedLink("a1", LinkMode.RING))))
        assertEquals(EditInvite.Joined, e.vm.state.value.invite)
    }

    @Test fun anInviteThatEndsWithoutAppliedReturnsToInvite() = runTest {
        val e = Env("a1", listOf(existing), linkOverride = { fake ->
            object : LinkRepository by fake { override fun invite(alarmId: String) = flowOf(CommandStatus.PENDING to null) }
        })
        paired(e)
        e.vm.invite()
        assertEquals(EditInvite.Invite, e.vm.state.value.invite)
    }

    @Test fun invitedAfterAppliedBecomesJoinedWhenThePartnerJoins() = runTest {
        val e = edit()
        paired(e)
        e.vm.invite()
        e.partner.set(PartnerState("Alex", emptyList(), null, null, links = listOf(PublishedLink("a1", LinkMode.RING))))
        assertEquals(EditInvite.Joined, e.vm.state.value.invite)
    }

    @Test fun aSecondTapWhileTheInviteIsInFlightStartsNothing() = runTest {
        val gate = kotlinx.coroutines.flow.MutableSharedFlow<Pair<CommandStatus, String?>>()
        var started = 0
        val e = Env("a1", listOf(existing), linkOverride = { fake ->
            object : LinkRepository by fake { override fun invite(alarmId: String) = gate.also { started++ } }
        })
        paired(e)
        e.vm.invite()
        e.vm.invite()
        assertEquals(1, started)
    }

    @Test fun invitedWhileTheAlarmHasAnOpenInvite() = runTest {
        val e = edit()
        paired(e)
        e.links.openInvites.value = setOf("a1")
        assertEquals(EditInvite.Invited, e.vm.state.value.invite)
        e.links.openInvites.value = setOf("other")
        assertEquals(EditInvite.Invite, e.vm.state.value.invite)
    }

    @Test fun invitedWhileThisScreensInviteIsPending() = runTest {
        val gate = kotlinx.coroutines.flow.MutableSharedFlow<Pair<CommandStatus, String?>>()
        val e = Env("a1", listOf(existing), linkOverride = { fake ->
            object : LinkRepository by fake { override fun invite(alarmId: String) = gate }
        })
        paired(e)
        e.vm.invite()
        gate.emit(CommandStatus.PENDING to null)
        assertEquals(EditInvite.Invited, e.vm.state.value.invite)
        gate.emit(CommandStatus.APPLIED to null)
        assertEquals(EditInvite.Invited, e.vm.state.value.invite)
    }

    @Test fun joinedTakesPrecedence() = runTest {
        val e = edit()
        paired(e)
        e.links.openInvites.value = setOf("a1")
        e.partner.set(PartnerState("Alex", emptyList(), null, null, links = listOf(PublishedLink("a1", LinkMode.RING))))
        assertEquals(EditInvite.Joined, e.vm.state.value.invite)
    }

    @Test fun rejectedInviteGoesBackToInviteWithItsReason() = runTest {
        val e = Env("a1", listOf(existing), linkOverride = { fake ->
            object : LinkRepository by fake {
                override fun invite(alarmId: String) =
                    flowOf(CommandStatus.PENDING to null, CommandStatus.REJECTED to "Alex said no")
            }
        })
        paired(e)
        e.vm.invite()
        assertEquals(EditInvite.Invite, e.vm.state.value.invite)
        assertEquals(CommandUiStatus.Rejected("Alex said no"), e.vm.state.value.inviteStatus)
    }

    @Test fun rejectedWithoutAReasonUsesTheFallbackAndAResendClearsIt() = runTest {
        var reject = true
        val e = Env("a1", listOf(existing), linkOverride = { fake ->
            object : LinkRepository by fake {
                override fun invite(alarmId: String) = flowOf(
                    if (reject) CommandStatus.REJECTED to null else CommandStatus.APPLIED to null,
                )
            }
        })
        paired(e)
        e.vm.invite()
        assertEquals(CommandUiStatus.Rejected("invite failed"), e.vm.state.value.inviteStatus)
        reject = false
        e.vm.invite()
        assertNull(e.vm.state.value.inviteStatus)
    }

    @Test fun editorClosesForAJoinedRow() = runTest {
        val linked = existing.copy(
            link = AlarmLink("uid", "o1", LinkMode.RING, null, true, null, null, null, null),
        )
        val e = Env("a1", listOf(linked))
        assertTrue(e.vm.state.value.closed)
        paired(e)
        assertEquals(EditInvite.Hidden, e.vm.state.value.invite)
    }
}
