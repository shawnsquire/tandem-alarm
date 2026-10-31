package me.shawnsquire.tandem.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.fakes.FakePairingRepository
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
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
        val vm = SettingsViewModel(
            settings = settings,
            pairing = pairing,
            displayName = name,
            setDisplayName = { savedNames += it },
            ringtoneTitle = { "title:${it ?: "default"}" },
            importRingtone = {
                importedRingtones += it
                if (failImport) error("boom")
                "file:///imported-${importedRingtones.size}"
            },
            deleteRingtoneFile = { events += "del-ring:$it" },
            importWallpaper = {
                importedWallpapers += it
                if (failImport) error("boom")
                "/wall-${importedWallpapers.size}"
            },
            deleteWallpaperFile = { events += "del-wall:$it" },
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
}
