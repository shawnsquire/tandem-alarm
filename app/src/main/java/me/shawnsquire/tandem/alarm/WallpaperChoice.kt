package me.shawnsquire.tandem.alarm

/** Alarm wallpaper first, then the settings default, else null (plain gradient). Blank counts as absent. */
fun chooseWallpaperPaths(alarmPath: String?, defaultPath: String?): List<String> =
    listOfNotNull(alarmPath, defaultPath).filter { it.isNotBlank() }
