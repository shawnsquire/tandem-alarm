package me.shawnsquire.tandem.data

import kotlinx.coroutines.flow.Flow

/** Local user settings. */
interface SettingsRepository {
    val snoozeMinutes: Flow<Int>
    val defaultWallpaperPath: Flow<String?>
    val defaultRingtoneUri: Flow<String?>
    suspend fun setSnoozeMinutes(m: Int)
    suspend fun setDefaultWallpaperPath(p: String?)
    suspend fun setDefaultRingtoneUri(u: String?)
}
