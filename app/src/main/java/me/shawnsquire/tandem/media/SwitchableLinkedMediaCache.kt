package me.shawnsquire.tandem.media

import me.shawnsquire.tandem.alarm.chooseWallpaperPaths
import me.shawnsquire.tandem.alarm.systemDefaultAlarmUri
import me.shawnsquire.tandem.schedule.Alarm

/**
 * Before the real cache is attached: nothing cached, so a linked row resolves from its own media.
 * Both this and [LinkedMediaCacheImpl]'s error fallback use [resolveFromRow].
 */
object DefaultLinkedMediaCache : LinkedMediaCache {
    override fun update(ownerUid: String, alarms: Map<String, AlarmMedia>, defaultWallpaper: MediaRef?) = Unit
    override fun pathFor(sha: String): String? = null
    override fun inUse(): Set<String> = emptySet()

    override suspend fun resolve(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: String?) =
        resolveFromRow(alarm, joinerDefaultRingtone, joinerDefaultWallpaper, systemDefaultAlarmUri())

    /**
     * Pure and never throws. An own alarm (no link) is its media then the joiner defaults, without the
     * system sound (the sound adapter appends that). A linked row follows the design's order with
     * nothing cached: sound the joiner's override, the alarm's, [systemAlarmUri], the joiner's default;
     * wallpaper the override, the alarm's, the joiner's default; label the override, else the alarm's.
     */
    fun resolveFromRow(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: String?, systemAlarmUri: String?): RingMedia {
        val link = alarm.link ?: return RingMedia(
            label = alarm.label,
            soundCandidates = listOfNotNull(alarm.ringtoneUri, joinerDefaultRingtone),
            wallpaperPaths = chooseWallpaperPaths(alarm.wallpaperPath, joinerDefaultWallpaper),
        )
        return RingMedia(
            label = link.overrideLabel?.takeIf { it.isNotBlank() } ?: alarm.label,
            soundCandidates = listOfNotNull(link.overrideRingtoneUri, alarm.ringtoneUri, systemAlarmUri, joinerDefaultRingtone)
                .filter { it.isNotBlank() }.distinct(),
            wallpaperPaths = listOfNotNull(link.overrideWallpaperPath, alarm.wallpaperPath, joinerDefaultWallpaper)
                .filter { it.isNotBlank() }.distinct(),
        )
    }
}

/**
 * The engine is built before AppContainer, and RingingSession can't reach AppContainer, so the real
 * cache is swapped in later by assigning [delegate].
 */
class SwitchableLinkedMediaCache : LinkedMediaCache {
    @Volatile var delegate: LinkedMediaCache = DefaultLinkedMediaCache

    override fun update(ownerUid: String, alarms: Map<String, AlarmMedia>, defaultWallpaper: MediaRef?) =
        delegate.update(ownerUid, alarms, defaultWallpaper)

    override fun pathFor(sha: String): String? = delegate.pathFor(sha)
    override fun inUse(): Set<String> = delegate.inUse()

    override suspend fun resolve(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: String?): RingMedia =
        delegate.resolve(alarm, joinerDefaultRingtone, joinerDefaultWallpaper)
}
