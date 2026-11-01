package me.shawnsquire.tandem.ui.alarms.linked

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth
import me.shawnsquire.tandem.ui.partner.CommandUiStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class LinkedBothStatusTest {
    private val x = Instant.parse("2026-11-03T06:30:00Z")
    private val reason = "nope"

    private fun row(
        enabled: Boolean = true, ownerSkip: Instant? = null, pending: PendingBoth? = null,
    ) = Alarm(
        "j", "Gym", 6, 30, emptySet(), enabled, null, null, null,
        link = AlarmLink("u", "o", LinkMode.RING, ownerSkip, true, pending, null, null, null),
    )

    private fun status(sent: SentBoth, alarm: Alarm) = deriveBothStatus(sent, alarm, reason)

    @Test fun pendingWhileTheRowStillCarriesThatCommand() {
        val pending = PendingBoth(CommandType.SKIP_NEXT, x, "c1")
        assertEquals(CommandUiStatus.Pending, status(SentBoth(CommandType.SKIP_NEXT, x, returned = true), row(pending = pending)))
    }

    @Test fun pendingWhileTheSendIsStillInFlight() {
        assertEquals(CommandUiStatus.Pending, status(SentBoth(CommandType.SKIP_NEXT, x), row()))
    }

    @Test fun skipAppliedOnceOwnerSkipMatches() {
        assertEquals(CommandUiStatus.Applied, status(SentBoth(CommandType.SKIP_NEXT, x, returned = true), row(ownerSkip = x)))
    }

    @Test fun skipRejectedWhenPendingClearedWithoutTheOwnerSkip() {
        assertEquals(CommandUiStatus.Rejected(reason), status(SentBoth(CommandType.SKIP_NEXT, x, returned = true), row()))
        assertEquals(
            CommandUiStatus.Rejected(reason),
            status(SentBoth(CommandType.SKIP_NEXT, x, returned = true), row(ownerSkip = x.plusSeconds(86_400))),
        )
    }

    @Test fun unskipAppliedOnceOwnerSkipIsGone() {
        val sent = SentBoth(CommandType.UNSKIP, x, returned = true)
        assertEquals(CommandUiStatus.Applied, status(sent, row(ownerSkip = null)))
        assertEquals(CommandUiStatus.Rejected(reason), status(sent, row(ownerSkip = x)))
    }

    @Test fun disableAndEnableFollowTheCopiedEnabled() {
        val off = SentBoth(CommandType.DISABLE, x, returned = true)
        assertEquals(CommandUiStatus.Applied, status(off, row(enabled = false)))
        assertEquals(CommandUiStatus.Rejected(reason), status(off, row(enabled = true)))
        val on = SentBoth(CommandType.ENABLE, null, returned = true)
        assertEquals(CommandUiStatus.Applied, status(on, row(enabled = true)))
        assertEquals(CommandUiStatus.Rejected(reason), status(on, row(enabled = false)))
    }

    @Test fun aFailedSendIsRejectedAtOnce() {
        assertEquals(CommandUiStatus.Rejected(reason), status(SentBoth(CommandType.SKIP_NEXT, x, failed = true), row()))
    }

    @Test fun aDifferentPendingCommandDoesNotCountAsOurs() {
        val other = PendingBoth(CommandType.DISABLE, x, "c2")
        assertEquals(CommandUiStatus.Rejected(reason), status(SentBoth(CommandType.SKIP_NEXT, x, returned = true), row(pending = other)))
    }

    @Test fun oneShotSkipIsAppliedOnceTheOwnerTurnedTheAlarmOff() {
        val sent = SentBoth(CommandType.SKIP_NEXT, x, returned = true)
        assertEquals(CommandUiStatus.Applied, status(sent, row(enabled = false)))
        assertEquals(CommandUiStatus.Rejected(reason), status(sent, row(enabled = true)))
    }

    @Test fun repeatingSkipIsNotAppliedByTheAlarmBeingOff() {
        val repeating = row(enabled = false).let { it.copy(days = setOf(java.time.DayOfWeek.MONDAY)) }
        assertEquals(
            CommandUiStatus.Rejected(reason),
            status(SentBoth(CommandType.SKIP_NEXT, x, returned = true), repeating),
        )
    }

    @Test fun aLatchedOutcomeIsReturnedUnchanged() {
        val sent = SentBoth(CommandType.SKIP_NEXT, x, returned = true, outcome = CommandUiStatus.Applied)
        assertEquals(CommandUiStatus.Applied, status(sent, row()))
    }
}
