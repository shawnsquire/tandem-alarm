package me.shawnsquire.tandem.ui.partner

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.alarm.RingingController
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeLinkRepository
import me.shawnsquire.tandem.fakes.FakeRingingController
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.ParkedSnooze
import me.shawnsquire.tandem.schedule.RingContext
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingOrigin
import me.shawnsquire.tandem.sync.LinkRepository
import me.shawnsquire.tandem.sync.LinkResult
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.sync.CommandSender
import me.shawnsquire.tandem.sync.PairingException
import me.shawnsquire.tandem.sync.PairingRepository
import me.shawnsquire.tandem.sync.PairingStatus
import me.shawnsquire.tandem.sync.PartnerAlarm
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.sync.PartnerState
import me.shawnsquire.tandem.ui.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

private data class Sent(val type: CommandType, val alarmId: String, val occurrenceAt: Instant?, val ringId: String?)

private class RecordingSender(var result: () -> Flow<Pair<CommandStatus, String?>> = { flowOf(CommandStatus.PENDING to null) }) :
    CommandSender {
    val sent = mutableListOf<Sent>()
    override fun send(type: CommandType, alarmId: String, occurrenceAt: Instant?, ringId: String?, snoozedUntil: Instant?): Flow<Pair<CommandStatus, String?>> {
        sent += Sent(type, alarmId, occurrenceAt, ringId)
        return result()
    }
}

private class FakePairing : PairingRepository {
    val statusFlow = MutableStateFlow<PairingStatus>(PairingStatus.Unpaired)
    var createError: Exception? = null
    var joinError: Exception? = null
    var joined: String? = null
    override val status: Flow<PairingStatus> = statusFlow
    override suspend fun createCode(): String { createError?.let { throw it }; return "ABC123" }
    override suspend fun join(code: String) { joinError?.let { throw it }; joined = code }
    override suspend fun unpair() {}
}

private class FakePartnerRepo(initial: PartnerState?) : PartnerRepository {
    val flow = MutableStateFlow(initial)
    override val partner: Flow<PartnerState?> = flow
    override suspend fun readFromServer(): PartnerState? = null
}

class PartnerViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val now = Instant.parse("2026-11-02T05:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val next = Instant.parse("2026-11-02T06:30:00Z")
    private val skipped = Instant.parse("2026-11-03T06:30:00Z")

    private fun alarm(
        id: String, hour: Int = 6, minute: Int = 30, enabled: Boolean = true,
        nextAt: Instant? = next, skip: Instant? = null,
    ) = PartnerAlarm(id, id, hour, minute, setOf(DayOfWeek.MONDAY), enabled, nextAt, skip)

    private fun partner(
        vararg alarms: PartnerAlarm, ringing: RingState? = null, lastSeen: Instant? = now,
    ) = PartnerState("Alex", alarms.toList(), ringing, lastSeen)

    private class Env(
        val pairing: FakePairing, val repo: FakePartnerRepo, val sender: RecordingSender, val vm: PartnerViewModel,
        val alarms: FakeAlarmRepository, val links: FakeLinkRepository, val ringing: FakeRingingController,
    )

    private fun env(
        partner: PartnerState? = null,
        paired: Boolean = true,
        sender: RecordingSender = RecordingSender(),
        localAlarms: List<Alarm> = emptyList(),
        controller: ((FakeRingingController) -> RingingController)? = null,
    ): Env {
        val pairing = FakePairing()
        if (paired) pairing.statusFlow.value = PairingStatus.Paired("h", "Alex")
        val repo = FakePartnerRepo(partner)
        val alarms = FakeAlarmRepository(localAlarms)
        val links = FakeLinkRepository()
        val ringing = FakeRingingController()
        val vm = PartnerViewModel(pairing, repo, sender, alarms, links, controller?.invoke(ringing) ?: ringing, clock, MutableStateFlow(Unit))
        return Env(pairing, repo, sender, vm, alarms, links, ringing)
    }

    // The state flow is lazy; keep a collector alive so it stays current.
    private fun Env.collect(scope: CoroutineScope): () -> PartnerUiState {
        scope.launch(Dispatchers.Unconfined) { vm.state.collect {} }
        return { vm.state.value }
    }

    @Test fun commandsArePinnedToWhatPartnerSaw() = runTest {
        val ring = RingState("r", "ring-1", now, null)
        val e = env(
            partner(
                alarm("a"), alarm("b", skip = skipped), alarm("c", enabled = false, nextAt = null),
                ringing = ring,
            ),
        )
        e.vm.skipNext("a")
        e.vm.unskip("b")
        e.vm.turnOff("a")
        e.vm.turnOn("c")
        e.vm.stopRinging()
        assertEquals(
            listOf(
                Sent(CommandType.SKIP_NEXT, "a", next, null),
                Sent(CommandType.UNSKIP, "b", skipped, null),
                Sent(CommandType.DISABLE, "a", next, null),
                Sent(CommandType.ENABLE, "c", null, null),
                Sent(CommandType.STOP_RINGING, "r", null, "ring-1"),
            ),
            e.sender.sent,
        )
    }

    @Test fun rejectedSurfacesReason() = runTest {
        val sender = RecordingSender { flowOf(CommandStatus.PENDING to null, CommandStatus.REJECTED to "already ringing") }
        val e = env(partner(alarm("a")), sender = sender)
        val state = e.collect(backgroundScope)
        e.vm.skipNext("a")
        assertEquals(CommandUiStatus.Rejected("already ringing"), state().alarms.single().commandStatus)
    }

    @Test fun appliedSurfacesApplied() = runTest {
        val sender = RecordingSender { flowOf(CommandStatus.PENDING to null, CommandStatus.APPLIED to null) }
        val e = env(partner(alarm("a")), sender = sender)
        val state = e.collect(backgroundScope)
        e.vm.turnOff("a")
        assertEquals(CommandUiStatus.Applied, state().alarms.single().commandStatus)
    }

    @Test fun pendingWhileOpenThenUnknownWhenFlowCompletes() = runTest {
        val gate = MutableSharedFlow<Pair<CommandStatus, String?>>()
        val sender = RecordingSender { flow { emit(CommandStatus.PENDING to null); gate.collect { emit(it) } } }
        val e = env(partner(alarm("a")), sender = sender)
        val state = e.collect(backgroundScope)
        e.vm.skipNext("a")
        assertEquals(CommandUiStatus.Pending, state().alarms.single().commandStatus)

        val done = RecordingSender { flowOf(CommandStatus.PENDING to null) }
        val e2 = env(partner(alarm("a")), sender = done)
        val state2 = e2.collect(backgroundScope)
        e2.vm.skipNext("a")
        assertEquals(CommandUiStatus.Unknown, state2().alarms.single().commandStatus)
    }

    @Test fun stopStatusShownOnBanner() = runTest {
        val ring = RingState("a", "ring-1", now, null)
        val sender = RecordingSender { flowOf(CommandStatus.PENDING to null, CommandStatus.APPLIED to null) }
        val e = env(partner(alarm("a"), ringing = ring), sender = sender)
        val state = e.collect(backgroundScope)
        assertNull(state().ringing!!.stopStatus)
        e.vm.stopRinging()
        assertEquals(CommandUiStatus.Applied, state().ringing!!.stopStatus)
    }

    @Test fun snoozeShownAsLocalTime() = runTest {
        val ring = RingState("a", "ring-1", now, Instant.parse("2026-11-02T06:40:00Z"))
        val e = env(partner(alarm("a"), ringing = ring))
        val state = e.collect(backgroundScope)
        assertEquals(6, state().ringing!!.snoozedHour)
        assertEquals(40, state().ringing!!.snoozedMinute)
    }

    @Test fun joinErrorShowsPairingMessage() = runTest {
        val e = env(paired = false)
        val state = e.collect(backgroundScope)
        e.pairing.joinError = PairingException("That code is invalid or has expired.")
        e.vm.onJoinInput(" abc123 ")
        e.vm.join()
        assertEquals("That code is invalid or has expired.", state().error)
        assertFalse(state().joining)
        assertNull(e.pairing.joined)
    }

    @Test fun joinTrimsAndUppercases() = runTest {
        val e = env(paired = false)
        e.collect(backgroundScope)
        e.vm.onJoinInput(" ab12cd ")
        e.vm.join()
        assertEquals("AB12CD", e.pairing.joined)
    }

    @Test fun unexpectedErrorIsGeneric() = runTest {
        val e = env(paired = false)
        val state = e.collect(backgroundScope)
        e.pairing.joinError = IllegalStateException("boom")
        e.vm.onJoinInput("ABC123")
        e.vm.join()
        assertEquals("Something went wrong. Try again.", state().error)
    }

    @Test fun createCodeErrorShowsMessage() = runTest {
        val e = env(paired = false)
        val state = e.collect(backgroundScope)
        e.pairing.createError = PairingException("No connection. Try again when you're online.")
        e.vm.showCode()
        assertEquals("No connection. Try again when you're online.", state().error)
        assertNull(state().code)
        assertFalse(state().codeLoading)
    }

    @Test fun codeStaysUntilPairedThenViewSwitches() = runTest {
        val e = env(paired = false)
        val state = e.collect(backgroundScope)
        e.vm.showCode()
        assertEquals("ABC123", state().code)
        assertFalse(state().paired)
        // A household of one is still unpaired: the code stays.
        e.pairing.statusFlow.value = PairingStatus.Unpaired
        assertEquals("ABC123", state().code)
        e.pairing.statusFlow.value = PairingStatus.Paired("h", "Alex")
        assertTrue(state().paired)
        assertEquals("Alex", state().partnerName)
    }

    @Test fun asleepWarningThresholdIsSixtyMinutes() = runTest {
        val e = env(partner(lastSeen = now.minusSeconds(59 * 60)))
        val state = e.collect(backgroundScope)
        assertFalse(state().asleepWarning)
        assertEquals("59 min ago", state().lastSeenText)
        e.repo.flow.value = partner(lastSeen = now.minusSeconds(61 * 60))
        assertTrue(state().asleepWarning)
        assertEquals("1 h ago", state().lastSeenText)
    }

    @Test fun neverSeenHasNoWarning() = runTest {
        val e = env(partner(lastSeen = null))
        val state = e.collect(backgroundScope)
        assertNull(state().lastSeenText)
        assertFalse(state().asleepWarning)
    }

    @Test fun staleSkipShowsOn() = runTest {
        val e = env(partner(alarm("a", skip = now.minusSeconds(60)), alarm("b", skip = skipped)))
        val state = e.collect(backgroundScope)
        val (a, b) = state().alarms
        assertEquals(AlarmChip.ON, a.chip)
        assertEquals(listOf(PartnerAction.SKIP_NEXT, PartnerAction.TURN_OFF), a.actions)
        assertEquals(AlarmChip.SKIPPED_NEXT, b.chip)
        assertEquals(listOf(PartnerAction.UNSKIP, PartnerAction.TURN_OFF), b.actions)
    }

    @Test fun nullNextOccurrenceHidesPinnedActions() = runTest {
        val e = env(partner(alarm("a", nextAt = null), alarm("off", enabled = false, nextAt = null)))
        val state = e.collect(backgroundScope)
        val (a, off) = state().alarms
        assertTrue(a.actions.isEmpty())
        assertEquals(listOf(PartnerAction.TURN_ON), off.actions)
        e.vm.skipNext("a")
        e.vm.turnOff("a")
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun alarmsSortedByTime() = runTest {
        val e = env(partner(alarm("late", hour = 9), alarm("early", hour = 5)))
        val state = e.collect(backgroundScope)
        assertEquals(listOf("early", "late"), state().alarms.map { it.id })
    }

    @Test fun startsLoadingUntilFirstRealEmission() = runTest {
        val pairing = FakePairing()
        val late = MutableSharedFlow<PairingStatus>()
        val slowPairing = object : PairingRepository by pairing {
            override val status: Flow<PairingStatus> = late
        }
        val vm = PartnerViewModel(
            slowPairing, FakePartnerRepo(partner(alarm("a"))), RecordingSender(), FakeAlarmRepository(emptyList()),
            FakeLinkRepository(), FakeRingingController(), clock, MutableStateFlow(Unit),
        )
        vm.state.test {
            val first = awaitItem()
            assertTrue(first.loading)
            assertFalse(first.paired)
            late.emit(PairingStatus.Paired("h", "Alex"))
            val loaded = expectMostRecentItem()
            assertFalse(loaded.loading)
            assertTrue(loaded.paired)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun unpairedIsNotLoadingOnceKnown() = runTest {
        val e = env(paired = false)
        val state = e.collect(backgroundScope)
        assertFalse(state().loading)
        assertFalse(state().paired)
    }

    @Test fun pendingActionNamesOnlyTheInFlightAction() = runTest {
        val gate = MutableSharedFlow<Pair<CommandStatus, String?>>()
        val sender = RecordingSender { flow { emit(CommandStatus.PENDING to null); gate.collect { emit(it) } } }
        val e = env(partner(alarm("a")), sender = sender)
        val state = e.collect(backgroundScope)
        assertNull(state().alarms.single().pendingAction)
        e.vm.turnOff("a")
        assertEquals(PartnerAction.TURN_OFF, state().alarms.single().pendingAction)
        // A different action replaces the previous command and becomes the pending one.
        e.vm.skipNext("a")
        assertEquals(PartnerAction.SKIP_NEXT, state().alarms.single().pendingAction)
    }

    // Joining and stopping a joined alarm.

    private fun linkedRow(id: String, ownerAlarmId: String) = Alarm(
        id, "", 6, 30, setOf(DayOfWeek.MONDAY), true, null, null, null,
        link = AlarmLink("owner", ownerAlarmId, LinkMode.RING, null, true, null, null, null, null),
    )

    private val ringAt = Instant.parse("2026-11-02T06:30:00Z")
    private val partnerRing = RingState("a", "ring-1", now, null, occurrenceAt = ringAt)

    private fun ctx(
        ring: RingState? = null, merged: List<RingMember> = emptyList(), snoozes: List<ParkedSnooze> = emptyList(),
    ) = RingContext(ring, merged, snoozes, null)

    @Test fun joinedRowFlipsOnceLinkedRowExists() = runTest {
        val e = env(partner(alarm("a"), alarm("b")), localAlarms = listOf(linkedRow("L1", "a")))
        val state = e.collect(backgroundScope)
        assertEquals("L1", state().alarms.first { it.id == "a" }.linkedAlarmId)
        assertNull(state().alarms.first { it.id == "b" }.linkedAlarmId)
    }

    @Test fun joinCallsRepositoryAndDisablesButtonWhileRunning() = runTest {
        val gate = CompletableDeferred<Unit>()
        val e = env(partner(alarm("a")))
        val slow = object : LinkRepository by e.links {
            override suspend fun join(ownerAlarmId: String): LinkResult { gate.await(); return e.links.join(ownerAlarmId) }
        }
        val vm = PartnerViewModel(e.pairing, e.repo, e.sender, e.alarms, slow, e.ringing, clock, MutableStateFlow(Unit))
        backgroundScope.launch(Dispatchers.Unconfined) { vm.state.collect {} }
        vm.joinAlarm("a")
        assertTrue(vm.state.value.alarms.single().joining)
        vm.joinAlarm("a")
        gate.complete(Unit)
        e.alarms.upsert(linkedRow("L1", "a"))
        assertFalse(vm.state.value.alarms.single().joining)
        assertEquals(listOf<FakeLinkRepository.Call>(FakeLinkRepository.Call.Join("a")), e.links.calls)
    }

    @Test fun joinFailureShowsReasonOnRow() = runTest {
        val e = env(partner(alarm("a")))
        e.links.joinResult = LinkResult.Failed("Alex deleted this alarm")
        val state = e.collect(backgroundScope)
        e.vm.joinAlarm("a")
        val row = state().alarms.single()
        assertEquals("Alex deleted this alarm", row.joinError)
        assertFalse(row.joining)
        assertNull(row.linkedAlarmId)
    }

    @Test fun isLocalCopyLiveCoversSlotMergedAndParked() {
        val slot = RingState("L1", "r", now, null, occurrenceAt = ringAt)
        assertTrue(isLocalCopyLive(ctx(ring = slot), "L1", ringAt))
        assertTrue(isLocalCopyLive(ctx(ring = slot.copy(snoozedUntil = ringAt.plusSeconds(60))), "L1", ringAt))
        assertTrue(isLocalCopyLive(ctx(ring = slot.copy(alarmId = "other", merged = listOf(RingMember("L1", ringAt)))), "L1", ringAt))
        assertTrue(isLocalCopyLive(ctx(merged = listOf(RingMember("L1", ringAt))), "L1", ringAt))
        assertTrue(isLocalCopyLive(ctx(snoozes = listOf(ParkedSnooze("L1", ringAt, ringAt.plusSeconds(60)))), "L1", ringAt))
    }

    @Test fun isLocalCopyLiveRejectsOtherAlarmOrOccurrence() {
        val later = ringAt.plusSeconds(86_400)
        val slot = RingState("L1", "r", now, null, occurrenceAt = later)
        assertFalse(isLocalCopyLive(ctx(), "L1", ringAt))
        assertFalse(isLocalCopyLive(ctx(ring = slot), "L1", ringAt))
        assertFalse(isLocalCopyLive(ctx(ring = slot.copy(alarmId = "L2", occurrenceAt = ringAt)), "L1", ringAt))
        assertFalse(isLocalCopyLive(ctx(merged = listOf(RingMember("L1", later))), "L1", ringAt))
        assertFalse(isLocalCopyLive(ctx(snoozes = listOf(ParkedSnooze("L1", null, ringAt))), "L1", ringAt))
        assertFalse(isLocalCopyLive(ctx(snoozes = listOf(ParkedSnooze("L2", ringAt, ringAt))), "L1", ringAt))
    }

    private fun joinedEnv(context: RingContext): Env {
        val e = env(partner(alarm("a"), ringing = partnerRing), localAlarms = listOf(linkedRow("L1", "a")))
        e.ringing.ringContextResult = context
        return e
    }

    @Test fun stopOnLiveSoundingCopyDismissesLocallyAndSendsNothing() = runTest {
        val e = joinedEnv(ctx(ring = RingState("L1", "local", now, null, occurrenceAt = ringAt)))
        val state = e.collect(backgroundScope)
        e.vm.stopRinging()
        assertEquals(listOf(FakeRingingController.OccurrenceCall("L1", ringAt, RingOrigin.LOCAL)), e.ringing.dismissedOccurrences)
        assertTrue(e.sender.sent.isEmpty())
        assertEquals(listOf("L1"), e.ringing.ringContextAlarmIds)
        assertEquals(CommandUiStatus.Applied, state().ringing!!.stopStatus)
    }

    @Test fun stopOnMergedCopyDismissesLocally() = runTest {
        val e = joinedEnv(ctx(ring = RingState("x", "local", now, null, merged = listOf(RingMember("L1", ringAt)))))
        e.vm.stopRinging()
        assertEquals(1, e.ringing.dismissedOccurrences.size)
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun stopOnParkedSnoozeDismissesLocally() = runTest {
        val e = joinedEnv(ctx(snoozes = listOf(ParkedSnooze("L1", ringAt, ringAt.plusSeconds(300)))))
        e.vm.stopRinging()
        assertEquals(1, e.ringing.dismissedOccurrences.size)
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun stopWhenLocalCopyNotLiveSendsCommand() = runTest {
        val e = joinedEnv(ctx())
        e.vm.stopRinging()
        assertTrue(e.ringing.dismissedOccurrences.isEmpty())
        assertEquals(listOf(Sent(CommandType.STOP_RINGING, "a", null, "ring-1")), e.sender.sent)
    }

    @Test fun stopWithoutOccurrenceSendsCommandWithoutReadingContext() = runTest {
        val e = env(
            partner(alarm("a"), ringing = partnerRing.copy(occurrenceAt = null)),
            localAlarms = listOf(linkedRow("L1", "a")),
        )
        e.vm.stopRinging()
        assertTrue(e.ringing.ringContextAlarmIds.isEmpty())
        assertEquals(1, e.sender.sent.size)
    }

    @Test fun stopOnUnjoinedAlarmSendsCommand() = runTest {
        val e = env(partner(alarm("a"), ringing = partnerRing))
        e.vm.stopRinging()
        assertTrue(e.ringing.ringContextAlarmIds.isEmpty())
        assertTrue(e.ringing.dismissedOccurrences.isEmpty())
        assertEquals(listOf(Sent(CommandType.STOP_RINGING, "a", null, "ring-1")), e.sender.sent)
    }

    // Repeat taps and failures.

    private fun joinedEnvWith(
        context: RingContext = ctx(ring = RingState("L1", "local", now, null, occurrenceAt = ringAt)),
        controller: (FakeRingingController) -> RingingController,
    ): Env {
        val e = env(
            partner(alarm("a"), ringing = partnerRing), localAlarms = listOf(linkedRow("L1", "a")),
            controller = controller,
        )
        e.ringing.ringContextResult = context
        return e
    }

    @Test fun doubleTapWhileContextIsReadDismissesOnceAndSendsNothing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val e = joinedEnvWith { fake ->
            object : RingingController by fake {
                override suspend fun ringContext(alarmId: String): RingContext { gate.await(); return fake.ringContext(alarmId) }
            }
        }
        val state = e.collect(backgroundScope)
        e.vm.stopRinging()
        assertEquals(CommandUiStatus.Pending, state().ringing!!.stopStatus)
        e.vm.stopRinging()
        gate.complete(Unit)
        assertEquals(1, e.ringing.dismissedOccurrences.size)
        assertTrue(e.sender.sent.isEmpty())
        assertEquals(CommandUiStatus.Applied, state().ringing!!.stopStatus)
    }

    @Test fun tapAfterLocalDismissSendsNothing() = runTest {
        val e = joinedEnvWith { it }
        val state = e.collect(backgroundScope)
        e.vm.stopRinging()
        // The copy is gone now; a repeat tap must not fall through to a command.
        e.ringing.ringContextResult = ctx()
        e.vm.stopRinging()
        assertEquals(1, e.ringing.dismissedOccurrences.size)
        assertTrue(e.sender.sent.isEmpty())
        assertEquals(CommandUiStatus.Applied, state().ringing!!.stopStatus)
    }

    @Test fun newRingIdStillStopsLocallyAfterAnEarlierDismiss() = runTest {
        val e = joinedEnvWith { it }
        e.collect(backgroundScope)
        e.vm.stopRinging()
        val nextX = ringAt.plusSeconds(86_400)
        e.repo.flow.value = partner(alarm("a"), ringing = RingState("a", "ring-2", now, null, occurrenceAt = nextX))
        e.ringing.ringContextResult = ctx(ring = RingState("L1", "local2", now, null, occurrenceAt = nextX))
        e.vm.stopRinging()
        assertEquals(2, e.ringing.dismissedOccurrences.size)
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun ringContextThrowingShowsUnknown() = runTest {
        val e = joinedEnvWith { fake ->
            object : RingingController by fake {
                override suspend fun ringContext(alarmId: String): RingContext = throw IllegalStateException("boom")
            }
        }
        val state = e.collect(backgroundScope)
        e.vm.stopRinging()
        assertEquals(CommandUiStatus.Unknown, state().ringing!!.stopStatus)
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun dismissThrowingShowsUnknown() = runTest {
        val e = joinedEnvWith { fake ->
            object : RingingController by fake {
                override suspend fun dismissOccurrence(alarmId: String, occurrenceAt: Instant, origin: RingOrigin) =
                    throw IllegalStateException("boom")
            }
        }
        val state = e.collect(backgroundScope)
        e.vm.stopRinging()
        assertEquals(CommandUiStatus.Unknown, state().ringing!!.stopStatus)
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun localDismissIsPendingThenApplied() = runTest {
        val gate = CompletableDeferred<Unit>()
        val e = joinedEnvWith { fake ->
            object : RingingController by fake {
                override suspend fun dismissOccurrence(alarmId: String, occurrenceAt: Instant, origin: RingOrigin) {
                    gate.await()
                    fake.dismissOccurrence(alarmId, occurrenceAt, origin)
                }
            }
        }
        val state = e.collect(backgroundScope)
        e.vm.stopRinging()
        assertEquals(CommandUiStatus.Pending, state().ringing!!.stopStatus)
        gate.complete(Unit)
        assertEquals(CommandUiStatus.Applied, state().ringing!!.stopStatus)
    }

    @Test fun cancellationIsNotReportedAsUnknown() = runTest {
        val e = joinedEnvWith { fake ->
            object : RingingController by fake {
                override suspend fun ringContext(alarmId: String): RingContext = throw CancellationException("cancelled")
            }
        }
        val state = e.collect(backgroundScope)
        e.vm.stopRinging()
        assertEquals(CommandUiStatus.Pending, state().ringing!!.stopStatus)
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun joinStaysJoiningUntilLinkedRowAppears() = runTest {
        val e = env(partner(alarm("a")))
        val state = e.collect(backgroundScope)
        e.vm.joinAlarm("a")
        // join() returned Joined but the linked row has not been emitted yet.
        assertTrue(state().alarms.single().joining)
        assertNull(state().alarms.single().linkedAlarmId)
        e.vm.joinAlarm("a")
        assertEquals(1, e.links.calls.size)
        e.alarms.upsert(linkedRow("L1", "a"))
        val row = state().alarms.single()
        assertFalse(row.joining)
        assertEquals("L1", row.linkedAlarmId)
    }

    @Test fun tapAfterLocalDismissAndRowRemovalSendsNothing() = runTest {
        val e = joinedEnvWith { it }
        e.collect(backgroundScope)
        e.vm.stopRinging()
        e.alarms.delete("L1")
        e.vm.stopRinging()
        assertEquals(1, e.ringing.dismissedOccurrences.size)
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun tapWhileLocalDismissInFlightAndRowRemovedSendsNothing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val e = joinedEnvWith { fake ->
            object : RingingController by fake {
                override suspend fun ringContext(alarmId: String): RingContext { gate.await(); return fake.ringContext(alarmId) }
            }
        }
        e.collect(backgroundScope)
        e.vm.stopRinging()
        e.alarms.delete("L1")
        e.vm.stopRinging()
        gate.complete(Unit)
        assertEquals(1, e.ringing.dismissedOccurrences.size)
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun tapAfterSentStopNeverDismissesLocallyOnceRowAppears() = runTest {
        val e = env(partner(alarm("a"), ringing = partnerRing))
        e.ringing.ringContextResult = ctx(ring = RingState("L1", "local", now, null, occurrenceAt = ringAt))
        e.collect(backgroundScope)
        e.vm.stopRinging()
        e.alarms.upsert(linkedRow("L1", "a"))
        e.vm.stopRinging()
        assertTrue(e.ringing.dismissedOccurrences.isEmpty())
        assertEquals(2, e.sender.sent.size)
    }
}
