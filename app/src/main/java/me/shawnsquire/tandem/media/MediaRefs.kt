package me.shawnsquire.tandem.media

/** A content-addressed file in Cloud Storage `households/{hid}/media/{ownerUid}/{sha}`. [title] only for ringtones. */
data class MediaRef(val sha: String, val size: Long, val title: String? = null)

/** The media refs of one alarm: its wallpaper and ringtone, either absent. */
data class AlarmMedia(val wallpaper: MediaRef?, val ringtone: MediaRef?)
