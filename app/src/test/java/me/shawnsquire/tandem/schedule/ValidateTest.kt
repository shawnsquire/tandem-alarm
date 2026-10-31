package me.shawnsquire.tandem.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidateTest {
    private val newYork = ZoneId.of("America/New_York")
    private val allDays = DayOfWeek.entries.toSet()

    // Saturday 2026-11-07 07:00 EST
    private val now = Instant.parse("2026-11-07T12:00:00Z")
    private val todayRing = Instant.parse("2026-11-07T13:00:00Z")
    private val tomorrowRing = Instant.parse("2026-11-08T13:00:00Z")
    private val dayAfterRing = Instant.parse("2026-11-09T13:00:00Z")
    private val yesterdayRing = Instant.parse("2026-11-06T13:00:00Z")

    private val apply = CommandDecision.Apply

    private fun reject(reason: String) = CommandDecision.Reject(reason)

    private fun alarm(
        enabled: Boolean = true,
        skippedOccurrenceAt: Instant? = null,
    ) = Alarm(
        id = "a1",
        label = "",
        hour = 8,
        minute = 0,
        days = allDays,
        enabled = enabled,
        skippedOccurrenceAt = skippedOccurrenceAt,
        ringtoneUri = null,
        wallpaperPath = null,
    )

    private fun ringState(alarmId: String = "a1", ringId: String = "r1") = RingState(
        alarmId = alarmId,
        ringId = ringId,
        startedAt = now,
        snoozedUntil = null,
    )

    private fun cmd(
        type: CommandType,
        occurrenceAt: Instant? = null,
        ringId: String? = null,
        expiresAt: Instant = now.plusSeconds(3600),
    ) = Command(
        id = "c1",
        from = "partner",
        to = "owner",
        type = type,
        alarmId = "a1",
        occurrenceAt = occurrenceAt,
        ringId = ringId,
        createdAt = now.minusSeconds(60),
        expiresAt = expiresAt,
    )

    private fun check(cmd: Command, alarm: Alarm?, ring: RingState? = null) =
        validate(cmd, alarm, ring, now, newYork)

    private fun checkAt(at: Instant, cmd: Command, alarm: Alarm?, ring: RingState? = null) =
        validate(cmd.copy(expiresAt = at.plusSeconds(3600)), alarm, ring, at, newYork)

    private val afterRing = todayRing.plusSeconds(1)
    private val laterRing = todayRing.plus(3, ChronoUnit.MINUTES)
    private fun oneShot(enabled: Boolean = true) = alarm(enabled = enabled).copy(days = emptySet())

    @Test
    fun expiredAtExactlyExpiresAtRejects() {
        val c = cmd(CommandType.ENABLE, expiresAt = now)
        assertEquals(reject("expired"), check(c, alarm()))
    }

    @Test
    fun oneInstantBeforeExpiryApplies() {
        val c = cmd(CommandType.ENABLE, expiresAt = now.plusMillis(1))
        assertEquals(apply, check(c, alarm()))
    }

    @Test
    fun expiredWinsOverMissingAlarm() {
        val c = cmd(CommandType.ENABLE, expiresAt = now.minusSeconds(1))
        assertEquals(reject("expired"), check(c, null))
    }

    @Test
    fun missingAlarmRejects() {
        assertEquals(reject("alarm not found"), check(cmd(CommandType.ENABLE), null))
    }

    @Test
    fun missingAlarmRejectsStopRingingEvenWithRing() {
        val c = cmd(CommandType.STOP_RINGING, ringId = "r1")
        assertEquals(reject("alarm not found"), check(c, null, ringState()))
    }

    @Test
    fun skipNextMatchingNextOccurrenceApplies() {
        assertEquals(apply, check(cmd(CommandType.SKIP_NEXT, todayRing), alarm()))
    }

    @Test
    fun skipNextStaleOccurrenceRejects() {
        val c = cmd(CommandType.SKIP_NEXT, yesterdayRing)
        assertEquals(reject("already rang or changed"), check(c, alarm()))
    }

    @Test
    fun skipNextFutureButNotNextRejects() {
        val c = cmd(CommandType.SKIP_NEXT, tomorrowRing)
        assertEquals(reject("already rang or changed"), check(c, alarm()))
    }

    @Test
    fun skipNextNullOccurrenceRejects() {
        assertEquals(reject("already rang or changed"), check(cmd(CommandType.SKIP_NEXT), alarm()))
    }

    @Test
    fun skipNextTargetingAlreadySkippedOccurrenceRejectsAsAlreadySkipped() {
        val a = alarm(skippedOccurrenceAt = todayRing)
        val c = cmd(CommandType.SKIP_NEXT, todayRing)
        assertEquals(reject("already skipped"), check(c, a))
    }

    @Test
    fun skipNextOnOccurrenceAfterActiveSkipRejects() {
        // Mon skipped; partner sees Tue as next and taps Skip again. Must not replace the skip.
        val a = alarm(skippedOccurrenceAt = todayRing)
        val c = cmd(CommandType.SKIP_NEXT, tomorrowRing)
        assertEquals(reject("already skipped"), check(c, a))
    }

    @Test
    fun skipNextWithExpiredSkipApplies() {
        val a = alarm(skippedOccurrenceAt = yesterdayRing)
        assertEquals(apply, check(cmd(CommandType.SKIP_NEXT, todayRing), a))
    }

    @Test
    fun skipNextWithSkipExactlyNowRejectsAsAlreadySkippedWithinGrace() {
        val a = alarm(skippedOccurrenceAt = now)
        assertEquals(reject("already skipped"), check(cmd(CommandType.SKIP_NEXT, tomorrowRing), a))
    }

    @Test
    fun skipNextOnDisabledAlarmRejects() {
        val c = cmd(CommandType.SKIP_NEXT, todayRing)
        assertEquals(reject("already rang or changed"), check(c, alarm(enabled = false)))
    }

    @Test
    fun unskipMatchingFutureSkipApplies() {
        val a = alarm(skippedOccurrenceAt = todayRing)
        assertEquals(apply, check(cmd(CommandType.UNSKIP, todayRing), a))
    }

    @Test
    fun unskipWithoutSkipRejects() {
        assertEquals(reject("nothing to unskip"), check(cmd(CommandType.UNSKIP, todayRing), alarm()))
    }

    @Test
    fun unskipPastSkipRejects() {
        val a = alarm(skippedOccurrenceAt = yesterdayRing)
        assertEquals(reject("nothing to unskip"), check(cmd(CommandType.UNSKIP, yesterdayRing), a))
    }

    @Test
    fun unskipSkipExactlyNowRejects() {
        val a = alarm(skippedOccurrenceAt = now)
        assertEquals(reject("nothing to unskip"), check(cmd(CommandType.UNSKIP, now), a))
    }

    @Test
    fun unskipMismatchedOccurrenceRejects() {
        val a = alarm(skippedOccurrenceAt = todayRing)
        assertEquals(reject("nothing to unskip"), check(cmd(CommandType.UNSKIP, tomorrowRing), a))
    }

    @Test
    fun unskipNullOccurrenceRejects() {
        val a = alarm(skippedOccurrenceAt = todayRing)
        assertEquals(reject("nothing to unskip"), check(cmd(CommandType.UNSKIP), a))
    }

    @Test
    fun disableMatchingNextOccurrenceApplies() {
        assertEquals(apply, check(cmd(CommandType.DISABLE, todayRing), alarm()))
    }

    @Test
    fun disableOnDisabledAlarmRejects() {
        val c = cmd(CommandType.DISABLE, todayRing)
        assertEquals(reject("already off"), check(c, alarm(enabled = false)))
    }

    @Test
    fun disableStaleOccurrenceRejects() {
        val c = cmd(CommandType.DISABLE, yesterdayRing)
        assertEquals(reject("already rang or changed"), check(c, alarm()))
    }

    @Test
    fun disableNullOccurrenceRejects() {
        assertEquals(reject("already rang or changed"), check(cmd(CommandType.DISABLE), alarm()))
    }

    @Test
    fun disableAgainstSkippedAlarmUsesOccurrenceAfterSkip() {
        val a = alarm(skippedOccurrenceAt = todayRing)
        assertEquals(reject("already rang or changed"), check(cmd(CommandType.DISABLE, todayRing), a))
        assertEquals(apply, check(cmd(CommandType.DISABLE, tomorrowRing), a))
    }

    @Test
    fun disableWithNoNextOccurrenceRejects() {
        val oneShot = alarm().copy(days = emptySet(), skippedOccurrenceAt = todayRing)
        assertEquals(reject("already rang or changed"), check(cmd(CommandType.DISABLE, todayRing), oneShot))
    }

    @Test
    fun enableOnEnabledAlarmApplies() {
        assertEquals(apply, check(cmd(CommandType.ENABLE), alarm()))
    }

    @Test
    fun enableOnDisabledAlarmApplies() {
        assertEquals(apply, check(cmd(CommandType.ENABLE), alarm(enabled = false)))
    }

    @Test
    fun stopRingingMatchingRingApplies() {
        val c = cmd(CommandType.STOP_RINGING, ringId = "r1")
        assertEquals(apply, check(c, alarm(), ringState()))
    }

    @Test
    fun stopRingingWithNoRingRejects() {
        val c = cmd(CommandType.STOP_RINGING, ringId = "r1")
        assertEquals(reject("not ringing"), check(c, alarm(), null))
    }

    @Test
    fun stopRingingWrongAlarmIdRejects() {
        val c = cmd(CommandType.STOP_RINGING, ringId = "r1")
        assertEquals(reject("not ringing"), check(c, alarm(), ringState(alarmId = "other")))
    }

    @Test
    fun stopRingingWrongRingIdRejects() {
        val c = cmd(CommandType.STOP_RINGING, ringId = "r1")
        assertEquals(reject("not ringing"), check(c, alarm(), ringState(ringId = "r2")))
    }

    @Test
    fun stopRingingNullRingIdRejects() {
        val c = cmd(CommandType.STOP_RINGING)
        assertEquals(reject("not ringing"), check(c, alarm(), ringState()))
    }

    @Test
    fun skipNextPinnedToFiredOccurrenceStillAppliesJustAfterFire() {
        assertEquals(apply, checkAt(afterRing, cmd(CommandType.SKIP_NEXT, todayRing), alarm()))
    }

    @Test
    fun skipNextPinnedToFiredOccurrenceRejectsAfterGrace() {
        assertEquals(
            reject("already rang or changed"),
            checkAt(laterRing, cmd(CommandType.SKIP_NEXT, todayRing), alarm()),
        )
    }

    @Test
    fun skipNextPinnedToTomorrowAppliesJustAfterFire() {
        assertEquals(apply, checkAt(afterRing, cmd(CommandType.SKIP_NEXT, tomorrowRing), alarm()))
    }

    @Test
    fun skipNextWhileRingingRejects() {
        val c = cmd(CommandType.SKIP_NEXT, todayRing)
        assertEquals(reject("already ringing"), checkAt(afterRing, c, alarm(), ringState()))
    }

    @Test
    fun skipNextWithOtherAlarmRingingIsNotBlocked() {
        val c = cmd(CommandType.SKIP_NEXT, todayRing)
        assertEquals(apply, checkAt(afterRing, c, alarm(), ringState(alarmId = "other")))
    }

    @Test
    fun disablePinnedToFiredOccurrenceAppliesJustAfterFire() {
        assertEquals(apply, checkAt(afterRing, cmd(CommandType.DISABLE, todayRing), alarm()))
    }

    @Test
    fun disablePinnedToFiredOccurrenceRejectsAfterGrace() {
        assertEquals(
            reject("already rang or changed"),
            checkAt(laterRing, cmd(CommandType.DISABLE, todayRing), alarm()),
        )
    }

    @Test
    fun disableWhileRingingRejects() {
        val c = cmd(CommandType.DISABLE, todayRing)
        assertEquals(reject("already ringing"), checkAt(afterRing, c, alarm(), ringState()))
    }

    @Test
    fun disableOnDisabledWhileRingingRejectsAsAlreadyOff() {
        val c = cmd(CommandType.DISABLE, todayRing)
        assertEquals(reject("already off"), checkAt(afterRing, c, alarm(enabled = false), ringState()))
    }

    @Test
    fun oneShotSkipNextPinnedCorrectlyApplies() {
        assertEquals(apply, check(cmd(CommandType.SKIP_NEXT, todayRing), oneShot()))
        assertEquals(apply, checkAt(afterRing, cmd(CommandType.SKIP_NEXT, todayRing), oneShot()))
    }

    @Test
    fun oneShotSkipNextOnDisabledRejectsAsAlreadyOff() {
        assertEquals(reject("already off"), check(cmd(CommandType.SKIP_NEXT, todayRing), oneShot(enabled = false)))
    }

    @Test
    fun oneShotSkipNextWhileRingingRejects() {
        val c = cmd(CommandType.SKIP_NEXT, todayRing)
        assertEquals(reject("already ringing"), checkAt(afterRing, c, oneShot(), ringState()))
    }

    @Test
    fun oneShotSkipNextStaleRejects() {
        assertEquals(
            reject("already rang or changed"),
            checkAt(laterRing, cmd(CommandType.SKIP_NEXT, todayRing), oneShot()),
        )
        assertEquals(reject("already rang or changed"), check(cmd(CommandType.SKIP_NEXT, tomorrowRing), oneShot()))
    }

    @Test
    fun skipNextAlreadySkippedStillBlocksOneMinuteAfterFire() {
        val a = alarm(skippedOccurrenceAt = todayRing)
        val c = cmd(CommandType.SKIP_NEXT, tomorrowRing)
        assertEquals(reject("already skipped"), checkAt(todayRing.plusSeconds(60), c, a))
    }

    @Test
    fun skipNextNotBlockedByExpiredSkipThreeMinutesAfterFire() {
        val a = alarm(skippedOccurrenceAt = todayRing)
        val c = cmd(CommandType.SKIP_NEXT, tomorrowRing)
        assertEquals(apply, checkAt(laterRing, c, a))
    }

    @Test
    fun unskipAfterSkippedOccurrenceHasFiredRejects() {
        val a = alarm(skippedOccurrenceAt = todayRing)
        assertEquals(reject("nothing to unskip"), checkAt(afterRing, cmd(CommandType.UNSKIP, todayRing), a))
    }

    @Test
    fun isOneShotIsTrueOnlyForEmptyDays() {
        assertTrue(oneShot().isOneShot())
        assertFalse(alarm().isOneShot())
    }
}
