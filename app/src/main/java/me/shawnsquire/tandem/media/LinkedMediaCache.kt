package me.shawnsquire.tandem.media

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.WallpaperFrame

/** A wallpaper file and how it is framed on the ringing screen ([frame] null: centred, the plain crop). */
data class RingWallpaper(val path: String, val frame: WallpaperFrame?)

/** Drops blank paths and repeats of a path (the first wins), keeping the order. */
internal fun List<RingWallpaper>.distinctWallpapers(): List<RingWallpaper> =
    filter { it.path.isNotBlank() }.distinctBy { it.path }

/** What a linked ring shows and plays. [soundCandidates] are in resolution order and already include the system alarm sound, so the ring passes them through without the adapter appending its defaults. [wallpapers] are in resolution order, each with its own frame. */
data class RingMedia(val label: String, val soundCandidates: List<String>, val wallpapers: List<RingWallpaper>)

/**
 * Joiner-side cache of the owner's media under filesDir/linked. Real implementation: L5.
 *
 * It must persist the refs it receives in [update] (to storage a fresh process can read), because a
 * fire can run in a fresh process, and before unlock, with no LinkSync pass beforehand.
 */
interface LinkedMediaCache {
    /**
     * Must persist [alarms] and [defaultWallpaper] before returning, not just hold them in memory:
     * [resolve] and [pathFor] may run in a process that never saw this call. LinkSync, each pass: the owner's current refs for linked alarms (keyed by ownerAlarmId) and default wallpaper; enqueues downloads of uncached hashes (network-constrained WorkManager).
     * [defaultWallpaperFrame] frames [defaultWallpaper] (null: centred); it is persisted with the refs but is not a media ref, so it adds no download. */
    fun update(ownerUid: String, alarms: Map<String, AlarmMedia>, defaultWallpaper: MediaRef?, defaultWallpaperFrame: WallpaperFrame? = null)

    /** The frame of [ownerUid]'s default wallpaper as last passed to [update]; null (centred) when none, another owner's, or unreadable. Never throws. */

    fun defaultWallpaperFrame(ownerUid: String): WallpaperFrame?

    /** Cached file path (filesDir/linked/{sha}) for [sha], or null. */
    fun pathFor(sha: String): String?

    /** Hashes referenced by a linked row or pending download; MediaOrphanSweeper keeps these. */
    fun inUse(): Set<String>

    /** Design Requirements 6: joiner override → owner per-alarm media → owner default wallpaper / system alarm sound → joiner defaults. Label: override, else owner's label. An unreadable override falls to the next step; a missing file never blocks a ring (I1). */
    suspend fun resolve(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: RingWallpaper?): RingMedia
}
