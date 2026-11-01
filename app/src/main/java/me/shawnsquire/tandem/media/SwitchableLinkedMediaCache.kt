package me.shawnsquire.tandem.media

import me.shawnsquire.tandem.alarm.chooseWallpaperPaths
import me.shawnsquire.tandem.schedule.Alarm

/** Today's behavior, before linked media exists: nothing cached, the alarm's own media plus the joiner defaults. */
object DefaultLinkedMediaCache : LinkedMediaCache {
    override fun update(ownerUid: String, alarms: Map<String, AlarmMedia>, defaultWallpaper: MediaRef?) = Unit
    override fun pathFor(sha: String): String? = null
    override fun inUse(): Set<String> = emptySet()

    override suspend fun resolve(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: String?) = RingMedia(
        label = alarm.link?.overrideLabel ?: alarm.label,
        soundCandidates = listOfNotNull(alarm.ringtoneUri, joinerDefaultRingtone),
        wallpaperPaths = chooseWallpaperPaths(alarm.wallpaperPath, joinerDefaultWallpaper),
    )
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
