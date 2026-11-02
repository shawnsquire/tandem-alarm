package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.shawnsquire.tandem.media.LinkedMediaCache
import me.shawnsquire.tandem.media.RingWallpaper
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.effectiveTextPosition

/**
 * What a ring shows: every member's label (primary first), the partner's name on a joined ring, the
 * primary's wallpaper candidates (the screen decodes the first readable one, framed as its candidate is)
 * and where the primary puts the text block ([TextPosition.MIDDLE] when the primary can't be read).
 */
data class RingScreenContent(
    val labels: List<String>,
    val withPartner: String?,
    val wallpapers: List<RingWallpaper>,
    val textPosition: TextPosition,
)

/**
 * Resolves the primary of [ring] and each merged member through [resolveRingMedia], one at a time
 * and each guarded: a member that can't be read or resolved shows nothing for itself. Blank labels
 * are skipped. [RingScreenContent.withPartner] is [partnerName] when any member is a joined copy
 * (`link != null`) and a name is known. Only the caller's own cancellation propagates. Each resolve
 * runs on [dispatcher] (see [resolveRingMedia]).
 */
suspend fun ringScreenContent(
    ring: RingState,
    readAlarm: suspend (String) -> Alarm?,
    cache: LinkedMediaCache,
    defaultWallpaper: RingWallpaper?,
    partnerName: String?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): RingScreenContent {
    val resolved = (listOf(ring.alarmId) + ring.merged.map { it.alarmId }).map { id ->
        val alarm = guardedOrNull { readAlarm(id) }
        alarm to guardedOrNull { resolveRingMedia(alarm, cache, null, defaultWallpaper, dispatcher = dispatcher) }
    }
    val joined = resolved.any { (alarm, _) -> alarm?.link != null }
    return RingScreenContent(
        labels = resolved.mapNotNull { (_, media) -> media?.label?.takeIf { it.isNotBlank() } },
        withPartner = partnerName?.takeIf { joined && it.isNotBlank() },
        wallpapers = resolved.first().second?.wallpapers ?: chooseWallpapers(null, null, defaultWallpaper),
        textPosition = resolved.first().first?.effectiveTextPosition() ?: TextPosition.MIDDLE,
    )
}

private suspend inline fun <T> guardedOrNull(block: () -> T): T? = try {
    block()
} catch (e: Throwable) {
    currentCoroutineContext().ensureActive()
    null
}
