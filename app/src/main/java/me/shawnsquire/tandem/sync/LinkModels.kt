package me.shawnsquire.tandem.sync

import me.shawnsquire.tandem.media.AlarmMedia
import me.shawnsquire.tandem.media.MediaRef
import me.shawnsquire.tandem.schedule.LinkMode
import java.time.DayOfWeek

/** Owner-side media refs to publish: [alarms] keyed by own alarm id. */
data class PublishedMedia(val defaultWallpaper: MediaRef?, val alarms: Map<String, AlarmMedia>)

/**
 * One entry of the device doc's `links`, written by the joiner. [hour], [minute] and [days] are the joiner's
 * copy of the owner's schedule, which the owner compares with its own to see whether the joiner picked up an
 * edit. All three are set, or all null for unknown (a partner on an older build): null is never "not confirmed".
 */
data class PublishedLink(
    val ownerAlarmId: String,
    val mode: LinkMode,
    val hour: Int? = null,
    val minute: Int? = null,
    val days: Set<DayOfWeek>? = null,
)
