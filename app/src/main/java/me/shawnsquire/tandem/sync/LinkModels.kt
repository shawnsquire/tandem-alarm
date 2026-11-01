package me.shawnsquire.tandem.sync

import me.shawnsquire.tandem.media.AlarmMedia
import me.shawnsquire.tandem.media.MediaRef
import me.shawnsquire.tandem.schedule.LinkMode

/** Owner-side media refs to publish: [alarms] keyed by own alarm id. */
data class PublishedMedia(val defaultWallpaper: MediaRef?, val alarms: Map<String, AlarmMedia>)

/** One entry of the device doc's `links`, written by the joiner. */
data class PublishedLink(val ownerAlarmId: String, val mode: LinkMode)
