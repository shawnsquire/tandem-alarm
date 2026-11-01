package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.media.LinkedMediaCache
import me.shawnsquire.tandem.media.RingMedia
import me.shawnsquire.tandem.schedule.Alarm
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a ring plays and shows. An own ring (no link) is the alarm's own media then the joiner's
 * defaults, with no cache involved. A linked ring asks [cache], bounded by [timeout]: I1, a slow or
 * failing cache never delays sound beyond that, it falls back to the same own-ring formula (label:
 * the joiner's override, else the alarm's). Only the caller's own cancellation propagates.
 */
suspend fun resolveRingMedia(
    alarm: Alarm?,
    cache: LinkedMediaCache,
    defaultRingtone: String?,
    defaultWallpaper: String?,
    timeout: Duration = RING_MEDIA_TIMEOUT,
): RingMedia {
    fun own() = RingMedia(
        label = alarm?.link?.overrideLabel ?: alarm?.label.orEmpty(),
        soundCandidates = listOfNotNull(alarm?.ringtoneUri, defaultRingtone),
        wallpaperPaths = chooseWallpaperPaths(alarm?.wallpaperPath, defaultWallpaper),
    )
    if (alarm?.link == null) return own()
    return try {
        withTimeoutOrNull(timeout) { cache.resolve(alarm, defaultRingtone, defaultWallpaper) } ?: own()
    } catch (e: Throwable) {
        currentCoroutineContext().ensureActive()
        own()
    }
}

val RING_MEDIA_TIMEOUT = 500.milliseconds
