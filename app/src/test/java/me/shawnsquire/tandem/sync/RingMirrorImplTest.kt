package me.shawnsquire.tandem.sync

import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.alarm.MirrorKind
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeCommandSender
import me.shawnsquire.tandem.fakes.FakeCommandSender.Sent
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.RingMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RingMirrorImplTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val hh = Household("h1", "me", "them")
    private val occ = Instant.parse("2026-03-02T06:30:00Z")
    private val until = Instant.parse("2026-03-02T06:39:00Z")
    private val days = setOf(DayOfWeek.MONDAY)

    private fun own(id: String) = Alarm(id, "Mine", 6, 30, days, true, null, null, null)

    private fun linked(id: String, ownerAlarmId: String = "o1", ownerUid: String = "them") = Alarm(
        id, "Linked", 6, 30, days, true, null, null, null,
        link = AlarmLink(ownerUid, ownerAlarmId, LinkMode.RING, null, true, null, null, null, null),
    )

    private fun state(vararg ownerAlarmIds: String) = PartnerState(
        "P", emptyList(), null, null, links = ownerAlarmIds.map { PublishedLink(it, LinkMode.RING) },
    )

    private class Env(
        val sender: FakeCommandSender,
        val household: MutableStateFlow<Household?>,
        val partner: MutableStateFlow<PartnerState?>,
        val removals: LinkRemovals,
        val mirror: RingMirrorImpl,
    )

    private fun TestScope.env(
        rows: List<Alarm>,
        household: Household? = hh,
        partner: PartnerState? = state(),
        repo: ((FakeAlarmRepository) -> AlarmRepository)? = null,
        householdWait: kotlin.time.Duration = 2.minutes,
    ): Env {
        val fake = FakeAlarmRepository(rows)
        val sender = FakeCommandSender()
        val hf = MutableStateFlow(household)
        val pf = MutableStateFlow(partner)
        val removals = LinkRemovals(
            createSyncDataStore(File(tmp.root, "rm-${rows.hashCode()}-${System.nanoTime()}.preferences_pb"), backgroundScope),
        )
        val mirror = RingMirrorImpl(
            repo?.invoke(fake) ?: fake, pf, hf, sender, removals, backgroundScope, householdWait, 10.seconds,
        )
        return Env(sender, hf, pf, removals, mirror)
    }

    @Test fun statusCollectorEndsAfterCommandTtl() = runTest {
        var cancelled = false
        val hangingSender = object : CommandSender {
            override fun send(
                type: CommandType, alarmId: String, occurrenceAt: Instant?, ringId: String?, snoozedUntil: Instant?,
                onCreated: (String) -> Unit,
            ) = kotlinx.coroutines.flow.flow<Pair<me.shawnsquire.tandem.schedule.CommandStatus, String?>> {
                try {
                    emit(me.shawnsquire.tandem.schedule.CommandStatus.PENDING to null)
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val removals = LinkRemovals(createSyncDataStore(File(tmp.root, "ttl.preferences_pb"), backgroundScope))
        val mirror = RingMirrorImpl(
            FakeAlarmRepository(listOf(linked("row1", "o1"))), MutableStateFlow<PartnerState?>(state()),
            MutableStateFlow<Household?>(hh), hangingSender, removals, backgroundScope,
        )
        mirror.onLocal(MirrorKind.DISMISS, listOf(RingMember("row1", occ)), null)
        runCurrent()
        advanceTimeBy(23.hours)
        runCurrent()
        assertTrue(!cancelled)
        advanceTimeBy(2.hours)
        runCurrent()
        assertTrue(cancelled)
    }

    @Test fun joinerSnoozeMapsToOwnerAlarmWithT() = runTest {
        val e = env(listOf(linked("row1", "o1")))
        e.mirror.onLocal(MirrorKind.SNOOZE, listOf(RingMember("row1", occ)), until)
        runCurrent()
        assertEquals(listOf(Sent(CommandType.SNOOZE, "o1", occ, null, until)), e.sender.sent)
    }

    @Test fun joinerDismissSendsStopRinging() = runTest {
        val e = env(listOf(linked("row1", "o1")))
        e.mirror.onLocal(MirrorKind.DISMISS, listOf(RingMember("row1", occ)), null)
        runCurrent()
        assertEquals(listOf(Sent(CommandType.STOP_RINGING, "o1", occ, null, null)), e.sender.sent)
    }

    @Test fun ownerSendsOnlyWhenPartnerLinksIncludeIt() = runTest {
        val e = env(listOf(own("a1")), partner = state("a1"))
        e.mirror.onLocal(MirrorKind.DISMISS, listOf(RingMember("a1", occ)), null)
        runCurrent()
        assertEquals(listOf(Sent(CommandType.STOP_RINGING, "a1", occ, null, null)), e.sender.sent)

        val e2 = env(listOf(own("a1")), partner = state("other"))
        e2.mirror.onLocal(MirrorKind.SNOOZE, listOf(RingMember("a1", occ)), until)
        runCurrent()
        assertTrue(e2.sender.sent.isEmpty())
    }

    @Test fun ownerWithUnknownPartnerStateDoesNotSend() = runTest {
        val e = env(listOf(own("a1")), partner = null)
        e.mirror.onLocal(MirrorKind.DISMISS, listOf(RingMember("a1", occ)), null)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun mergedCallSendsExactlyTwo() = runTest {
        val e = env(listOf(linked("row1", "o1"), own("a1"), own("a2")), partner = state("a1"))
        val members = listOf(RingMember("row1", occ), RingMember("a1", occ), RingMember("a2", occ))
        e.mirror.onLocal(MirrorKind.SNOOZE, members, until)
        runCurrent()
        assertEquals(
            listOf(
                Sent(CommandType.SNOOZE, "o1", occ, null, until),
                Sent(CommandType.SNOOZE, "a1", occ, null, until),
            ),
            e.sender.sent,
        )
    }

    @Test fun pendingRemovalRowIsNotMirrored() = runTest {
        val e = env(listOf(linked("row1", "o1")))
        e.removals.add("row1", RemovalReason.LEFT)
        e.mirror.onLocal(MirrorKind.DISMISS, listOf(RingMember("row1", occ)), null)
        runCurrent()
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun ownerUidMismatchIsNotMirrored() = runTest {
        val e = env(listOf(linked("row1", "o1", ownerUid = "stranger")))
        e.mirror.onLocal(MirrorKind.DISMISS, listOf(RingMember("row1", occ)), null)
        runCurrent()
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun snoozeWithoutTimeIsSkipped() = runTest {
        val e = env(listOf(linked("row1", "o1")))
        e.mirror.onLocal(MirrorKind.SNOOZE, listOf(RingMember("row1", occ)), null)
        runCurrent()
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun missingRowIsSkippedAndLaterMembersStillSend() = runTest {
        val e = env(listOf(linked("row2", "o2")))
        e.mirror.onLocal(MirrorKind.DISMISS, listOf(RingMember("gone", occ), RingMember("row2", occ)), null)
        runCurrent()
        assertEquals(listOf(Sent(CommandType.STOP_RINGING, "o2", occ, null, null)), e.sender.sent)
    }

    @Test fun callsBeforeHouseholdAreHeldAndSentInOrder() = runTest {
        val e = env(listOf(linked("row1", "o1")), household = null)
        e.mirror.onLocal(MirrorKind.SNOOZE, listOf(RingMember("row1", occ)), until)
        e.mirror.onLocal(MirrorKind.DISMISS, listOf(RingMember("row1", occ)), null)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertTrue(e.sender.sent.isEmpty())
        e.household.value = hh
        runCurrent()
        assertEquals(
            listOf(
                Sent(CommandType.SNOOZE, "o1", occ, null, until),
                Sent(CommandType.STOP_RINGING, "o1", occ, null, null),
            ),
            e.sender.sent,
        )
    }

    @Test fun householdTimeoutDropsTheCall() = runTest {
        val e = env(listOf(linked("row1", "o1")), household = null, householdWait = 1.minutes)
        e.mirror.onLocal(MirrorKind.DISMISS, listOf(RingMember("row1", occ)), null)
        advanceTimeBy(61.seconds)
        runCurrent()
        e.household.value = hh
        runCurrent()
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun onLocalReturnsWithoutSuspendingWhenHouseholdUnknown() = runTest {
        val e = env(listOf(linked("row1", "o1")), household = null)
        // Plain (non-suspend) call; no scheduler advancement has happened yet.
        e.mirror.onLocal(MirrorKind.DISMISS, listOf(RingMember("row1", occ)), null)
        assertTrue(e.sender.sent.isEmpty())
    }

    @Test fun oneFailingMemberDoesNotStopLaterOnes() = runTest {
        val e = env(
            listOf(linked("row2", "o2")),
            repo = { fake ->
                object : AlarmRepository by fake {
                    override suspend fun get(id: String): Alarm? =
                        if (id == "bad") throw IllegalStateException("boom") else fake.get(id)
                }
            },
        )
        e.mirror.onLocal(MirrorKind.DISMISS, listOf(RingMember("bad", occ), RingMember("row2", occ)), null)
        e.mirror.onLocal(MirrorKind.DISMISS, listOf(RingMember("row2", occ)), null)
        runCurrent()
        assertEquals(2, e.sender.sent.size)
        assertEquals("o2", e.sender.sent[0].alarmId)
    }
}
