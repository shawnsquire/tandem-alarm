package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.media.RingWallpaper
import me.shawnsquire.tandem.media.distinctWallpapers
import me.shawnsquire.tandem.schedule.WallpaperFrame

/** Alarm wallpaper (with its [alarmFrame]) first, then the settings [default] (with its own frame), else empty (plain gradient). Blank counts as absent. */
fun chooseWallpapers(alarmPath: String?, alarmFrame: WallpaperFrame?, default: RingWallpaper?): List<RingWallpaper> =
    listOfNotNull(alarmPath?.let { RingWallpaper(it, alarmFrame) }, default).distinctWallpapers()

/**
 * Power-of-two inSampleSize for an image of [w]x[h]: halve while both halved sides stay at or above
 * the screen size, then keep halving while the decoded ARGB_8888 size exceeds the budget of
 * 2 * [screenW] * [screenH] * 4 bytes (drawing bitmaps over 100 MB throws).
 */
internal fun sampleSizeFor(w: Int, h: Int, screenW: Int, screenH: Int): Int {
    var sample = 1
    while (w / (sample * 2) >= screenW && h / (sample * 2) >= screenH) sample *= 2
    val budget = 2L * screenW * screenH * 4
    while ((w.toLong() / sample) * (h.toLong() / sample) * 4 > budget) sample *= 2
    return sample
}
