package me.shawnsquire.tandem.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        soloSkippedOccurrenceAt: Instant? = null,
        link: AlarmLink? = null,
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
        link = link,
        soloSkippedOccurrenceAt = soloSkippedOccurrenceAt,
    )

    private fun link(
        ownerSkippedOccurrenceAt: Instant? = null,
        joinerEnabled: Boolean = true,
        pendingBoth: PendingBoth? = null,
    ) = AlarmLink(
        ownerUid = "owner",
        ownerAlarmId = "o1",
        mode = LinkMode.RING,
        ownerSkippedOccurrenceAt = ownerSkippedOccurrenceAt,
        joinerEnabled = joinerEnabled,
        pendingBoth = pendingBoth,
        overrideLabel = null,
        overrideRingtoneUri = null,
        overrideWallpaperPath = null,
    )

    private fun pending(type: CommandType, occurrenceAt: Instant? = null) =
        PendingBoth(type, occurrenceAt, "c1")

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

    // Linked rows and the solo skip.

    private val satEight = Instant.parse("2026-11-07T13:00:00Z")
    private val monEight = Instant.parse("2026-11-09T13:00:00Z")
    private val satMonDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY)

    @Test
    fun ownRowSoloSkipSkipsThatOccurrence() {
        val alarm = alarm(8, days = satMonDays, soloSkippedOccurrenceAt = satEight)
        assertEquals(monEight, next(alarm, saturdayMorning))
    }

    @Test
    fun ownRowSoloAndSharedSkipsBothApply() {
        val alarm = alarm(8, days = satMonDays, skippedOccurrenceAt = satEight, soloSkippedOccurrenceAt = monEight)
        assertEquals(Instant.parse("2026-11-14T13:00:00Z"), next(alarm, saturdayMorning))
    }

    @Test
    fun soloSkippedOneShotReturnsNull() {
        assertNull(next(alarm(8, soloSkippedOccurrenceAt = satEight), saturdayMorning))
    }

    @Test
    fun soloSkippedOneShotStaysNullAfterXUntilSkipExpiryTurnsItOff() {
        val a = alarm(8, soloSkippedOccurrenceAt = satEight)
        assertNull(next(a, satEight.plusSeconds(60)))
        assertNull(next(a, satEight.plusSeconds(119)))
        // Never tomorrow's time, at any point while the solo skip is still present.
        assertNull(next(a, satEight.plusSeconds(3600)))
    }

    @Test
    fun linkedRowWithNoSkipsSchedulesNormally() {
        assertEquals(satEight, next(alarm(8, days = satMonDays, link = link()), saturdayMorning))
    }

    @Test
    fun linkedRowIgnoresSoloSkip() {
        val alarm = alarm(8, days = satMonDays, soloSkippedOccurrenceAt = satEight, link = link())
        assertEquals(satEight, next(alarm, saturdayMorning))
    }

    @Test
    fun linkedRowOwnSkipApplies() {
        val alarm = alarm(8, days = satMonDays, skippedOccurrenceAt = satEight, link = link())
        assertEquals(monEight, next(alarm, saturdayMorning))
    }

    @Test
    fun linkedRowOwnerSkipApplies() {
        val alarm = alarm(8, days = satMonDays, link = link(ownerSkippedOccurrenceAt = satEight))
        assertEquals(monEight, next(alarm, saturdayMorning))
    }

    @Test
    fun linkedRowPendingSkipNextAppliesAtItsOccurrence() {
        val pb = pending(CommandType.SKIP_NEXT, satEight)
        val alarm = alarm(8, days = satMonDays, link = link(pendingBoth = pb))
        assertEquals(monEight, next(alarm, saturdayMorning))
    }

    @Test
    fun linkedRowPendingSkipNextWithoutOccurrenceSkipsNothing() {
        val alarm = alarm(8, days = satMonDays, link = link(pendingBoth = pending(CommandType.SKIP_NEXT)))
        assertEquals(satEight, next(alarm, saturdayMorning))
    }

    @Test
    fun linkedRowAllThreeSkipsOnConsecutiveOccurrences() {
        val fri = Instant.parse("2026-11-13T13:00:00Z")
        val alarm = alarm(
            8,
            days = setOf(DayOfWeek.FRIDAY),
            skippedOccurrenceAt = fri,
            link = link(
                ownerSkippedOccurrenceAt = fri.plusSeconds(7 * 86400),
                pendingBoth = pending(CommandType.SKIP_NEXT, fri.plusSeconds(14 * 86400)),
            ),
        )
        assertEquals(Instant.parse("2026-12-04T13:00:00Z"), next(alarm, saturdayMorning))
    }

    @Test
    fun twoSkipsOnConsecutiveOccurrencesOfSingleWeekdayAlarmReachTwoWeeksOut() {
        // Two stacked skips on a weekday alarm whose next ring is 6 days out: the answer is day 20.
        // The true worst case for the search window is in the test below.
        val fri = Instant.parse("2026-11-13T13:00:00Z")
        val alarm = alarm(
            8,
            days = setOf(DayOfWeek.FRIDAY),
            skippedOccurrenceAt = fri,
            link = link(ownerSkippedOccurrenceAt = fri.plusSeconds(7 * 86400)),
        )
        assertEquals(Instant.parse("2026-11-27T13:00:00Z"), next(alarm, saturdayMorning))
        val own = alarm(
            8,
            days = setOf(DayOfWeek.FRIDAY),
            skippedOccurrenceAt = fri,
            soloSkippedOccurrenceAt = fri.plusSeconds(7 * 86400),
        )
        assertEquals(Instant.parse("2026-11-27T13:00:00Z"), next(own, saturdayMorning))
    }

    @Test
    fun threeStackedSkipsOnTodaysWeekdayAfterItsTimePassedReachDay28() {
        // Worst case for the search window: today is Saturday 07:00 and the alarm is Saturday 06:00,
        // so the first candidate is day 7. Three skips (days 7, 14, 21) put the answer on day 28.
        val sat1 = Instant.parse("2026-11-14T11:00:00Z")
        val alarm = alarm(
            6,
            days = setOf(DayOfWeek.SATURDAY),
            skippedOccurrenceAt = sat1,
            link = link(
                ownerSkippedOccurrenceAt = sat1.plusSeconds(7 * 86400),
                pendingBoth = pending(CommandType.SKIP_NEXT, sat1.plusSeconds(14 * 86400)),
            ),
        )
        assertEquals(Instant.parse("2026-12-05T11:00:00Z"), next(alarm, saturdayMorning))
    }

    @Test
    fun expiredSkipsOfEachKindAreIgnored() {
        val past = Instant.parse("2026-11-07T11:00:00Z")
        assertEquals(
            satEight,
            next(alarm(8, days = satMonDays, soloSkippedOccurrenceAt = past), saturdayMorning),
        )
        assertEquals(
            satEight,
            next(alarm(8, days = satMonDays, skippedOccurrenceAt = past, link = link()), saturdayMorning),
        )
        assertEquals(
            satEight,
            next(alarm(8, days = satMonDays, link = link(ownerSkippedOccurrenceAt = past)), saturdayMorning),
        )
        val pb = pending(CommandType.SKIP_NEXT, past)
        assertEquals(satEight, next(alarm(8, days = satMonDays, link = link(pendingBoth = pb)), saturdayMorning))
    }

    @Test
    fun linkedRowPendingUnskipMasksOwnerSkipAtThatOccurrence() {
        val pb = pending(CommandType.UNSKIP, satEight)
        val alarm = alarm(8, days = satMonDays, link = link(ownerSkippedOccurrenceAt = satEight, pendingBoth = pb))
        assertEquals(satEight, next(alarm, saturdayMorning))
    }

    @Test
    fun linkedRowPendingUnskipDoesNotTouchJoinersOwnSkip() {
        val pb = pending(CommandType.UNSKIP, satEight)
        val alarm = alarm(
            8,
            days = satMonDays,
            skippedOccurrenceAt = satEight,
            link = link(ownerSkippedOccurrenceAt = satEight, pendingBoth = pb),
        )
        assertEquals(monEight, next(alarm, saturdayMorning))
    }

    @Test
    fun linkedRowPendingUnskipForOtherOccurrenceKeepsOwnerSkip() {
        val pb = pending(CommandType.UNSKIP, monEight)
        val alarm = alarm(8, days = satMonDays, link = link(ownerSkippedOccurrenceAt = satEight, pendingBoth = pb))
        assertEquals(monEight, next(alarm, saturdayMorning))
    }

    @Test
    fun linkedRowPendingDisableReturnsNull() {
        val pb = pending(CommandType.DISABLE, satEight)
        assertNull(next(alarm(8, days = satMonDays, link = link(pendingBoth = pb)), saturdayMorning))
    }

    @Test
    fun linkedRowPendingEnableSchedulesEvenWhenCopiedEnabledIsFalse() {
        val pb = pending(CommandType.ENABLE)
        val alarm = alarm(8, days = satMonDays, enabled = false, link = link(pendingBoth = pb))
        assertEquals(satEight, next(alarm, saturdayMorning))
    }

    @Test
    fun linkedRowWithCopiedEnabledFalseAndNoPendingReturnsNull() {
        assertNull(next(alarm(8, days = satMonDays, enabled = false, link = link()), saturdayMorning))
    }

    @Test
    fun linkedRowJoinerDisabledReturnsNull() {
        assertNull(next(alarm(8, days = satMonDays, link = link(joinerEnabled = false)), saturdayMorning))
    }

    @Test
    fun linkedRowJoinerDisabledBeatsPendingEnable() {
        val link = link(joinerEnabled = false, pendingBoth = pending(CommandType.ENABLE))
        assertNull(next(alarm(8, days = satMonDays, enabled = false, link = link), saturdayMorning))
    }

    @Test
    fun linkedOneShotOwnerSkipReturnsNull() {
        assertNull(next(alarm(8, link = link(ownerSkippedOccurrenceAt = satEight)), saturdayMorning))
    }

    @Test
    fun linkedSpringForwardNonexistentTwoThirtyRingsAtShiftedThreeThirty() {
        val alarm = alarm(2, 30, days = allDays, link = link())
        val now = Instant.parse("2027-03-14T05:00:00Z")
        assertEquals(Instant.parse("2027-03-14T07:30:00Z"), next(alarm, now))
    }

    @Test
    fun linkedSpringForwardSkipOfShiftedOccurrenceReturnsFollowingDay() {
        val alarm = alarm(
            2,
            30,
            days = allDays,
            link = link(ownerSkippedOccurrenceAt = Instant.parse("2027-03-14T07:30:00Z")),
        )
        val now = Instant.parse("2027-03-14T05:00:00Z")
        assertEquals(Instant.parse("2027-03-15T06:30:00Z"), next(alarm, now))
    }

    @Test
    fun linkedFallBackAmbiguousOneThirtyUsesEarlierOffset() {
        val alarm = alarm(1, 30, days = allDays, link = link())
        val now = Instant.parse("2026-11-01T04:00:00Z")
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), next(alarm, now))
    }

    @Test
    fun linkedFallBackSecondPassOneThirtyDoesNotRingTwice() {
        val alarm = alarm(1, 30, days = allDays, link = link())
        val now = Instant.parse("2026-11-01T06:10:00Z")
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), next(alarm, now))
    }

    @Test
    fun linkedNonUsZoneKolkataUsesLocalDateAndHalfHourOffset() {
        val alarm = alarm(6, days = setOf(DayOfWeek.SUNDAY), link = link())
        val now = Instant.parse("2026-11-07T20:00:00Z")
        assertEquals(Instant.parse("2026-11-08T00:30:00Z"), next(alarm, now, kolkata))
    }

    // isArmed

    @Test
    fun ownRowIsArmedWhenEnabled() {
        assertEquals(true, isArmed(alarm(8)))
        assertEquals(false, isArmed(alarm(8, enabled = false)))
    }

    @Test
    fun linkedRowIsArmedWhenJoinerAndCopiedEnabled() {
        assertEquals(true, isArmed(alarm(8, link = link())))
    }

    @Test
    fun linkedRowNotArmedWhenJoinerDisabled() {
        assertEquals(false, isArmed(alarm(8, link = link(joinerEnabled = false))))
    }

    @Test
    fun linkedRowNotArmedWhenCopiedEnabledFalse() {
        assertEquals(false, isArmed(alarm(8, enabled = false, link = link())))
    }

    @Test
    fun linkedRowPendingEnableArmsDespiteCopiedEnabledFalse() {
        val link = link(pendingBoth = pending(CommandType.ENABLE))
        assertEquals(true, isArmed(alarm(8, enabled = false, link = link)))
        assertEquals(false, isArmed(alarm(8, enabled = false, link = link.copy(joinerEnabled = false))))
    }

    @Test
    fun linkedRowPendingDisableDisarms() {
        assertEquals(false, isArmed(alarm(8, link = link(pendingBoth = pending(CommandType.DISABLE)))))
    }

    @Test
    fun linkedRowPendingSkipAndUnskipDoNotAffectArming() {
        assertEquals(true, isArmed(alarm(8, link = link(pendingBoth = pending(CommandType.SKIP_NEXT, satEight)))))
        assertEquals(true, isArmed(alarm(8, link = link(pendingBoth = pending(CommandType.UNSKIP, satEight)))))
        assertEquals(
            false,
            isArmed(alarm(8, enabled = false, link = link(pendingBoth = pending(CommandType.SKIP_NEXT, satEight)))),
        )
    }

    // ---- fallsOnSchedule ----

    @Test
    fun fallsOnScheduleMatchesTheRowsHourMinuteAndDays() {
        val monSixThirty = Instant.parse("2026-11-09T11:30:00Z") // Monday 06:30 EST
        val weekly = alarm(6, 30, days = setOf(DayOfWeek.MONDAY))
        assertTrue(fallsOnSchedule(weekly, monSixThirty, newYork))
        assertFalse(fallsOnSchedule(weekly.copy(minute = 31), monSixThirty, newYork))
        assertFalse(fallsOnSchedule(weekly.copy(hour = 7, minute = 15), monSixThirty, newYork))
        assertFalse(fallsOnSchedule(weekly.copy(days = setOf(DayOfWeek.TUESDAY)), monSixThirty, newYork))
        // A one-shot matches its time on any day.
        assertTrue(fallsOnSchedule(alarm(6, 30), monSixThirty, newYork))
        // The same instant in another zone is another local time.
        assertFalse(fallsOnSchedule(alarm(6, 30), monSixThirty, kolkata))
    }

    @Test
    fun fallsOnScheduleIgnoresArmingAndEverySkip() {
        val x = Instant.parse("2026-11-09T11:30:00Z")
        val skippedEverywhere = alarm(
            6, 30, enabled = false, skippedOccurrenceAt = x, soloSkippedOccurrenceAt = x,
            link = link(ownerSkippedOccurrenceAt = x, joinerEnabled = false),
        )
        assertTrue(fallsOnSchedule(skippedEverywhere, x, newYork))
    }

    @Test
    fun fallsOnScheduleAcceptsTheTimeTheSchedulerShiftedOutOfADstGap() {
        // 02:30 doesn't exist on 2026-03-08 in New York; nextOccurrence arms 03:30 EDT.
        val a = alarm(2, 30)
        val shifted = nextOccurrence(a, Instant.parse("2026-03-08T05:00:00Z"), newYork)!!
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), shifted)
        assertTrue(fallsOnSchedule(a, shifted, newYork))
        // A real 03:30 alarm also matches that instant, as the scheduler would arm it there too.
        assertTrue(fallsOnSchedule(alarm(3, 30), shifted, newYork))
    }

    @Test
    fun fallsOnScheduleOnAnOverlapMatchesTheInstantTheSchedulerPicks() {
        // 01:30 happens twice on 2026-11-01 in New York; the scheduler takes the earlier (EDT) one.
        val a = alarm(1, 30)
        val armed = nextOccurrence(a, Instant.parse("2026-11-01T04:00:00Z"), newYork)!!
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), armed)
        assertTrue(fallsOnSchedule(a, armed, newYork))
        assertFalse(fallsOnSchedule(a, Instant.parse("2026-11-01T06:30:00Z"), newYork))
    }
}
