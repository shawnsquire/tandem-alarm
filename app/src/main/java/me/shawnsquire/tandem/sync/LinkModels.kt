package me.shawnsquire.tandem.sync

import me.shawnsquire.tandem.schedule.LinkMode

/** A content-addressed file in Cloud Storage `households/{hid}/media/{ownerUid}/{sha}`. [title] only for ringtones. */
data class MediaRef(val sha: String, val size: Long, val title: String? = null)

/** The media refs of one alarm: its wallpaper and ringtone, either absent. */
data class AlarmMedia(val wallpaper: MediaRef?, val ringtone: MediaRef?)

/** Owner-side media refs to publish: [alarms] keyed by own alarm id. */
data class PublishedMedia(val defaultWallpaper: MediaRef?, val alarms: Map<String, AlarmMedia>)

/** One entry of the device doc's `links`, written by the joiner. */
data class PublishedLink(val ownerAlarmId: String, val mode: LinkMode)
