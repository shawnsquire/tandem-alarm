package me.shawnsquire.tandem.sync

import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeLinkedMediaCache
import me.shawnsquire.tandem.fakes.FakeRingingController
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.nextOccurrence
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeLinkSync
import me.shawnsquire.tandem.fakes.FakePartnerRepository
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PreFireCheckImplTest {
    private val hh = Household("h1", "me", "them")
    private val remote = FakeCommandRemote()
    private val applied = mutableListOf<Pair<Household, Command>>()
    private var applyFailsFor: String? = null

    private fun check(
        household: kotlinx.coroutines.flow.Flow<Household?> = MutableStateFlow(hh),
        onSuccess: () -> Unit = {},
    ) = PreFireCheckImpl(household, remote, { h, c ->
        applied += h to c
        if (c.id == applyFailsFor) error("boom")
    }, FakePartnerRepository(), FakeLinkSync(), onSuccess)

    private fun cmd(id: String, createdAt: String) = Command(
        id, "them", "me", CommandType.STOP_RINGING, "a1", null, "r1",
        Instant.parse(createdAt), Instant.parse("2026-03-02T07:00:00Z"),
    )

    @Test
    fun `fetch that never completes returns false within the timeout`() = runTest {
        remote.fetchBlock = { awaitCancellation() }
        assertFalse(check().pullAndApply(3.seconds))
        assertTrue(testScheduler.currentTime <= 3_000)
    }

    @Test
    fun `caller timeout above 3 s is capped at 3 s`() = runTest {
        remote.fetchBlock = { awaitCancellation() }
        assertFalse(check().pullAndApply(10.seconds))
        assertEquals(3_000, testScheduler.currentTime)
    }

    @Test
    fun `shorter caller timeout is honored`() = runTest {
        remote.fetchBlock = { awaitCancellation() }
        assertFalse(check().pullAndApply(1.seconds))
        assertEquals(1_000, testScheduler.currentTime)
    }

    @Test
    fun `fetch failure returns false`() = runTest {
        remote.fetchBlock = { throw IllegalStateException("offline") }
        assertFalse(check().pullAndApply(3.seconds))
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `null household returns false without fetching`() = runTest {
        assertFalse(check(MutableStateFlow(null)).pullAndApply(3.seconds))
        assertEquals(0, remote.fetchCalls)
    }

    @Test
    fun `household that never emits returns false within the timeout`() = runTest {
        assertFalse(check(MutableSharedFlow()).pullAndApply(10.seconds))
        assertEquals(3_000, testScheduler.currentTime)
        assertEquals(0, remote.fetchCalls)
    }

    @Test
    fun `success applies every pending command in createdAt order`() = runTest {
        remote.fetchResult = listOf(
            cmd("late", "2026-03-02T05:02:00Z"),
            cmd("early", "2026-03-02T05:00:00Z"),
            cmd("mid", "2026-03-02T05:01:00Z"),
        )
        assertTrue(check().pullAndApply(3.seconds))
        assertEquals(listOf("early", "mid", "late"), applied.map { it.second.id })
        assertTrue(applied.all { it.first == hh })
    }

    @Test
    fun `success with nothing pending is true`() = runTest {
        assertTrue(check().pullAndApply(3.seconds))
    }

    @Test
    fun `a failing apply does not stop the rest and the pull still succeeds`() = runTest {
        remote.fetchResult = listOf(cmd("a", "2026-03-02T05:00:00Z"), cmd("b", "2026-03-02T05:01:00Z"))
        applyFailsFor = "a"
        assertTrue(check().pullAndApply(3.seconds))
        assertEquals(listOf("a", "b"), applied.map { it.second.id })
    }

    @Test
    fun `caller cancellation propagates`() = runTest {
        remote.fetchBlock = { awaitCancellation() }
        val done = CompletableDeferred<Boolean>()
        val job = launch {
            try {
                check().pullAndApply(3.seconds)
            } catch (e: kotlinx.coroutines.CancellationException) {
                done.complete(true)
                throw e
            }
        }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertTrue(done.await())
    }

    @Test
    fun `onSuccess runs after a successful pullAndApply`() = runTest {
        var calls = 0
        assertTrue(check(onSuccess = { calls++ }).pullAndApply(3.seconds))
        assertEquals(1, calls)
    }

    @Test
    fun `onSuccess does not run after a timed out or failed pull or unpaired`() = runTest {
        var calls = 0
        remote.fetchBlock = { awaitCancellation() }
        assertFalse(check(onSuccess = { calls++ }).pullAndApply(1.seconds))
        remote.fetchBlock = { error("net") }
        assertFalse(check(onSuccess = { calls++ }).pullAndApply(3.seconds))
        assertFalse(check(MutableStateFlow(null), onSuccess = { calls++ }).pullAndApply(3.seconds))
        assertEquals(0, calls)
    }

    @Test
    fun `a throwing onSuccess does not change the result`() = runTest {
        assertTrue(check(onSuccess = { error("boom") }).pullAndApply(3.seconds))
    }

    @Test
    fun `worker pull does not trigger onSuccess`() = runTest {
        var calls = 0
        assertTrue(check(onSuccess = { calls++ }).pull(30.seconds))
        assertEquals(0, calls)
    }

    // --- linked rows (L4) ---

    private class ReadRepo(var read: suspend () -> PartnerState?) : PartnerRepository {
        var calls = 0
        override val partner: Flow<PartnerState?> = MutableStateFlow(null)
        override suspend fun readFromServer(): PartnerState? { calls++; return read() }
    }

    private class RecordingLinkSync(val delegate: LinkSync? = null) : LinkSync {
        val applied = mutableListOf<PartnerState>()
        override fun start() {}
        override suspend fun apply(owner: PartnerState) { applied += owner; delegate?.apply(owner) }
        override suspend fun removeAll() {}
    }

    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)
    private val now = Instant.parse("2026-03-02T05:00:00Z") // Monday
    private val x = Instant.parse("2026-03-02T06:30:00Z") // Monday 06:30
    private val nextWeek = Instant.parse("2026-03-03T06:30:00Z")
    private var storeCount = 0

    @get:Rule
    val tmp = TemporaryFolder()

    private fun linkedRow() = Alarm(
        "row1", "Wake", 6, 30, weekdays, true, null, null, null,
        link = AlarmLink("them", "o1", LinkMode.NOTIFY, null, true, null, null, null, null),
    )

    private fun ownerAlarm(
        hour: Int = 6, minute: Int = 30, enabled: Boolean = true,
        skip: Instant? = null, solo: Instant? = null, next: Instant? = x,
    ) = PartnerAlarm("o1", "L", hour, minute, weekdays, enabled, next, skip, null, solo)

    private fun ownerState(vararg a: PartnerAlarm) = PartnerState("Shawn", a.toList(), null, null)

    private class Linked(val check: PreFireCheckImpl, val alarms: FakeAlarmRepository, val sync: RecordingLinkSync, val repo: ReadRepo)

    private fun TestScope.linked(read: suspend () -> PartnerState?): Linked {
        val alarms = FakeAlarmRepository(listOf(linkedRow()))
        val partner = MutableStateFlow<PartnerState?>(null)
        val real = LinkSyncImpl(
            partner, MutableStateFlow(hh), alarms, FakeRingingController(), MutableStateFlow(null),
            FakeAlarmScheduler(), FakeLinkedMediaCache(), FakeCommandNotifier(),
            LinkRemovals(createSyncDataStore(File(tmp.root, "s${storeCount++}.preferences_pb"), backgroundScope)),
            Clock.fixed(now, ZoneOffset.UTC), backgroundScope, unpairSettle = 30.seconds,
        )
        val sync = RecordingLinkSync(real)
        val repo = ReadRepo(read)
        return Linked(PreFireCheckImpl(MutableStateFlow(hh), remote, { _, _ -> }, repo, sync), alarms, sync, repo)
    }

    private suspend fun Linked.row() = alarms.get("row1")
    private suspend fun Linked.nextAt(): Instant? = row()?.let { nextOccurrence(it, now, ZoneOffset.UTC) }

    @Test
    fun `linked success with a shared skip suppresses the ring at X`() = runTest {
        val l = linked({ ownerState(ownerAlarm(skip = x)) })
        assertEquals(x, l.nextAt())
        assertTrue(l.check.pullAndApply(3.seconds, "o1"))
        assertEquals(x, l.row()!!.link!!.ownerSkippedOccurrenceAt)
        assertEquals(nextWeek, l.nextAt())
    }

    @Test
    fun `linked success with owner off disables the row`() = runTest {
        val l = linked({ ownerState(ownerAlarm(enabled = false)) })
        assertTrue(l.check.pullAndApply(3.seconds, "o1"))
        assertFalse(l.row()!!.enabled)
        assertNull(l.nextAt())
    }

    @Test
    fun `linked success with owner alarm deleted removes the row`() = runTest {
        val l = linked({ ownerState() })
        assertTrue(l.check.pullAndApply(3.seconds, "o1"))
        assertNull(l.row())
    }

    @Test
    fun `linked success with the time moved changes the row so X no longer rings`() = runTest {
        val l = linked({ ownerState(ownerAlarm(hour = 7, minute = 15)) })
        assertTrue(l.check.pullAndApply(3.seconds, "o1"))
        assertEquals(7, l.row()!!.hour)
        assertEquals(15, l.row()!!.minute)
        assertEquals(Instant.parse("2026-03-02T07:15:00Z"), l.nextAt())
    }

    @Test
    fun `owner's solo skip does not suppress the joiner`() = runTest {
        // The owner's published nextOccurrenceAt is already past X because of the solo skip.
        val l = linked({ ownerState(ownerAlarm(solo = x, next = nextWeek)) })
        assertTrue(l.check.pullAndApply(3.seconds, "o1"))
        assertNull(l.row()!!.link!!.ownerSkippedOccurrenceAt)
        assertEquals(x, l.nextAt())
    }

    @Test
    fun `a hanging owner read rings within the cap and applies nothing`() = runTest {
        val l = linked({ awaitCancellation() })
        assertFalse(l.check.pullAndApply(10.seconds, "o1"))
        assertEquals(3_000, testScheduler.currentTime)
        assertTrue(l.sync.applied.isEmpty())
        assertEquals(x, l.nextAt())
    }

    @Test
    fun `a failed owner read returns false and applies nothing`() = runTest {
        var onSuccess = 0
        val l = linked({ null })
        val check = PreFireCheckImpl(MutableStateFlow(hh), remote, { _, _ -> }, l.repo, l.sync) { onSuccess++ }
        assertFalse(check.pullAndApply(3.seconds, "o1"))
        assertTrue(l.sync.applied.isEmpty())
        assertEquals(0, onSuccess)
        assertEquals(x, l.nextAt())
    }

    @Test
    fun `a failed command pull still applies a successful owner read but returns false`() = runTest {
        remote.fetchBlock = { error("offline") }
        val l = linked({ ownerState(ownerAlarm(skip = x)) })
        assertFalse(l.check.pullAndApply(3.seconds, "o1"))
        assertEquals(1, l.sync.applied.size)
        assertEquals(nextWeek, l.nextAt())
    }

    @Test
    fun `pull and owner read run concurrently under one cap`() = runTest {
        remote.fetchBlock = { delay(2_000); emptyList() }
        val l = linked({ delay(2_000); ownerState(ownerAlarm(skip = x)) })
        assertTrue(l.check.pullAndApply(3.seconds, "o1"))
        assertEquals(2_000, testScheduler.currentTime)
        assertEquals(nextWeek, l.nextAt())
    }

    @Test
    fun `onSuccess runs for a linked row only when pull, read and apply all succeeded`() = runTest {
        var calls = 0
        val l = linked({ ownerState(ownerAlarm()) })
        val check = PreFireCheckImpl(MutableStateFlow(hh), remote, { _, _ -> }, l.repo, l.sync) { calls++ }
        assertTrue(check.pullAndApply(3.seconds, "o1"))
        assertEquals(1, calls)
    }

    @Test
    fun `without a linked owner alarm the owner doc is never read`() = runTest {
        val l = linked({ error("unused") })
        assertTrue(l.check.pullAndApply(3.seconds))
        assertEquals(0, l.repo.calls)
        assertTrue(l.sync.applied.isEmpty())
    }
}
