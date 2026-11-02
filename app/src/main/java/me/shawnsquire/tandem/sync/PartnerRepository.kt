package me.shawnsquire.tandem.sync

import me.shawnsquire.tandem.media.AlarmMedia
import me.shawnsquire.tandem.media.MediaRef
import kotlinx.coroutines.flow.Flow
import me.shawnsquire.tandem.schedule.LateRing
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.WallpaperFrame
import java.time.DayOfWeek
import java.time.Instant

/** Read-only view of a partner alarm, mirrored from their phone (I2: partner data is never written here). */
data class PartnerAlarm(
    val id: String,
    val label: String,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek>,
    val enabled: Boolean,
    val nextOccurrenceAt: Instant?,
    val skippedOccurrenceAt: Instant?,
    val media: AlarmMedia? = null,
    val soloSkippedOccurrenceAt: Instant? = null,
    val lateRing: LateRing? = null,
    val vibration: VibrationPattern = VibrationPattern.PULSE,
    val vibrationOn: Boolean = true,
    val soundOn: Boolean = true,
    /** Frames the alarm's own wallpaper (null: centred) and places the ring text; both copied by a joiner. */
    val wallpaperFrame: WallpaperFrame? = null,
    val textPosition: TextPosition = TextPosition.MIDDLE,
)

/** Read-only mirror of the partner's state; commands target [PartnerAlarm.nextOccurrenceAt] / [ringing] (I3). */
data class PartnerState(
    val displayName: String,
    val alarms: List<PartnerAlarm>,
    val ringing: RingState?,
    val lastSeen: Instant?,
    val links: List<PublishedLink> = emptyList(),
    val defaultWallpaper: MediaRef? = null,
    val defaultWallpaperFrame: WallpaperFrame? = null,
)

interface PartnerRepository {
    /** Null when unpaired or nothing has been received yet. */
    val partner: Flow<PartnerState?>

    /** Source.SERVER, never the cache; null on any failure, so the caller rings (I1). L4 implements. */
    suspend fun readFromServer(): PartnerState?
}
