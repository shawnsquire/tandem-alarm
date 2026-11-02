package me.shawnsquire.tandem.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.schedule.MAX_ZOOM
import me.shawnsquire.tandem.schedule.WallpaperFrame
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
        val ds = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "test.preferences_pb") },
        )
        val r = SettingsRepositoryImpl(ds)
        val key = intPreferencesKey("snoozeMinutes")
        r.setSnoozeMinutes(0)
        assertEquals(1, ds.data.first()[key])
        r.setSnoozeMinutes(61)
        assertEquals(60, ds.data.first()[key])
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

    @Test
    fun snoozeClampedOnRead() = runTest {
        val ds = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "test.preferences_pb") },
        )
        val r = SettingsRepositoryImpl(ds)
        val key = intPreferencesKey("snoozeMinutes")
        ds.edit { it[key] = 0 }
        assertEquals(1, r.snoozeMinutes.first())
        ds.edit { it[key] = 99 }
        assertEquals(60, r.snoozeMinutes.first())
        ds.edit { it[key] = -5 }
        assertEquals(1, r.snoozeMinutes.first())
    }

    @Test
    fun wallpaperFrameSetAndCleared() = runTest {
        val r = repo()
        assertNull(r.defaultWallpaperFrame.first())
        r.setDefaultWallpaperFrame(WallpaperFrame(2f, 0.25f, 0.75f))
        assertEquals(WallpaperFrame(2f, 0.25f, 0.75f), r.defaultWallpaperFrame.first())
        r.setDefaultWallpaperFrame(null)
        assertNull(r.defaultWallpaperFrame.first())
    }

    @Test
    fun wallpaperAndFrameAreWrittenTogether() = runTest {
        val r = repo()
        r.setDefaultWallpaper("/a.jpg", WallpaperFrame(2f, 0.25f, 0.75f))
        assertEquals("/a.jpg", r.defaultWallpaperPath.first())
        assertEquals(WallpaperFrame(2f, 0.25f, 0.75f), r.defaultWallpaperFrame.first())
        r.setDefaultWallpaper("/b.jpg", null)
        assertEquals("/b.jpg", r.defaultWallpaperPath.first())
        assertNull(r.defaultWallpaperFrame.first())
        r.setDefaultWallpaper(null, null)
        assertNull(r.defaultWallpaperPath.first())
    }

    @Test
    fun wallpaperFrameIsSanitizedOnReadAndNullUnlessAllPartsAreStored() = runTest {
        val ds = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "test.preferences_pb") },
        )
        val r = SettingsRepositoryImpl(ds)
        val zoom = floatPreferencesKey("defaultWallpaperZoom")
        val x = floatPreferencesKey("defaultWallpaperFocusX")
        val y = floatPreferencesKey("defaultWallpaperFocusY")
        ds.edit { it[zoom] = 9f; it[x] = 2f }
        assertNull(r.defaultWallpaperFrame.first())
        ds.edit { it[y] = Float.NaN }
        assertEquals(WallpaperFrame(MAX_ZOOM, 1f, 0.5f), r.defaultWallpaperFrame.first())
    }
}
