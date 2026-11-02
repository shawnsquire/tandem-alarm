package me.shawnsquire.tandem.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.shawnsquire.tandem.schedule.WallpaperFrame

/** Snooze length when none is stored or it can't be read. */
internal const val DEFAULT_SNOOZE_MINUTES = 10

/** Every snooze length read or written is coerced into this range. */
internal val SNOOZE_RANGE = 1..60

class SettingsRepositoryImpl(private val dataStore: DataStore<Preferences>) : SettingsRepository {
    override val snoozeMinutes: Flow<Int> =
        dataStore.data.map { (it[SNOOZE_MINUTES] ?: DEFAULT_SNOOZE_MINUTES).coerceIn(SNOOZE_RANGE) }

    override val defaultWallpaperPath: Flow<String?> = dataStore.data.map { it[WALLPAPER_PATH] }

    // Stored only when all three parts are present; read through sanitized().
    override val defaultWallpaperFrame: Flow<WallpaperFrame?> = dataStore.data.map {
        val zoom = it[FRAME_ZOOM]
        val x = it[FRAME_FOCUS_X]
        val y = it[FRAME_FOCUS_Y]
        if (zoom != null && x != null && y != null) WallpaperFrame(zoom, x, y).sanitized() else null
    }

    override val defaultRingtoneUri: Flow<String?> = dataStore.data.map { it[RINGTONE_URI] }

    override suspend fun setSnoozeMinutes(m: Int) {
        dataStore.edit { it[SNOOZE_MINUTES] = m.coerceIn(SNOOZE_RANGE) }
    }

    override suspend fun setDefaultWallpaperPath(p: String?) {
        dataStore.edit { if (p == null) it.remove(WALLPAPER_PATH) else it[WALLPAPER_PATH] = p }
    }

    override suspend fun setDefaultRingtoneUri(u: String?) {
        dataStore.edit { if (u == null) it.remove(RINGTONE_URI) else it[RINGTONE_URI] = u }
    }

    override suspend fun setDefaultWallpaperFrame(f: WallpaperFrame?) {
        dataStore.edit { it.putFrame(f) }
    }

    override suspend fun setDefaultWallpaper(path: String?, frame: WallpaperFrame?) {
        dataStore.edit {
            if (path == null) it.remove(WALLPAPER_PATH) else it[WALLPAPER_PATH] = path
            it.putFrame(frame)
        }
    }

    private fun MutablePreferences.putFrame(f: WallpaperFrame?) {
        if (f == null) {
            remove(FRAME_ZOOM)
            remove(FRAME_FOCUS_X)
            remove(FRAME_FOCUS_Y)
        } else {
            val s = f.sanitized()
            this[FRAME_ZOOM] = s.zoom
            this[FRAME_FOCUS_X] = s.focusX
            this[FRAME_FOCUS_Y] = s.focusY
        }
    }

    private companion object {
        val SNOOZE_MINUTES = intPreferencesKey("snoozeMinutes")
        val WALLPAPER_PATH = stringPreferencesKey("defaultWallpaperPath")
        val FRAME_ZOOM = floatPreferencesKey("defaultWallpaperZoom")
        val FRAME_FOCUS_X = floatPreferencesKey("defaultWallpaperFocusX")
        val FRAME_FOCUS_Y = floatPreferencesKey("defaultWallpaperFocusY")
        val RINGTONE_URI = stringPreferencesKey("defaultRingtoneUri")
    }
}
