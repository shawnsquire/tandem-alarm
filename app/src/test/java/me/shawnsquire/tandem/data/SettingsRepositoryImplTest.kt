package me.shawnsquire.tandem.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryImplTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.repo() = SettingsRepositoryImpl(
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "test.preferences_pb") },
        ),
    )

    @Test
    fun snoozeDefaultsToTen() = runTest {
        assertEquals(10, repo().snoozeMinutes.first())
    }

    @Test
    fun snoozeRoundTrips() = runTest {
        val r = repo()
        r.setSnoozeMinutes(15)
        assertEquals(15, r.snoozeMinutes.first())
    }

    @Test
    fun snoozeClampedOnWrite() = runTest {
        val r = repo()
        r.setSnoozeMinutes(0)
        assertEquals(1, r.snoozeMinutes.first())
        r.setSnoozeMinutes(61)
        assertEquals(60, r.snoozeMinutes.first())
    }

    @Test
    fun snoozeBoundsKept() = runTest {
        val r = repo()
        r.setSnoozeMinutes(1)
        assertEquals(1, r.snoozeMinutes.first())
        r.setSnoozeMinutes(60)
        assertEquals(60, r.snoozeMinutes.first())
    }

    @Test
    fun wallpaperSetAndCleared() = runTest {
        val r = repo()
        assertNull(r.defaultWallpaperPath.first())
        r.setDefaultWallpaperPath("/files/wall.jpg")
        assertEquals("/files/wall.jpg", r.defaultWallpaperPath.first())
        r.setDefaultWallpaperPath(null)
        assertNull(r.defaultWallpaperPath.first())
    }

    @Test
    fun ringtoneSetAndCleared() = runTest {
        val r = repo()
        assertNull(r.defaultRingtoneUri.first())
        r.setDefaultRingtoneUri("content://media/1")
        assertEquals("content://media/1", r.defaultRingtoneUri.first())
        r.setDefaultRingtoneUri(null)
        assertNull(r.defaultRingtoneUri.first())
    }
}
