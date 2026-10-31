package me.shawnsquire.tandem.media

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class MediaOrphanSweeperTest {
    @get:Rule val tmp = TemporaryFolder()

    // Whole seconds: some file systems keep mtime at that precision.
    private val now = System.currentTimeMillis() / 1000 * 1000
    private val old = now - 2 * HOUR
    private val filesDir by lazy { tmp.newFolder("files") }
    private val wallpapers by lazy { File(filesDir, "wallpapers").apply { mkdirs() } }
    private val ringtones by lazy { File(filesDir, "ringtones").apply { mkdirs() } }

    private fun file(dir: File, name: String, modified: Long = old) =
        File(dir, name).apply { writeText("x"); check(setLastModified(modified)) }

    private fun alarm(id: String, ringtone: String? = null, wallpaper: String? = null) =
        Alarm(id, id, 6, 30, emptySet(), true, null, ringtone, wallpaper)

    private fun sweep(
        alarms: AlarmRepository = FakeAlarmRepository(emptyList()),
        settings: SettingsRepository = FakeSettingsRepository(),
    ) = runBlocking { MediaOrphanSweeper(filesDir, alarms, settings) { now }.sweep() }

    @Test
    fun deletesOldUnreferencedFilesInBothDirectories() {
        val wallpaper = file(wallpapers, "orphan.jpg")
        val ringtone = file(ringtones, "orphan.mp3")

        assertEquals(2, sweep())

        assertFalse(wallpaper.exists())
        assertFalse(ringtone.exists())
    }

    @Test
    fun keepsEveryReferencedFile() = runBlocking {
        // All old, so only the reference keeps them.
        val alarmWallpaper = file(wallpapers, "alarm.jpg")
        val alarmToneUri = file(ringtones, "uuid-Wake up.mp3")
        val alarmTonePath = file(ringtones, "plain.mp3")
        val defaultWallpaper = file(wallpapers, "default.jpg")
        val defaultTone = file(ringtones, "default.mp3")
        val orphan = file(ringtones, "orphan.mp3")
        val alarms = FakeAlarmRepository(
            listOf(
                alarm("a", ringtone = Uri.fromFile(alarmToneUri).toString(), wallpaper = alarmWallpaper.absolutePath),
                alarm("b", ringtone = alarmTonePath.absolutePath),
                alarm("c", ringtone = "content://media/external/audio/1"),
            ),
        )
        val settings = FakeSettingsRepository().apply {
            setDefaultWallpaperPath(defaultWallpaper.absolutePath)
            setDefaultRingtoneUri(Uri.fromFile(defaultTone).toString())
        }

        assertEquals(1, sweep(alarms, settings))

        for (f in listOf(alarmWallpaper, alarmToneUri, alarmTonePath, defaultWallpaper, defaultTone)) assertTrue(f.name, f.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun aDefaultRingtoneStoredAsAPlainPathIsKept() = runBlocking {
        val tone = file(ringtones, "default.mp3")
        val settings = FakeSettingsRepository().apply { setDefaultRingtoneUri(tone.absolutePath) }

        assertEquals(0, sweep(settings = settings))

        assertTrue(tone.exists())
    }

    @Test
    fun keepsYoungUnreferencedFiles() {
        val young = file(wallpapers, "editing.jpg", modified = now - 10 * 60_000)
        val almostOld = file(ringtones, "almost.mp3", modified = now - HOUR + 60_000)

        assertEquals(0, sweep())

        assertTrue(young.exists())
        assertTrue(almostOld.exists())
    }

    @Test
    fun leavesOtherDirectoriesAndSubdirectoriesAlone() {
        val elsewhere = file(File(filesDir, "other").apply { mkdirs() }, "orphan.jpg")
        val topLevel = file(filesDir, "orphan.jpg")
        val nested = file(File(wallpapers, "sub").apply { mkdirs() }, "orphan.jpg")

        assertEquals(0, sweep())

        assertTrue(elsewhere.exists())
        assertTrue(topLevel.exists())
        assertTrue(nested.exists())
    }

    @Test
    fun missingDirectoriesAreFine() {
        assertEquals(0, sweep())
    }

    @Test
    fun anUnreadableAlarmListDeletesNothing() {
        val orphan = file(wallpapers, "orphan.jpg")
        val alarms = object : AlarmRepository by FakeAlarmRepository(emptyList()) {
            override val alarms: Flow<List<Alarm>> = flow { throw IllegalStateException("db") }
        }

        assertEquals(0, sweep(alarms = alarms))

        assertTrue(orphan.exists())
    }

    @Test
    fun unreadableSettingsDeleteNothing() {
        val orphan = file(ringtones, "orphan.mp3")
        val settings = object : SettingsRepository by FakeSettingsRepository() {
            override val defaultRingtoneUri: Flow<String?> = flow { throw IllegalStateException("datastore") }
        }

        assertEquals(0, sweep(settings = settings))

        assertTrue(orphan.exists())
    }

    @Test
    fun neverFollowsASymlinkOutOfTheDirectory() {
        val outside = file(tmp.newFolder("outside"), "precious.jpg")
        val link = File(wallpapers, "link.jpg")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertEquals(0, sweep())

        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(outside.exists())
    }

    @Test
    fun neverSweepsADirectoryThatLinksOutOfFilesDir() {
        val outside = tmp.newFolder("outside")
        val precious = file(outside, "precious.mp3")
        Files.createSymbolicLink(File(filesDir, "ringtones").toPath(), outside.toPath())

        assertEquals(0, sweep())

        assertTrue(precious.exists())
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
