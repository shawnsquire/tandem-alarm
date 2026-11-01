package me.shawnsquire.tandem.media

import me.shawnsquire.tandem.schedule.Alarm

/** What a linked ring shows and plays. [soundCandidates] exclude the system/bundled fallbacks, which the sound adapter appends. */
data class RingMedia(val label: String, val soundCandidates: List<String>, val wallpaperPaths: List<String>)

/**
 * Joiner-side cache of the owner's media under filesDir/linked. Real implementation: L5.
 *
 * It must persist the refs it receives in [update] (to storage a fresh process can read), because a
 * fire can run in a fresh process, and before unlock, with no LinkSync pass beforehand.
 */
interface LinkedMediaCache {
    /**
     * Must persist [alarms] and [defaultWallpaper] before returning, not just hold them in memory:
     * [resolve] and [pathFor] may run in a process that never saw this call. LinkSync, each pass: the owner's current refs for linked alarms (keyed by ownerAlarmId) and default wallpaper; enqueues downloads of uncached hashes (network-constrained WorkManager). */
    fun update(ownerUid: String, alarms: Map<String, AlarmMedia>, defaultWallpaper: MediaRef?)

    /** Cached file path (filesDir/linked/{sha}) for [sha], or null. */
    fun pathFor(sha: String): String?

    /** Hashes referenced by a linked row or pending download; MediaOrphanSweeper keeps these. */
    fun inUse(): Set<String>

    /** Design Requirements 6: joiner override → owner per-alarm media → owner default wallpaper / system alarm sound → joiner defaults. Label: override, else owner's label. An unreadable override falls to the next step; a missing file never blocks a ring (I1). */
    suspend fun resolve(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: String?): RingMedia
}
