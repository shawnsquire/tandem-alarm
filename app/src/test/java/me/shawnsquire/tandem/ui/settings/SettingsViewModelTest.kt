package me.shawnsquire.tandem.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import me.shawnsquire.tandem.backup.BackupRejected
import me.shawnsquire.tandem.backup.ExportResult
import me.shawnsquire.tandem.backup.ImportResult
import me.shawnsquire.tandem.backup.LostSound
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.fakes.FakePairingRepository
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.schedule.WallpaperFrame
import me.shawnsquire.tandem.sync.PairingException
import me.shawnsquire.tandem.sync.PairingRepository
import me.shawnsquire.tandem.sync.PairingStatus
import me.shawnsquire.tandem.ui.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    /** Records events in order so "delete after persist" can be asserted. */
    private val events = mutableListOf<String>()

    private inner class RecordingSettings(private val inner: FakeSettingsRepository = FakeSettingsRepository()) :
        SettingsRepository by inner {
        override suspend fun setDefaultRingtoneUri(u: String?) {
            events += "set-ring:$u"; inner.setDefaultRingtoneUri(u)
        }
        override suspend fun setDefaultWallpaperPath(p: String?) {
            events += "set-wall:$p"; inner.setDefaultWallpaperPath(p)
        }
        override suspend fun setDefaultWallpaper(path: String?, frame: WallpaperFrame?) {
            events += "set-wall:$path"; inner.setDefaultWallpaper(path, frame)
        }
    }

    private class ThrowingPairing(private val error: Exception) : PairingRepository by FakePairingRepository() {
        override suspend fun unpair() = throw error
    }

    private inner class Env(
        val settings: SettingsRepository = RecordingSettings(),
        val pairing: PairingRepository = FakePairingRepository(),
    ) {
        val name = MutableStateFlow("Me")
        val savedNames = mutableListOf<String?>()
        val importedRingtones = mutableListOf<String>()
        val importedWallpapers = mutableListOf<String>()
        var failImport = false
        /** What a ringtone import returns once called. */
        var importRingtone: suspend (String) -> String = { "file:///imported-${importedRingtones.size}" }
        /** What the content comparison says; a throw counts as different. */
        var sameContent: () -> Boolean = { false }
        /** The document URIs the export was asked to write, in order. */
        val exportedTo = mutableListOf<String>()
        /** What an export does once called; the default exports three alarms. */
        var exportBackup: suspend (String) -> ExportResult = { ExportResult(3, 0) }
        /** The document URIs the import was asked to read, in order. */
        val readFrom = mutableListOf<String>()
        var applied = 0
        var discarded = 0
        /** What an apply does once called. */
        var applyBackup: suspend () -> ImportResult = { ImportResult(2, 0) }
        /** What a read does once called; the default finds two alarms. */
        var readBackup: suspend (String) -> PendingImport = {
            PendingImport(2, { applied++; applyBackup() }, { discarded++ })
        }
        val vm = SettingsViewModel(
            settings = settings,
            pairing = pairing,
            displayName = name,
            setDisplayName = { savedNames += it },
            ringtoneTitle = { "title:${it ?: "default"}" },
            importRingtone = {
                importedRingtones += it
                if (failImport) error("boom")
                importRingtone(it)
            },
            deleteRingtoneFile = { events += "del-ring:$it" },
            importWallpaper = {
                importedWallpapers += it
                if (failImport) error("boom")
                "/wall-${importedWallpapers.size}"
            },
            deleteWallpaperFile = { events += "del-wall:$it" },
            sameWallpaperContent = { _, _ -> sameContent() },
            exportBackup = { exportedTo += it; exportBackup(it) },
            readBackup = { readFrom += it; readBackup(it) },
        )
    }

    private fun kotlinx.coroutines.test.TestScope.collect(env: Env) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { env.vm.state.collect {} }
    }

    @Test fun setSnoozePersistsAndRejectsOthers() = runTest {
        val env = Env(); collect(env)
        env.vm.setSnooze(15)
        assertEquals(15, env.settings.snoozeMinutes.first())
        assertEquals(15, env.vm.state.value.snoozeMinutes)
        env.vm.setSnooze(7)
        env.vm.setSnooze(0)
        assertEquals(15, env.settings.snoozeMinutes.first())
        assertEquals(listOf(5, 10, 15, 20), SNOOZE_CHOICES)
    }

    @Test fun ringtoneFilePickImportsStoresReturnedUriAndDeletesOldAfterPersist() = runTest {
        val env = Env(); collect(env)
        env.settings.setDefaultRingtoneUri("file:///old")
        events.clear()
        env.vm.onRingtoneFilePicked("content://doc/1")
        assertEquals(listOf("content://doc/1"), env.importedRingtones)
        assertEquals("file:///imported-1", env.settings.defaultRingtoneUri.first())
        assertEquals(listOf("set-ring:file:///imported-1", "del-ring:file:///old"), events)
        assertEquals("title:file:///imported-1", env.vm.state.value.ringtoneTitle)
    }

    @Test fun ringtoneImportFailureKeepsOldAndShowsError() = runTest {
        val env = Env(); collect(env)
        env.settings.setDefaultRingtoneUri("file:///old")
        events.clear()
        env.failImport = true
        env.vm.onRingtoneFilePicked("content://doc/1")
        assertEquals("file:///old", env.settings.defaultRingtoneUri.first())
        assertEquals(SettingsError.RingtoneImport, env.vm.state.value.error)
        assertTrue(events.isEmpty())
        env.vm.dismissError()
        assertNull(env.vm.state.value.error)
    }

    @Test fun systemRingtoneStoredAsReturnedAndOldFileDeleted() = runTest {
        val env = Env(); collect(env)
        env.settings.setDefaultRingtoneUri("file:///old")
        events.clear()
        env.vm.onSystemRingtonePicked("content://media/internal/audio/media/5")
        assertEquals("content://media/internal/audio/media/5", env.settings.defaultRingtoneUri.first())
        assertTrue(env.importedRingtones.isEmpty())
        assertEquals(listOf("set-ring:content://media/internal/audio/media/5", "del-ring:file:///old"), events)
    }

    @Test fun resetRingtoneClearsAndDeletesOld() = runTest {
        val env = Env(); collect(env)
        env.settings.setDefaultRingtoneUri("file:///old")
        events.clear()
        env.vm.resetRingtone()
        assertNull(env.settings.defaultRingtoneUri.first())
        assertEquals(listOf("set-ring:null", "del-ring:file:///old"), events)
        assertEquals("title:default", env.vm.state.value.ringtoneTitle)
    }

    @Test fun wallpaperPickPersistsAndDeletesOld() = runTest {
        val env = Env(); collect(env)
        env.vm.onWallpaperPicked("content://photo/1")
        assertEquals("/wall-1", env.settings.defaultWallpaperPath.first())
        assertEquals(listOf("set-wall:/wall-1"), events)
        env.vm.onWallpaperPicked("content://photo/2")
        assertEquals("/wall-2", env.settings.defaultWallpaperPath.first())
        assertEquals(listOf("set-wall:/wall-1", "set-wall:/wall-2", "del-wall:/wall-1"), events)
    }

    @Test fun wallpaperRemovePersistsNullAndDeletesOld() = runTest {
        val env = Env(); collect(env)
        env.settings.setDefaultWallpaperPath("/old")
        events.clear()
        env.vm.removeWallpaper()
        assertNull(env.settings.defaultWallpaperPath.first())
        assertEquals(listOf("set-wall:null", "del-wall:/old"), events)
    }

    @Test fun wallpaperImportFailureShowsError() = runTest {
        val env = Env(); collect(env)
        env.failImport = true
        env.vm.onWallpaperPicked("content://photo/1")
        assertEquals(SettingsError.WallpaperImport, env.vm.state.value.error)
        assertNull(env.settings.defaultWallpaperPath.first())
    }

    @Test fun displayNameIsTrimmedAndBlankResetsToDefault() = runTest {
        val env = Env(); collect(env)
        env.vm.saveDisplayName("  Sam  ")
        env.vm.saveDisplayName("   ")
        env.vm.saveDisplayName("")
        assertEquals(listOf("Sam", null, null), env.savedNames)
    }

    @Test fun pairingStateReflectsStatus() = runTest {
        val pairing = FakePairingRepository()
        val env = Env(pairing = pairing); collect(env)
        assertEquals(false, env.vm.state.value.paired)
        pairing.join("CODE")
        assertEquals(true, env.vm.state.value.paired)
        assertEquals("Alex", env.vm.state.value.partnerName)
    }

    @Test fun unpairCallsRepository() = runTest {
        val pairing = FakePairingRepository()
        pairing.join("CODE")
        val env = Env(pairing = pairing); collect(env)
        env.vm.unpair()
        assertEquals(PairingStatus.Unpaired, pairing.status.first())
        assertEquals(false, env.vm.state.value.paired)
        assertEquals(false, env.vm.state.value.unpairing)
        assertNull(env.vm.state.value.error)
    }

    @Test fun unpairErrorShownFromPairingException() = runTest {
        val env = Env(pairing = ThrowingPairing(PairingException("Offline, try later"))); collect(env)
        env.vm.unpair()
        assertEquals(SettingsError.Unpair("Offline, try later"), env.vm.state.value.error)
        assertEquals(false, env.vm.state.value.unpairing)
    }

    @Test fun unpairUnexpectedErrorIsGeneric() = runTest {
        val env = Env(pairing = ThrowingPairing(IllegalStateException("secret internals"))); collect(env)
        env.vm.unpair()
        assertEquals(SettingsError.Unpair("Something went wrong. Try again."), env.vm.state.value.error)
    }

    private val framed = WallpaperFrame(2f, 0.3f, 0.6f)

    @Test fun savingTheFrameStoresItForTheDefaultImage() = runTest {
        val env = Env(); collect(env)
        env.settings.setDefaultWallpaperPath("/old")
        env.vm.setWallpaperFrame(framed)
        assertEquals(framed, env.settings.defaultWallpaperFrame.first())
        assertEquals(framed, env.vm.state.value.wallpaperFrame)
    }

    @Test fun savingTheFrameWithoutAnImageStoresNothing() = runTest {
        val env = Env(); collect(env)
        env.vm.setWallpaperFrame(framed)
        assertNull(env.settings.defaultWallpaperFrame.first())
    }

    @Test fun repickingTheSameImageKeepsTheFrameAndOpensThePreview() = runTest {
        val env = Env(); collect(env)
        env.settings.setDefaultWallpaperPath("/old")
        env.settings.setDefaultWallpaperFrame(framed)
        env.sameContent = { true }
        env.vm.onWallpaperPicked("content://photo/1")
        assertEquals("/wall-1", env.settings.defaultWallpaperPath.first())
        assertEquals(framed, env.settings.defaultWallpaperFrame.first())
        assertTrue(env.vm.state.value.previewRequested)
        env.vm.previewRequestHandled()
        assertEquals(false, env.vm.state.value.previewRequested)
    }

    @Test fun repickingADifferentImageResetsTheFrame() = runTest {
        val env = Env(); collect(env)
        env.settings.setDefaultWallpaperPath("/old")
        env.settings.setDefaultWallpaperFrame(framed)
        env.vm.onWallpaperPicked("content://photo/1")
        assertNull(env.settings.defaultWallpaperFrame.first())
    }

    @Test fun aFailedComparisonResetsTheFrame() = runTest {
        val env = Env(); collect(env)
        env.settings.setDefaultWallpaperPath("/old")
        env.settings.setDefaultWallpaperFrame(framed)
        env.sameContent = { error("unreadable") }
        env.vm.onWallpaperPicked("content://photo/1")
        assertEquals("/wall-1", env.settings.defaultWallpaperPath.first())
        assertNull(env.settings.defaultWallpaperFrame.first())
    }

    @Test fun removingTheImageClearsTheFrame() = runTest {
        val env = Env(); collect(env)
        env.settings.setDefaultWallpaperPath("/old")
        env.settings.setDefaultWallpaperFrame(framed)
        env.vm.removeWallpaper()
        assertNull(env.settings.defaultWallpaperPath.first())
        assertNull(env.settings.defaultWallpaperFrame.first())
    }

    @Test fun exportPassesUriThroughAndReportsCount() = runTest {
        val env = Env(); collect(env)
        env.exportBackup = { ExportResult(2, 0) }
        env.vm.onExportDocumentCreated("content://doc/backup")
        assertEquals(listOf("content://doc/backup"), env.exportedTo)
        assertEquals(SettingsNotice.Exported(2, 0), env.vm.state.value.notice)
        assertNull(env.vm.state.value.error)
        assertEquals(false, env.vm.state.value.busy)
    }

    @Test fun exportFailureShowsErrorAndNoNotice() = runTest {
        val env = Env(); collect(env)
        env.exportBackup = { error("disk full") }
        env.vm.onExportDocumentCreated("content://doc/backup")
        assertEquals(SettingsError.Export, env.vm.state.value.error)
        assertNull(env.vm.state.value.notice)
        assertEquals(false, env.vm.state.value.busy)
    }

    @Test fun exportWhileOneIsRunningIsIgnored() = runTest {
        val env = Env(); collect(env)
        val gate = CompletableDeferred<ExportResult>()
        env.exportBackup = { gate.await() }
        env.vm.onExportDocumentCreated("content://doc/1")
        assertTrue(env.vm.state.value.busy)
        env.vm.onExportDocumentCreated("content://doc/2")
        assertEquals(listOf("content://doc/1"), env.exportedTo)
        gate.complete(ExportResult(1, 0))
        assertEquals(SettingsNotice.Exported(1, 0), env.vm.state.value.notice)
        assertEquals(false, env.vm.state.value.busy)
    }

    @Test fun exportReportsMissingMedia() = runTest {
        val env = Env(); collect(env)
        env.exportBackup = { ExportResult(2, 4) }
        env.vm.onExportDocumentCreated("content://doc/backup")
        assertEquals(SettingsNotice.Exported(2, 4), env.vm.state.value.notice)
    }

    @Test fun exportIsIgnoredWhileARingtoneImportRuns() = runTest {
        val env = Env(); collect(env)
        val gate = CompletableDeferred<String>()
        env.importRingtone = { gate.await() }
        env.vm.onRingtoneFilePicked("content://doc/1")
        assertTrue(env.vm.state.value.busy)
        env.vm.onExportDocumentCreated("content://doc/backup")
        assertTrue(env.exportedTo.isEmpty())
        gate.complete("file:///imported")
    }

    @Test fun ringtoneImportIsIgnoredWhileAnExportRuns() = runTest {
        val env = Env(); collect(env)
        val gate = CompletableDeferred<ExportResult>()
        env.exportBackup = { gate.await() }
        env.vm.onExportDocumentCreated("content://doc/backup")
        assertTrue(env.vm.state.value.busy)
        env.vm.onRingtoneFilePicked("content://doc/1")
        assertTrue(env.importedRingtones.isEmpty())
        gate.complete(ExportResult(1, 0))
    }

    @Test fun noticeShownClearsTheNotice() = runTest {
        val env = Env(); collect(env)
        env.vm.onExportDocumentCreated("content://doc/1")
        assertEquals(SettingsNotice.Exported(3, 0), env.vm.state.value.notice)
        env.vm.noticeShown()
        assertNull(env.vm.state.value.notice)
    }

    @Test fun aReadableBackupOpensTheConfirmWithItsAlarmCount() = runTest {
        val env = Env(); collect(env)
        env.vm.onImportDocumentPicked("content://doc/backup")
        assertEquals(listOf("content://doc/backup"), env.readFrom)
        assertEquals(2, env.vm.state.value.importConfirm)
        assertEquals(false, env.vm.state.value.busy)
        assertNull(env.vm.state.value.error)
        assertEquals(0, env.applied)
    }

    @Test fun eachRejectionReasonShowsItsError() = runTest {
        for (reason in BackupRejected.Reason.values()) {
            val env = Env(); collect(env)
            env.readBackup = { throw BackupRejected(reason) }
            env.vm.onImportDocumentPicked("content://doc/backup")
            assertEquals(SettingsError.Import(reason), env.vm.state.value.error)
            assertNull(env.vm.state.value.importConfirm)
            assertEquals(false, env.vm.state.value.busy)
        }
    }

    @Test fun anyOtherReadFailureIsGeneric() = runTest {
        val env = Env(); collect(env)
        env.readBackup = { error("cannot open") }
        env.vm.onImportDocumentPicked("content://doc/backup")
        assertEquals(SettingsError.Import(null), env.vm.state.value.error)
        assertNull(env.vm.state.value.importConfirm)
        assertEquals(false, env.vm.state.value.busy)
    }

    @Test fun confirmingAppliesOnceAndReportsTheResult() = runTest {
        val env = Env(); collect(env)
        env.applyBackup = { ImportResult(2, 1) }
        env.vm.onImportDocumentPicked("content://doc/backup")
        env.vm.confirmImport()
        env.vm.confirmImport()
        assertEquals(1, env.applied)
        assertEquals(SettingsNotice.Imported(2, 1), env.vm.state.value.notice)
        assertNull(env.vm.state.value.importConfirm)
        assertEquals(false, env.vm.state.value.busy)
    }

    @Test fun lostSoundsOpenTheRepickDialogAndRepickShownClearsIt() = runTest {
        val env = Env(); collect(env)
        val lost = listOf(LostSound("Work", 6, 30), LostSound("", 8, 15))
        env.applyBackup = { ImportResult(3, 2, lost, defaultSoundLost = true) }
        env.vm.onImportDocumentPicked("content://doc/backup")
        env.vm.confirmImport()
        assertEquals(SoundsToRepick(lost, true), env.vm.state.value.soundsToRepick)
        assertEquals(SettingsNotice.Imported(3, 2, lost, true), env.vm.state.value.notice)
        env.vm.repickShown()
        assertNull(env.vm.state.value.soundsToRepick)
    }

    @Test fun onlyALostDefaultSoundAlsoOpensTheDialog() = runTest {
        val env = Env(); collect(env)
        env.applyBackup = { ImportResult(0, 1, emptyList(), defaultSoundLost = true) }
        env.vm.onImportDocumentPicked("content://doc/backup")
        env.vm.confirmImport()
        assertEquals(SoundsToRepick(emptyList(), true), env.vm.state.value.soundsToRepick)
    }

    @Test fun anImportWithNoLostSoundsDoesNotOpenTheDialog() = runTest {
        val env = Env(); collect(env)
        env.applyBackup = { ImportResult(2, 1) }
        env.vm.onImportDocumentPicked("content://doc/backup")
        env.vm.confirmImport()
        assertNull(env.vm.state.value.soundsToRepick)
    }

    @Test fun anApplyFailureShowsTheApplyError() = runTest {
        val env = Env(); collect(env)
        env.applyBackup = { error("disk full") }
        env.vm.onImportDocumentPicked("content://doc/backup")
        env.vm.confirmImport()
        assertEquals(SettingsError.ImportApply, env.vm.state.value.error)
        assertNull(env.vm.state.value.notice)
        assertNull(env.vm.state.value.importConfirm)
        assertEquals(false, env.vm.state.value.busy)
    }

    @Test fun cancellingDiscardsAndALaterConfirmDoesNothing() = runTest {
        val env = Env(); collect(env)
        env.vm.onImportDocumentPicked("content://doc/backup")
        env.vm.cancelImport()
        assertEquals(1, env.discarded)
        assertNull(env.vm.state.value.importConfirm)
        env.vm.confirmImport()
        assertEquals(0, env.applied)
        assertNull(env.vm.state.value.notice)
    }

    @Test fun aPickIsIgnoredWhileAnExportRuns() = runTest {
        val env = Env(); collect(env)
        val gate = CompletableDeferred<ExportResult>()
        env.exportBackup = { gate.await() }
        env.vm.onExportDocumentCreated("content://doc/out")
        env.vm.onImportDocumentPicked("content://doc/backup")
        assertTrue(env.readFrom.isEmpty())
        gate.complete(ExportResult(1, 0))
    }

    @Test fun anExportIsIgnoredWhileAReadRuns() = runTest {
        val env = Env(); collect(env)
        val gate = CompletableDeferred<PendingImport>()
        env.readBackup = { gate.await() }
        env.vm.onImportDocumentPicked("content://doc/backup")
        assertTrue(env.vm.state.value.busy)
        env.vm.onExportDocumentCreated("content://doc/out")
        assertTrue(env.exportedTo.isEmpty())
        gate.complete(PendingImport(1, { ImportResult(1, 0) }, {}))
    }

    @Test fun clearingTheViewModelDiscardsAPendingImport() = runTest {
        val env = Env(); collect(env)
        env.vm.onImportDocumentPicked("content://doc/backup")
        val store = ViewModelStore()
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = env.vm as T
        })[SettingsViewModel::class.java]
        store.clear()
        assertEquals(1, env.discarded)
    }
}
