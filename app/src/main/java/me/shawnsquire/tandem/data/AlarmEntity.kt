package me.shawnsquire.tandem.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.DayOfWeek
import java.time.Instant
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LateRing
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth
import me.shawnsquire.tandem.schedule.VibrationPattern

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
    /** Epoch millis. Null only for a command stored before v3, which the v2 -> v3 migration stamps with its own time. */
    val linkPendingBothCreatedAt: Long? = null,
    val linkOverrideLabel: String? = null,
    val linkOverrideRingtoneUri: String? = null,
    val linkOverrideWallpaperPath: String? = null,
    /** Epoch millis of the owner's "just me" skipped occurrence. */
    val soloSkippedOccurrenceAt: Long? = null,
    /** Epoch millis of [LateRing.occurrenceAt]. Set together with [lateRingRing], or both null. */
    val lateRingOccurrenceAt: Long? = null,
    val lateRingRing: Boolean? = null,
    /** [VibrationPattern] name; see [toVibrationPattern]. */
    @ColumnInfo(defaultValue = "PULSE") val vibration: String = VibrationPattern.PULSE.name,
    @ColumnInfo(defaultValue = "1") val vibrationOn: Boolean = true,
    @ColumnInfo(defaultValue = "1") val soundOn: Boolean = true,
    /** [VibrationPattern] name; null means use the copied [vibration]. */
    val linkOverrideVibration: String? = null,
    val linkOverrideVibrationOn: Boolean? = null,
    val linkOverrideSoundOn: Boolean? = null,
)

/** An unknown name (a newer build's pattern, or bad data) reads as the default pattern. */
fun String.toVibrationPattern(): VibrationPattern = VibrationPattern.entries.firstOrNull { it.name == this } ?: VibrationPattern.PULSE

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
    lateRing = if (lateRingOccurrenceAt != null && lateRingRing != null) {
        LateRing(Instant.ofEpochMilli(lateRingOccurrenceAt), lateRingRing)
    } else null,
    vibration = vibration.toVibrationPattern(),
    vibrationOn = vibrationOn,
    soundOn = soundOn,
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
        PendingBoth(
            pendingType,
            linkPendingBothOccurrenceAt?.let(Instant::ofEpochMilli),
            linkPendingBothCommandId,
            linkPendingBothCreatedAt?.let(Instant::ofEpochMilli),
        )
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
        overrideVibration = linkOverrideVibration?.toVibrationPattern(),
        overrideVibrationOn = linkOverrideVibrationOn,
        overrideSoundOn = linkOverrideSoundOn,
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
    linkPendingBothCreatedAt = link?.pendingBoth?.createdAt?.toEpochMilli(),
    linkOverrideLabel = link?.overrideLabel,
    linkOverrideRingtoneUri = link?.overrideRingtoneUri,
    linkOverrideWallpaperPath = link?.overrideWallpaperPath,
    soloSkippedOccurrenceAt = soloSkippedOccurrenceAt?.toEpochMilli(),
    lateRingOccurrenceAt = lateRing?.occurrenceAt?.toEpochMilli(),
    lateRingRing = lateRing?.ring,
    vibration = vibration.name,
    vibrationOn = vibrationOn,
    soundOn = soundOn,
    linkOverrideVibration = link?.overrideVibration?.name,
    linkOverrideVibrationOn = link?.overrideVibrationOn,
    linkOverrideSoundOn = link?.overrideSoundOn,
)
