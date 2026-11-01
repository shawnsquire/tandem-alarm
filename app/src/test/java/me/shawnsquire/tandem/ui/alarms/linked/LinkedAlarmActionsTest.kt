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

    private val rowNext = Instant.parse("2026-11-02T06:30:00Z")
    private val zone = ZoneOffset.UTC

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

    @Test fun skipBothPinsToTheRowsOwnNextOccurrence() = runTest {
        assertTrue(actions.skipBoth(row(), now, zone))
        assertEquals(listOf<Call>(Call.SendBoth("j", CommandType.SKIP_NEXT, rowNext)), links.calls)
    }

    @Test fun skipBothSendsNothingWithoutAnOccurrence() = runTest {
        assertFalse(actions.skipBoth(row(enabled = false), now, zone))
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

    @Test fun bothOffSendsDisablePinnedToTheRowsOwnNextOccurrence() = runTest {
        assertTrue(actions.setBoth(row(), now, zone, on = false))
        assertEquals(listOf<Call>(Call.SendBoth("j", CommandType.DISABLE, rowNext)), links.calls)
    }

    @Test fun bothOffNeedsAnOccurrence() = runTest {
        // A one-shot whose only shared candidate is skipped by the owner has nothing to pin.
        val skipped = row(days = emptySet()).let { it.copy(link = it.link!!.copy(ownerSkippedOccurrenceAt = rowNext)) }
        assertFalse(actions.setBoth(skipped, now, zone, on = false))
        assertTrue(links.calls.isEmpty())
    }

    @Test fun bothOnSendsEnableWithoutAPin() = runTest {
        assertTrue(actions.setBoth(row(enabled = false), now, zone, on = true))
        assertEquals(listOf<Call>(Call.SendBoth("j", CommandType.ENABLE, null)), links.calls)
    }

    @Test fun switchOptionsWhenOn() {
        assertEquals(LinkedSwitchOptions(justMe = true, both = true), linkedSwitchOptions(row(), now, zone))
        val noNext = row(days = emptySet()).let { it.copy(link = it.link!!.copy(ownerSkippedOccurrenceAt = rowNext)) }
        assertEquals(LinkedSwitchOptions(justMe = true, both = false), linkedSwitchOptions(noNext, now, zone))
    }

    @Test fun switchOptionsWhenOffOnlyOfferJustMeIfTheOwnerIsOn() {
        assertEquals(
            LinkedSwitchOptions(justMe = true, both = true),
            linkedSwitchOptions(row(joinerEnabled = false), now, zone),
        )
        assertEquals(
            LinkedSwitchOptions(justMe = false, both = true),
            linkedSwitchOptions(row(enabled = false), now, zone),
        )
    }

    // The pin is the owner's shared view: copied fields and the owner's own shared skip only.

    private val dayAfter = Instant.parse("2026-11-03T06:30:00Z")

    @Test fun bothPinsIgnoreTheJoinersOwnSkipAndPendingCommand() = runTest {
        val mine = row(pending = PendingBoth(CommandType.SKIP_NEXT, rowNext, "c")).copy(skippedOccurrenceAt = rowNext)
        assertTrue(actions.setBoth(mine, now, zone, on = false))
        assertTrue(actions.skipBoth(mine, now, zone))
        assertEquals(
            listOf<Call>(Call.SendBoth("j", CommandType.DISABLE, rowNext), Call.SendBoth("j", CommandType.SKIP_NEXT, rowNext)),
            links.calls,
        )
    }

    @Test fun bothPinsIgnoreAJoinerSoloSkipField() {
        val mine = row().copy(soloSkippedOccurrenceAt = rowNext)
        assertEquals(rowNext, sharedNextOccurrence(mine, now, zone))
    }

    @Test fun bothOffIsPinnableWhileTheJoinerIsOffAndTheOwnerIsOn() = runTest {
        assertTrue(actions.setBoth(row(joinerEnabled = false), now, zone, on = false))
        assertEquals(listOf<Call>(Call.SendBoth("j", CommandType.DISABLE, rowNext)), links.calls)
    }

    @Test fun theOwnersSharedSkipStillMovesThePin() = runTest {
        val owned = row().let { it.copy(link = it.link!!.copy(ownerSkippedOccurrenceAt = rowNext)) }
        assertTrue(actions.skipBoth(owned, now, zone))
        assertEquals(listOf<Call>(Call.SendBoth("j", CommandType.SKIP_NEXT, dayAfter)), links.calls)
    }

    @Test fun switchOptionsStillOfferBothWhenOnlyTheJoinersSkipClearedTheRow() {
        val oneShot = row(days = emptySet()).copy(skippedOccurrenceAt = rowNext)
        assertEquals(LinkedSwitchOptions(justMe = true, both = true), linkedSwitchOptions(oneShot, now, zone))
    }
}
