package me.shawnsquire.tandem.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.DayOfWeek
import java.time.Instant
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey val id: String,
    val label: String,
    val hour: Int,
    val minute: Int,
    /** Bit `day.value - 1` set per day: MONDAY = bit 0 ... SUNDAY = bit 6. */
    val days: Int,
    val enabled: Boolean,
    /** Epoch millis of the skipped occurrence. */
    val skippedOccurrenceAt: Long?,
    val ringtoneUri: String?,
    val wallpaperPath: String?,
    // Link columns are flattened and all nullable with no default, so the v1 -> v2 migration is a
    // plain ADD COLUMN per column. The row is linked iff linkOwnerUid and linkOwnerAlarmId are set.
    val linkOwnerUid: String? = null,
    val linkOwnerAlarmId: String? = null,
    /** [LinkMode] name. */
    val linkMode: String? = null,
    /** Epoch millis. */
    val linkOwnerSkippedOccurrenceAt: Long? = null,
    val linkJoinerEnabled: Boolean? = null,
    /** [CommandType] name; the pending command exists iff this and its id are set. */
    val linkPendingBothType: String? = null,
    /** Epoch millis. */
    val linkPendingBothOccurrenceAt: Long? = null,
    val linkPendingBothCommandId: String? = null,
    val linkOverrideLabel: String? = null,
    val linkOverrideRingtoneUri: String? = null,
    val linkOverrideWallpaperPath: String? = null,
    /** Epoch millis of the owner's "just me" skipped occurrence. */
    val soloSkippedOccurrenceAt: Long? = null,
)

fun AlarmEntity.toAlarm(): Alarm = Alarm(
    id = id,
    label = label,
    hour = hour,
    minute = minute,
    days = DayOfWeek.entries.filter { days and (1 shl (it.value - 1)) != 0 }.toSet(),
    enabled = enabled,
    skippedOccurrenceAt = skippedOccurrenceAt?.let(Instant::ofEpochMilli),
    ringtoneUri = ringtoneUri,
    wallpaperPath = wallpaperPath,
    link = toLink(),
    soloSkippedOccurrenceAt = soloSkippedOccurrenceAt?.let(Instant::ofEpochMilli),
)

/**
 * Null unless linkOwnerUid and linkOwnerAlarmId are both set. A malformed linked row (missing
 * mode or joinerEnabled, unknown mode) falls back to mode RING and joinerEnabled true rather than
 * dropping the link; a pending command with a missing/unknown type or no id is dropped.
 */
private fun AlarmEntity.toLink(): AlarmLink? {
    val uid = linkOwnerUid ?: return null
    val ownerAlarmId = linkOwnerAlarmId ?: return null
    val pendingType = linkPendingBothType?.let { name -> CommandType.entries.firstOrNull { it.name == name } }
    val pending = if (pendingType != null && linkPendingBothCommandId != null) {
        PendingBoth(pendingType, linkPendingBothOccurrenceAt?.let(Instant::ofEpochMilli), linkPendingBothCommandId)
    } else null
    return AlarmLink(
        ownerUid = uid,
        ownerAlarmId = ownerAlarmId,
        mode = LinkMode.entries.firstOrNull { it.name == linkMode } ?: LinkMode.RING,
        ownerSkippedOccurrenceAt = linkOwnerSkippedOccurrenceAt?.let(Instant::ofEpochMilli),
        joinerEnabled = linkJoinerEnabled ?: true,
        pendingBoth = pending,
        overrideLabel = linkOverrideLabel,
        overrideRingtoneUri = linkOverrideRingtoneUri,
        overrideWallpaperPath = linkOverrideWallpaperPath,
    )
}

fun Alarm.toEntity(): AlarmEntity = AlarmEntity(
    id = id,
    label = label,
    hour = hour,
    minute = minute,
    days = days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) },
    enabled = enabled,
    skippedOccurrenceAt = skippedOccurrenceAt?.toEpochMilli(),
    ringtoneUri = ringtoneUri,
    wallpaperPath = wallpaperPath,
    linkOwnerUid = link?.ownerUid,
    linkOwnerAlarmId = link?.ownerAlarmId,
    linkMode = link?.mode?.name,
    linkOwnerSkippedOccurrenceAt = link?.ownerSkippedOccurrenceAt?.toEpochMilli(),
    linkJoinerEnabled = link?.joinerEnabled,
    linkPendingBothType = link?.pendingBoth?.type?.name,
    linkPendingBothOccurrenceAt = link?.pendingBoth?.occurrenceAt?.toEpochMilli(),
    linkPendingBothCommandId = link?.pendingBoth?.commandId,
    linkOverrideLabel = link?.overrideLabel,
    linkOverrideRingtoneUri = link?.overrideRingtoneUri,
    linkOverrideWallpaperPath = link?.overrideWallpaperPath,
    soloSkippedOccurrenceAt = soloSkippedOccurrenceAt?.toEpochMilli(),
)
