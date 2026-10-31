package me.shawnsquire.tandem.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextOccurrenceTest {
    private val newYork = ZoneId.of("America/New_York")
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val allDays = DayOfWeek.entries.toSet()

    // Saturday 2026-11-07 07:00 EST
    private val saturdayMorning = Instant.parse("2026-11-07T12:00:00Z")

    private fun alarm(
        hour: Int,
        minute: Int = 0,
        days: Set<DayOfWeek> = emptySet(),
        enabled: Boolean = true,
        skippedOccurrenceAt: Instant? = null,
    ) = Alarm(
        id = "a1",
        label = "",
        hour = hour,
        minute = minute,
        days = days,
        enabled = enabled,
        skippedOccurrenceAt = skippedOccurrenceAt,
        ringtoneUri = null,
        wallpaperPath = null,
    )

    private fun next(alarm: Alarm, now: Instant, zone: ZoneId = newYork) =
        nextOccurrence(alarm, now, zone)

    @Test
    fun disabledAlarmReturnsNull() {
        assertNull(next(alarm(8, days = allDays, enabled = false), saturdayMorning))
    }

    @Test
    fun weekdayAlarmRollsOverFromSaturdayToMonday() {
        val result = next(alarm(6, 30, days = setOf(DayOfWeek.MONDAY)), saturdayMorning)
        assertEquals(Instant.parse("2026-11-09T11:30:00Z"), result)
    }

    @Test
    fun weekdayAlarmLaterTodayRingsToday() {
        val result = next(alarm(8, days = setOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY)), saturdayMorning)
        assertEquals(Instant.parse("2026-11-07T13:00:00Z"), result)
    }

    @Test
    fun weekdayAlarmExactlyNowIsNotReturned() {
        val result = next(alarm(7, days = setOf(DayOfWeek.SATURDAY)), saturdayMorning)
        assertEquals(Instant.parse("2026-11-14T12:00:00Z"), result)
    }

    @Test
    fun oneShotLaterTodayRingsToday() {
        assertEquals(Instant.parse("2026-11-07T13:00:00Z"), next(alarm(8), saturdayMorning))
    }

    @Test
    fun oneShotEarlierThanNowRingsTomorrow() {
        assertEquals(Instant.parse("2026-11-08T11:00:00Z"), next(alarm(6), saturdayMorning))
    }

    @Test
    fun oneShotExactlyNowRingsTomorrow() {
        assertEquals(Instant.parse("2026-11-08T12:00:00Z"), next(alarm(7), saturdayMorning))
    }

    @Test
    fun skippedOccurrenceReturnsFollowingOne() {
        val alarm = alarm(
            8,
            days = setOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY),
            skippedOccurrenceAt = Instant.parse("2026-11-07T13:00:00Z"),
        )
        assertEquals(Instant.parse("2026-11-09T13:00:00Z"), next(alarm, saturdayMorning))
    }

    @Test
    fun skippedOnlyWeekdayReturnsSameDayNextWeek() {
        val alarm = alarm(
            8,
            days = setOf(DayOfWeek.SATURDAY),
            skippedOccurrenceAt = Instant.parse("2026-11-07T13:00:00Z"),
        )
        assertEquals(Instant.parse("2026-11-14T13:00:00Z"), next(alarm, saturdayMorning))
    }

    @Test
    fun skipInThePastIsIgnored() {
        val days = setOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY)
        val past = alarm(8, days = days, skippedOccurrenceAt = Instant.parse("2026-11-07T11:00:00Z"))
        val atNow = alarm(8, days = days, skippedOccurrenceAt = saturdayMorning)
        val expected = Instant.parse("2026-11-07T13:00:00Z")
        assertEquals(expected, next(past, saturdayMorning))
        assertEquals(expected, next(atNow, saturdayMorning))
    }

    @Test
    fun skipMatchingNoCandidateIsIgnored() {
        val alarm = alarm(
            8,
            days = setOf(DayOfWeek.SATURDAY),
            skippedOccurrenceAt = Instant.parse("2026-11-08T13:00:00Z"),
        )
        assertEquals(Instant.parse("2026-11-07T13:00:00Z"), next(alarm, saturdayMorning))
    }

    @Test
    fun skippedOneShotReturnsNull() {
        val alarm = alarm(8, skippedOccurrenceAt = Instant.parse("2026-11-07T13:00:00Z"))
        assertNull(next(alarm, saturdayMorning))
    }

    @Test
    fun springForwardNonexistentTwoThirtyRingsAtShiftedThreeThirty() {
        val alarm = alarm(2, 30, days = allDays)
        val now = Instant.parse("2027-03-14T05:00:00Z") // 00:00 EST
        assertEquals(Instant.parse("2027-03-14T07:30:00Z"), next(alarm, now)) // 03:30 EDT
    }

    @Test
    fun springForwardSundayOnlyAlarmRingsAtShiftedThreeThirty() {
        val alarm = alarm(2, 30, days = setOf(DayOfWeek.SUNDAY))
        val now = Instant.parse("2027-03-14T05:00:00Z")
        assertEquals(Instant.parse("2027-03-14T07:30:00Z"), next(alarm, now))
    }

    @Test
    fun fallBackAmbiguousOneThirtyUsesEarlierOffset() {
        val alarm = alarm(1, 30, days = allDays)
        val now = Instant.parse("2026-11-01T04:00:00Z") // 00:00 EDT
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), next(alarm, now)) // 01:30 EDT
    }

    @Test
    fun fallBackExactlyFirstOneThirtyRingsNextDay() {
        val alarm = alarm(1, 30, days = allDays)
        val now = Instant.parse("2026-11-01T05:30:00Z")
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), next(alarm, now)) // 01:30 EST
    }

    @Test
    fun fallBackSecondPassOneThirtyDoesNotRingTwice() {
        val alarm = alarm(1, 30, days = allDays)
        val now = Instant.parse("2026-11-01T06:10:00Z") // 01:10 EST, after the first 01:30
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), next(alarm, now))
    }

    @Test
    fun nonUsZoneKolkataUsesLocalDateAndHalfHourOffset() {
        // 2026-11-07T20:00Z is already Sunday 01:30 IST
        val alarm = alarm(6, days = setOf(DayOfWeek.SUNDAY))
        val now = Instant.parse("2026-11-07T20:00:00Z")
        assertEquals(Instant.parse("2026-11-08T00:30:00Z"), next(alarm, now, kolkata))
    }

    @Test
    fun oneSecondAfterFireReturnsTomorrow() {
        val daily = alarm(8, days = allDays)
        val result = next(daily, Instant.parse("2026-11-07T13:00:01Z"))
        assertEquals(Instant.parse("2026-11-08T13:00:00Z"), result)
    }
}
