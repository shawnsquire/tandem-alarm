package me.shawnsquire.tandem.ui.alarms.linked

import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeLinkRepository
import me.shawnsquire.tandem.fakes.FakeLinkRepository.Call
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth
import me.shawnsquire.tandem.sync.PartnerAlarm
import me.shawnsquire.tandem.ui.alarms.AlarmEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

class LinkedAlarmActionsTest {
    // Monday 2026-11-02 05:00 UTC.
    private val now = Instant.parse("2026-11-02T05:00:00Z")
    private val ownerNext = Instant.parse("2026-11-03T06:30:00Z")

    private fun row(
        enabled: Boolean = true, joinerEnabled: Boolean = true, pending: PendingBoth? = null,
        days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    ) = Alarm(
        "j", "Gym", 6, 30, days, enabled, null, null, null,
        link = AlarmLink("u", "o", LinkMode.RING, null, joinerEnabled, pending, null, null, null),
    )

    private fun owner(next: Instant? = ownerNext) = PartnerAlarm("o", "Gym", 6, 30, emptySet(), true, next, null)

    private val repo = FakeAlarmRepository(emptyList())
    private val scheduler = FakeAlarmScheduler()
    private val links = FakeLinkRepository()
    private val actions = LinkedAlarmActions(links, AlarmEdits(repo, scheduler))

    @Test fun skipJustMeOnRepeatingPinsTheRowsNextOccurrence() = runTest {
        repo.upsert(row())
        actions.skipJustMe(row(), now, ZoneOffset.UTC)
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), repo.get("j")!!.skippedOccurrenceAt)
        assertEquals(1, scheduler.rescheduleCount)
        assertTrue(links.calls.isEmpty())
    }

    @Test fun skipJustMeOnOneShotTurnsItOffForMeOnly() = runTest {
        repo.upsert(row(days = emptySet()))
        actions.skipJustMe(row(days = emptySet()), now, ZoneOffset.UTC)
        assertEquals(listOf<Call>(Call.SetJoinerEnabled("j", false)), links.calls)
        assertEquals(null, repo.get("j")!!.skippedOccurrenceAt)
    }

    @Test fun unskipClearsOnlyMySkip() = runTest {
        repo.upsert(row().copy(skippedOccurrenceAt = ownerNext))
        actions.unskipJustMe("j")
        assertEquals(null, repo.get("j")!!.skippedOccurrenceAt)
        assertEquals(1, scheduler.rescheduleCount)
    }

    @Test fun skipBothPinsToTheOwnersOccurrence() = runTest {
        assertTrue(actions.skipBoth("j", owner()))
        assertEquals(listOf<Call>(Call.SendBoth("j", CommandType.SKIP_NEXT, ownerNext)), links.calls)
    }

    @Test fun skipBothSendsNothingWithoutAPin() = runTest {
        assertFalse(actions.skipBoth("j", owner(next = null)))
        assertFalse(actions.skipBoth("j", null))
        assertTrue(links.calls.isEmpty())
    }

    @Test fun justMeTurnsOffAndOn() = runTest {
        assertTrue(actions.setJustMe(row(), on = false))
        assertTrue(actions.setJustMe(row(joinerEnabled = false), on = true))
        assertEquals(
            listOf<Call>(Call.SetJoinerEnabled("j", false), Call.SetJoinerEnabled("j", true)),
            links.calls,
        )
    }

    @Test fun justMeCannotTurnOnWhileTheOwnerSideIsOff() = runTest {
        assertFalse(actions.setJustMe(row(enabled = false, joinerEnabled = false), on = true))
        assertTrue(links.calls.isEmpty())
    }

    @Test fun justMeTurnsOnWhileAnEnableIsPending() = runTest {
        val pending = PendingBoth(CommandType.ENABLE, null, "c")
        assertTrue(actions.setJustMe(row(enabled = false, joinerEnabled = false, pending = pending), on = true))
        assertEquals(listOf<Call>(Call.SetJoinerEnabled("j", true)), links.calls)
    }

    @Test fun bothOffSendsDisablePinnedToTheOwnersOccurrence() = runTest {
        assertTrue(actions.setBoth(row(), owner(), on = false))
        assertEquals(listOf<Call>(Call.SendBoth("j", CommandType.DISABLE, ownerNext)), links.calls)
    }

    @Test fun bothOffNeedsAPin() = runTest {
        assertFalse(actions.setBoth(row(), owner(next = null), on = false))
        assertFalse(actions.setBoth(row(), null, on = false))
        assertTrue(links.calls.isEmpty())
    }

    @Test fun bothOnSendsEnableWithoutAPin() = runTest {
        assertTrue(actions.setBoth(row(enabled = false), null, on = true))
        assertEquals(listOf<Call>(Call.SendBoth("j", CommandType.ENABLE, null)), links.calls)
    }

    @Test fun switchOptionsWhenOn() {
        assertEquals(LinkedSwitchOptions(justMe = true, both = true), linkedSwitchOptions(row(), owner()))
        assertEquals(LinkedSwitchOptions(justMe = true, both = false), linkedSwitchOptions(row(), owner(next = null)))
    }

    @Test fun switchOptionsWhenOffOnlyOfferJustMeIfTheOwnerIsOn() {
        assertEquals(
            LinkedSwitchOptions(justMe = true, both = true),
            linkedSwitchOptions(row(joinerEnabled = false), owner()),
        )
        assertEquals(
            LinkedSwitchOptions(justMe = false, both = true),
            linkedSwitchOptions(row(enabled = false), owner(next = null)),
        )
    }
}
