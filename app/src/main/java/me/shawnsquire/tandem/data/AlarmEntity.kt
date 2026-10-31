package me.shawnsquire.tandem.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.DayOfWeek
import java.time.Instant
import me.shawnsquire.tandem.schedule.Alarm

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
)

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
)
