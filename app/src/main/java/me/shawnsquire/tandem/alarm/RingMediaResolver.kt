package me.shawnsquire.tandem.alarm

import android.provider.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.media.DefaultLinkedMediaCache
import me.shawnsquire.tandem.media.LinkedMediaCache
import me.shawnsquire.tandem.media.RingMedia
import me.shawnsquire.tandem.media.RingWallpaper
import me.shawnsquire.tandem.schedule.Alarm
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a ring plays and shows. An own ring (no link) is the alarm's own media then the joiner's
 * defaults, with no cache involved; the sound adapter appends the system defaults. A linked ring
 * asks [cache], bounded by [timeout]: I1, a slow or failing cache never delays sound beyond that, it
 * falls back to the row's own media in the cache's order, each override first as the label's is:
 * sound the joiner's override, the alarm's, [systemAlarmUri] (read only then), the joiner's default;
 * wallpaper the joiner's override, the alarm's, the joiner's default. Only the caller's own
 * cancellation propagates.
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
    defaultWallpaper: RingWallpaper?,
    timeout: Duration = RING_MEDIA_TIMEOUT,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    systemAlarmUri: () -> String? = ::systemDefaultAlarmUri,
): RingMedia {
    val link = alarm?.link ?: return RingMedia(
        label = alarm?.label.orEmpty(),
        soundCandidates = listOfNotNull(alarm?.ringtoneUri, defaultRingtone),
        wallpapers = chooseWallpapers(alarm?.wallpaperPath, alarm?.wallpaperFrame, defaultWallpaper),
    )
    fun fallback() = DefaultLinkedMediaCache.resolveFromRow(alarm, defaultRingtone, defaultWallpaper, systemAlarmUri())
    // Its own Job: a throw stays in the Deferred (await rethrows it) and never cancels the caller.
    val resolving = CoroutineScope(dispatcher).async { cache.resolve(alarm, defaultRingtone, defaultWallpaper) }
    return try {
        withTimeoutOrNull(timeout) { resolving.await() } ?: fallback()
    } catch (e: Throwable) {
        currentCoroutineContext().ensureActive()
        fallback()
    } finally {
        resolving.cancel() // a timeout or the caller's own cancellation: nobody waits for it any more
    }
}

val RING_MEDIA_TIMEOUT = 500.milliseconds

/** The system alarm sound's symbolic Uri, as the media cache places it; null where it can't be read (a JVM test). */
internal fun systemDefaultAlarmUri(): String? = runCatching { Settings.System.DEFAULT_ALARM_ALERT_URI?.toString() }.getOrNull()
