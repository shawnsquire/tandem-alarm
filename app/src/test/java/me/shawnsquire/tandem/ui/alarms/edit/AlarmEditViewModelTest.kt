package me.shawnsquire.tandem.ui.alarms.edit

import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
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

    private class Env(id: String?, initial: List<Alarm>) {
        val repo = FakeAlarmRepository(initial)
        val scheduler = FakeAlarmScheduler()
        val titleCalls = mutableListOf<String?>()
        val importedRingtones = mutableListOf<String>()
        val importedWallpapers = mutableListOf<String>()
        val deletedRingtones = mutableListOf<String>()
        val deletedWallpapers = mutableListOf<String>()
        var failImport = false
        val vm = AlarmEditViewModel(
            alarmId = id,
            repository = repo,
            edits = AlarmEdits(repo, scheduler),
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
}
