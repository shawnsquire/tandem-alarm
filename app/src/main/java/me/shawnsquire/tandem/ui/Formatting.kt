package me.shawnsquire.tandem.ui

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

private fun DayOfWeek.short(): String = getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

/** "Every day", "Mon–Fri", "Weekends", "Once", or a Mon..Sun comma list. */
fun formatDays(days: Set<DayOfWeek>): String = when {
    days.isEmpty() -> "Once"
    days.size == 7 -> "Every day"
    days == WEEKDAYS -> "Mon–Fri"
    days == WEEKEND -> "Weekends"
    else -> DayOfWeek.entries.filter { it in days }.joinToString(", ") { it.short() }
}

/** "Rings in 7 h 12 min", "Rings in 2 d 3 h". A negative [until] counts as zero. */
fun formatRingsIn(until: Duration): String {
    val totalMinutes = until.toMinutes().coerceAtLeast(0)
    if (totalMinutes < 1) return "Rings in less than a minute"
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    val parts = if (days > 0) {
        listOfNotNull("$days d", if (hours > 0) "$hours h" else null)
    } else {
        listOfNotNull(if (hours > 0) "$hours h" else null, if (minutes > 0) "$minutes min" else null)
    }
    return "Rings in " + parts.joinToString(" ")
}

/** "09:05" (24 h) or "9:05" (12 h, pair with [amPm]). */
fun formatClock(hour: Int, minute: Int, is24Hour: Boolean): String {
    val h = if (is24Hour) hour else (hour % 12).let { if (it == 0) 12 else it }
    return if (is24Hour) "%02d:%02d".format(h, minute) else "%d:%02d".format(h, minute)
}

/** "AM"/"PM" in 12 h mode, null in 24 h mode. */
fun amPm(hour: Int, is24Hour: Boolean): String? = if (is24Hour) null else if (hour < 12) "AM" else "PM"

/** Short weekday plus time, e.g. "Tue 07:00" or "Tue 7:00 AM". */
fun formatWeekdayTime(
    at: Instant,
    zone: ZoneId,
    is24Hour: Boolean,
    locale: Locale = Locale.getDefault(),
): String {
    val z = at.atZone(zone)
    val day = z.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    val time = formatClock(z.hour, z.minute, is24Hour) + (amPm(z.hour, is24Hour)?.let { " $it" } ?: "")
    return "$day $time"
}
