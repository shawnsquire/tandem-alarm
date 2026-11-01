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
        soloSkippedOccurrenceAt: Instant? = null,
        link: AlarmLink? = null,
        id: String = "a1",
    ) = Alarm(
        id = id,
        label = "",
        hour = 8,
        minute = 0,
        days = allDays,
        enabled = enabled,
        skippedOccurrenceAt = skippedOccurrenceAt,
        ringtoneUri = null,
        wallpaperPath = null,
        link = link,
        soloSkippedOccurrenceAt = soloSkippedOccurrenceAt,
    )

    private fun ringState(
        alarmId: String = "a1",
        ringId: String = "r1",
        occurrenceAt: Instant? = null,
        snoozedUntil: Instant? = null,
    ) = RingState(
        alarmId = alarmId,
        ringId = ringId,
        startedAt = now,
        snoozedUntil = snoozedUntil,
        occurrenceAt = occurrenceAt,
    )

    private fun cmd(
        type: CommandType,
        occurrenceAt: Instant? = null,
        ringId: String? = null,
        expiresAt: Instant = now.plusSeconds(3600),
        snoozedUntil: Instant? = null,
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
        snoozedUntil = snoozedUntil,
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

    private val context = RingContext(
        ring = ringState(),
        merged = emptyList(),
        snoozes = listOf(ParkedSnooze("a1", todayRing, todayRing.plusSeconds(540))),
        lastRang = yesterdayRing,
    )

    @Test
    fun ringContextDoesNotChangeTodaysDecisions() {
        val stop = cmd(CommandType.STOP_RINGING, ringId = "r1")
        val skip = cmd(CommandType.SKIP_NEXT, todayRing)
        for (c in listOf(stop, skip)) {
            for (ring in listOf(null, ringState())) {
                assertEquals(
                    validate(c, alarm(), ring, now, newYork),
                    validate(c, alarm(), ring, now, newYork, context),
                )
            }
        }
    }

    @Test
    fun snoozeAndInviteLinkAreNotSupportedYet() {
        for (type in listOf(CommandType.SNOOZE, CommandType.INVITE_LINK)) {
            val c = cmd(type, todayRing)
            assertEquals(reject("not supported yet"), check(c, alarm()))
            assertEquals(reject("not supported yet"), validate(c, alarm(), null, now, newYork, context))
        }
    }

    @Test
    fun isArmedMirrorsEnabled() {
        assertTrue(isArmed(alarm(enabled = true)))
        assertFalse(isArmed(alarm(enabled = false)))
    }

    // ---- Ring commands by occurrence (RingContext) ----

    private val x = todayRing
    private val t0 = x.plusSeconds(540)

    private fun ctx(
        ring: RingState? = null,
        merged: List<RingMember> = emptyList(),
        snoozes: List<ParkedSnooze> = emptyList(),
        lastRang: Instant? = null,
    ) = RingContext(ring, merged, snoozes, lastRang)

    private fun stop(at: Instant = x) = cmd(CommandType.STOP_RINGING, at)

    private fun snooze(until: Instant = t0, at: Instant = x) =
        cmd(CommandType.SNOOZE, at, snoozedUntil = until)

    private fun run(c: Command, a: Alarm?, context: RingContext?, at: Instant = now) =
        validate(c.copy(expiresAt = at.plusSeconds(3600)), a, null, at, newYork, context)

    private fun linkOf(
        ownerSkip: Instant? = null,
        joinerEnabled: Boolean = true,
        pending: PendingBoth? = null,
    ) = AlarmLink("owner", "a1", LinkMode.RING, ownerSkip, joinerEnabled, pending, null, null, null)

    private fun linked(
        enabled: Boolean = true,
        skippedOccurrenceAt: Instant? = null,
        link: AlarmLink = linkOf(),
    ) = alarm(enabled = enabled, skippedOccurrenceAt = skippedOccurrenceAt, link = link, id = "L1")

    private val skippedHere = reject("skipped here")
    private val resolved = reject("already rang or changed")

    @Test
    fun ownerWithSoloSkipRejectsPartnerRingCommands() {
        val a = alarm(soloSkippedOccurrenceAt = x)
        assertEquals(skippedHere, run(stop(), a, ctx()))
        assertEquals(skippedHere, run(snooze(), a, ctx()))
    }

    @Test
    fun ownerWithSharedSkipRejectsPartnerRingCommands() {
        val a = alarm(skippedOccurrenceAt = x)
        assertEquals(skippedHere, run(stop(), a, ctx()))
        assertEquals(skippedHere, run(snooze(), a, ctx()))
    }

    @Test
    fun joinerWithJustMeSkipRejectsOwnerRingCommands() {
        val a = linked(skippedOccurrenceAt = x)
        assertEquals(skippedHere, run(stop(), a, ctx()))
        assertEquals(skippedHere, run(snooze(), a, ctx()))
    }

    @Test
    fun linkedRowSkippedForOtherReasonsRejects() {
        val pendingSkip = PendingBoth(CommandType.SKIP_NEXT, x, "p1")
        val cases = listOf(
            linked(link = linkOf(joinerEnabled = false)),
            linked(link = linkOf(pending = PendingBoth(CommandType.DISABLE, x, "p1"))),
            linked(link = linkOf(pending = pendingSkip)),
            linked(link = linkOf(ownerSkip = x)),
            linked(enabled = false),
        )
        for (a in cases) {
            assertEquals(skippedHere, run(stop(), a, ctx()))
            assertEquals(skippedHere, run(snooze(), a, ctx()))
        }
    }

    @Test
    fun disabledOwnRowRejectsAsSkippedHere() {
        assertEquals(skippedHere, run(stop(), alarm(enabled = false), ctx()))
    }

    @Test
    fun skippedOccurrenceBeatsLive() {
        val a = alarm(skippedOccurrenceAt = x)
        val live = ctx(ring = ringState(occurrenceAt = x))
        assertEquals(skippedHere, run(stop(), a, live))
        assertEquals(skippedHere, run(snooze(), a, live))
    }

    @Test
    fun lastRangDismissedNotLiveRejectsInsideFireGrace() {
        val at = x.plusSeconds(60)
        val c = ctx(lastRang = x)
        assertEquals(resolved, run(stop(), alarm(), c, at))
        assertEquals(resolved, run(snooze(), alarm(), c, at))
    }

    @Test
    fun lastRangButSnoozedIsStillLive() {
        val at = x.plusSeconds(60)
        val c = ctx(lastRang = x, snoozes = listOf(ParkedSnooze("a1", x, x.plusSeconds(540))))
        assertEquals(apply, run(stop(), alarm(), c, at))
        assertEquals(apply, run(snooze(until = x.plusSeconds(900)), alarm(), c, at))
    }

    @Test
    fun soundingSlotRingApplies() {
        val c = ctx(ring = ringState(occurrenceAt = x))
        assertEquals(apply, run(stop(), alarm(), c))
        assertEquals(apply, run(snooze(), alarm(), c))
    }

    @Test
    fun mergedMemberApplies() {
        val c = ctx(ring = ringState(alarmId = "other", occurrenceAt = x), merged = listOf(RingMember("a1", x)))
        assertEquals(apply, run(stop(), alarm(), c))
        assertEquals(apply, run(snooze(), alarm(), c))
    }

    @Test
    fun parkedSnoozeApplies() {
        val c = ctx(snoozes = listOf(ParkedSnooze("a1", x, x.plusSeconds(60))))
        assertEquals(apply, run(stop(), alarm(), c))
        assertEquals(apply, run(snooze(), alarm(), c))
    }

    @Test
    fun crossingSnoozesSettleOnLaterTime() {
        val c = ctx(ring = ringState(occurrenceAt = x, snoozedUntil = t0))
        assertEquals(apply, run(snooze(until = t0.plusSeconds(1)), alarm(), c))
        assertEquals(reject("already snoozed later"), run(snooze(until = t0), alarm(), c))
        assertEquals(reject("already snoozed later"), run(snooze(until = t0.minusSeconds(1)), alarm(), c))
        assertEquals(apply, run(stop(), alarm(), c))
    }

    @Test
    fun parkedSnoozeEndAlsoGuardsSnooze() {
        val c = ctx(snoozes = listOf(ParkedSnooze("a1", x, t0)))
        assertEquals(reject("already snoozed later"), run(snooze(until = t0), alarm(), c))
        assertEquals(apply, run(snooze(until = t0.plusSeconds(1)), alarm(), c))
    }

    @Test
    fun otherAlarmsLiveStateAtSameOccurrenceIsNotLive() {
        val c = ctx(
            ring = ringState(alarmId = "other", occurrenceAt = x),
            merged = listOf(RingMember("other", x)),
            snoozes = listOf(ParkedSnooze("other", x, t0)),
        )
        assertEquals(resolved, run(stop(), alarm(), c))
        assertEquals(resolved, run(snooze(), alarm(), c))
    }

    @Test
    fun linkedRowMatchesLiveStateByRowIdNotCommandAlarmId() {
        val c = ctx(ring = ringState(alarmId = "L1", occurrenceAt = x))
        assertEquals("a1", stop().alarmId)
        assertEquals(apply, run(stop(), linked(), c))
        assertEquals(apply, run(snooze(), linked(), c))
        // Live under the owner's id does not count for the linked row.
        val wrong = ctx(ring = ringState(alarmId = "a1", occurrenceAt = x))
        assertEquals(resolved, run(stop(), linked(), wrong))
    }

    @Test
    fun earlyOccurrenceWithinFireGraceApplies() {
        for (secondsEarly in listOf(60L, 120L)) {
            val at = x.minusSeconds(secondsEarly)
            assertEquals(apply, run(stop(), alarm(), ctx(), at))
            assertEquals(apply, run(snooze(), alarm(), ctx(), at))
        }
    }

    @Test
    fun earlyOccurrenceBeyondFireGraceRejects() {
        val at = x.minusSeconds(180)
        assertEquals(resolved, run(stop(), alarm(), ctx(), at))
        assertEquals(resolved, run(snooze(), alarm(), ctx(), at))
    }

    @Test
    fun staleOccurrenceRejects() {
        assertEquals(resolved, run(stop(yesterdayRing), alarm(), ctx()))
        assertEquals(resolved, run(snooze(at = yesterdayRing), alarm(), ctx()))
    }

    @Test
    fun stopRingingWithRingIdAndContextKeepsTodaysRule() {
        val live = ctx(ring = ringState(occurrenceAt = x))
        val c = cmd(CommandType.STOP_RINGING, ringId = "r1")
        assertEquals(apply, validate(c, alarm(), ringState(), now, newYork, live))
        assertEquals(reject("not ringing"), validate(c, alarm(), ringState(ringId = "r2"), now, newYork, live))
        assertEquals(reject("not ringing"), validate(c, alarm(), null, now, newYork, live))
    }

    @Test
    fun stopRingingWithBothUsesOccurrencePath() {
        val c = cmd(CommandType.STOP_RINGING, x, ringId = "r1")
        assertEquals(resolved, validate(c, alarm(), ringState(), now.minusSeconds(3600 * 5), newYork, ctx()))
    }

    @Test
    fun snoozeWithoutContextIsNotSupported() {
        assertEquals(reject("not supported yet"), check(snooze(), alarm()))
    }

    @Test
    fun malformedSnoozeRejectsCleanly() {
        val live = ctx(ring = ringState(occurrenceAt = x))
        assertEquals(reject("not supported yet"), run(cmd(CommandType.SNOOZE, x), alarm(), live))
        assertEquals(reject("not supported yet"), run(cmd(CommandType.SNOOZE, snoozedUntil = t0), alarm(), live))
    }
}
