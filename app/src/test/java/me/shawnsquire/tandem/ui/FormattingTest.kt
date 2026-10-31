package me.shawnsquire.tandem.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class FormattingTest {
    @Test fun daysText() {
        assertEquals("Every day", formatDays(DayOfWeek.entries.toSet()))
        assertEquals("Mon–Fri", formatDays(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)))
        assertEquals("Weekends", formatDays(setOf(SUNDAY, SATURDAY)))
        assertEquals("Once", formatDays(emptySet()))
        assertEquals("Mon, Wed, Fri", formatDays(setOf(FRIDAY, MONDAY, WEDNESDAY)))
        assertEquals("Tue, Sun", formatDays(setOf(SUNDAY, TUESDAY)))
    }

    @Test fun ringsIn() {
        assertEquals("Rings in 7 h 12 min", formatRingsIn(Duration.ofMinutes(7 * 60 + 12)))
        assertEquals("Rings in 45 min", formatRingsIn(Duration.ofMinutes(45)))
        assertEquals("Rings in 1 min", formatRingsIn(Duration.ofSeconds(90)))
        assertEquals("Rings in less than a minute", formatRingsIn(Duration.ofSeconds(59)))
        assertEquals("Rings in less than a minute", formatRingsIn(Duration.ofSeconds(-5)))
        assertEquals("Rings in 3 h", formatRingsIn(Duration.ofHours(3)))
        assertEquals("Rings in 23 h 59 min", formatRingsIn(Duration.ofMinutes(24 * 60 - 1)))
        assertEquals("Rings in 1 d", formatRingsIn(Duration.ofHours(24)))
        assertEquals("Rings in 2 d 3 h", formatRingsIn(Duration.ofHours(51).plusMinutes(20)))
    }

    @Test fun clock() {
        assertEquals("07:05", formatClock(7, 5, true))
        assertEquals("19:05", formatClock(19, 5, true))
        assertEquals("7:05", formatClock(7, 5, false))
        assertEquals("12:00", formatClock(0, 0, false))
        assertEquals("12:30", formatClock(12, 30, false))
        assertEquals("7:05", formatClock(19, 5, false))
        assertEquals("AM", amPm(0, false))
        assertEquals("PM", amPm(12, false))
        assertEquals(null, amPm(12, true))
    }

    @Test fun weekdayTime() {
        val at = Instant.parse("2026-11-03T07:00:00Z") // Tuesday
        val utc = ZoneId.of("UTC")
        assertEquals("Tue 07:00", formatWeekdayTime(at, utc, true, Locale.US))
        assertEquals("Tue 7:00 AM", formatWeekdayTime(at, utc, false, Locale.US))
        // 07:00Z is 23:00 PST (UTC-8) on Monday.
        assertEquals("Mon 11:00 PM", formatWeekdayTime(at, ZoneId.of("America/Los_Angeles"), false, Locale.US))
    }
}
