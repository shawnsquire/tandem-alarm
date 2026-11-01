package me.shawnsquire.tandem.media

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.sync.AlarmMedia
import me.shawnsquire.tandem.sync.MediaRef

/** What a linked ring shows and plays. [soundCandidates] exclude the system/bundled fallbacks, which the sound adapter appends. */
data class RingMedia(val label: String, val soundCandidates: List<String>, val wallpaperPaths: List<String>)

/** Joiner-side cache of the owner's media under filesDir/linked. Real implementation: L5. */
interface LinkedMediaCache {
    /** LinkSync, each pass: the owner's current refs for linked alarms (keyed by ownerAlarmId) and default wallpaper; enqueues downloads of uncached hashes (network-constrained WorkManager). */
    fun update(ownerUid: String, alarms: Map<String, AlarmMedia>, defaultWallpaper: MediaRef?)

    /** Cached file path (filesDir/linked/{sha}) for [sha], or null. */
    fun pathFor(sha: String): String?

    /** Hashes referenced by a linked row or pending download; MediaOrphanSweeper keeps these. */
    fun inUse(): Set<String>

    /** Design Requirements 6: joiner override → owner per-alarm media → owner default wallpaper / system alarm sound → joiner defaults. Label: override, else owner's label. An unreadable override falls to the next step; a missing file never blocks a ring (I1). */
    suspend fun resolve(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: String?): RingMedia
}
