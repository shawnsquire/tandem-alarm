package me.shawnsquire.tandem.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepositoryImpl(private val dataStore: DataStore<Preferences>) : SettingsRepository {
    override val snoozeMinutes: Flow<Int> =
        dataStore.data.map { (it[SNOOZE_MINUTES] ?: DEFAULT_SNOOZE_MINUTES).coerceIn(SNOOZE_RANGE) }

    override val defaultWallpaperPath: Flow<String?> = dataStore.data.map { it[WALLPAPER_PATH] }

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

    private companion object {
        const val DEFAULT_SNOOZE_MINUTES = 10
        val SNOOZE_RANGE = 1..60
        val SNOOZE_MINUTES = intPreferencesKey("snoozeMinutes")
        val WALLPAPER_PATH = stringPreferencesKey("defaultWallpaperPath")
        val RINGTONE_URI = stringPreferencesKey("defaultRingtoneUri")
    }
}
