package me.shawnsquire.tandem.data

import kotlinx.coroutines.flow.Flow
import me.shawnsquire.tandem.schedule.WallpaperFrame

/** Local user settings. */
interface SettingsRepository {
    val snoozeMinutes: Flow<Int>
    val defaultWallpaperPath: Flow<String?>
    val defaultRingtoneUri: Flow<String?>
    /** Frames [defaultWallpaperPath]; null means centred. */
    val defaultWallpaperFrame: Flow<WallpaperFrame?>
    suspend fun setSnoozeMinutes(m: Int)
    suspend fun setDefaultWallpaperPath(p: String?)
    suspend fun setDefaultRingtoneUri(u: String?)
    suspend fun setDefaultWallpaperFrame(f: WallpaperFrame?)

    /** Sets the default wallpaper and its frame in one write, so a failure never pairs one image with another's frame. */
    suspend fun setDefaultWallpaper(path: String?, frame: WallpaperFrame?)
}
