package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.shawnsquire.tandem.media.LinkedMediaCache
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingState

/**
 * What a ring shows: every member's label (primary first), the partner's name on a joined ring, and
 * the primary's wallpaper candidates (the screen decodes the first readable one).
 */
data class RingScreenContent(
    val labels: List<String>,
    val withPartner: String?,
    val wallpaperPaths: List<String>,
)

/**
 * Resolves the primary of [ring] and each merged member through [resolveRingMedia], one at a time
 * and each guarded: a member that can't be read or resolved shows nothing for itself. Blank labels
 * are skipped. [RingScreenContent.withPartner] is [partnerName] when any member is a joined copy
 * (`link != null`) and a name is known. Only the caller's own cancellation propagates.
 */
suspend fun ringScreenContent(
    ring: RingState,
    readAlarm: suspend (String) -> Alarm?,
    cache: LinkedMediaCache,
    defaultWallpaper: String?,
    partnerName: String?,
): RingScreenContent {
    val resolved = (listOf(ring.alarmId) + ring.merged.map { it.alarmId }).map { id ->
        val alarm = guardedOrNull { readAlarm(id) }
        alarm to guardedOrNull { resolveRingMedia(alarm, cache, null, defaultWallpaper) }
    }
    val joined = resolved.any { (alarm, _) -> alarm?.link != null }
    return RingScreenContent(
        labels = resolved.mapNotNull { (_, media) -> media?.label?.takeIf { it.isNotBlank() } },
        withPartner = partnerName?.takeIf { joined && it.isNotBlank() },
        wallpaperPaths = resolved.first().second?.wallpaperPaths ?: chooseWallpaperPaths(null, defaultWallpaper),
    )
}

private suspend inline fun <T> guardedOrNull(block: () -> T): T? = try {
    block()
} catch (e: Throwable) {
    currentCoroutineContext().ensureActive()
    null
}
