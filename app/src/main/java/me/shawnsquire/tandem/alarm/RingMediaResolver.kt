package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 *
 * The resolve runs on [dispatcher], outside the caller's job (as the session's pre-fire check
 * does): a cache that blocks its thread on disk can't be interrupted, so on the caller's dispatcher
 * (the main thread, for the session and the ringing screen) it would hold both the thread and the
 * bound. Abandoned and cancelled at the timeout; it ends whenever its thread comes back.
 */
suspend fun resolveRingMedia(
    alarm: Alarm?,
    cache: LinkedMediaCache,
    defaultRingtone: String?,
    defaultWallpaper: String?,
    timeout: Duration = RING_MEDIA_TIMEOUT,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): RingMedia {
    fun own() = RingMedia(
        label = alarm?.link?.overrideLabel ?: alarm?.label.orEmpty(),
        soundCandidates = listOfNotNull(alarm?.ringtoneUri, defaultRingtone),
        wallpaperPaths = chooseWallpaperPaths(alarm?.wallpaperPath, defaultWallpaper),
    )
    if (alarm?.link == null) return own()
    // Its own Job: a throw stays in the Deferred (await rethrows it) and never cancels the caller.
    val resolving = CoroutineScope(dispatcher).async { cache.resolve(alarm, defaultRingtone, defaultWallpaper) }
    return try {
        withTimeoutOrNull(timeout) { resolving.await() } ?: own()
    } catch (e: Throwable) {
        currentCoroutineContext().ensureActive()
        own()
    } finally {
        resolving.cancel() // a timeout or the caller's own cancellation: nobody waits for it any more
    }
}

val RING_MEDIA_TIMEOUT = 500.milliseconds
