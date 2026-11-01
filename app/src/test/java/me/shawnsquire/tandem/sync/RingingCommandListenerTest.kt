package me.shawnsquire.tandem.sync

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RingingCommandListenerTest {
    private val hh = Household("h1", "me", "them")
    private val t0 = Instant.parse("2026-03-02T05:00:00Z")
    private val ring = RingState("a1", "r1", t0, null)
    private val remote = FakeCommandRemote()
    private val ringState = MutableStateFlow<RingState?>(null)
    private val household = MutableStateFlow<Household?>(hh)
    private val applied = mutableListOf<Pair<Household, Command>>()
    private var failFor: String? = null

    private fun TestScope.listener() = RingingCommandListener(ringState, household, remote, { h, c ->
        applied += h to c
        if (c.id == failFor) error("boom")
    }, backgroundScope)

    private fun cmd(id: String) = Command(
        id, "them", "me", CommandType.STOP_RINGING, "a1", null, "r1", t0, t0.plusSeconds(600),
    )

    @Test
    fun `no subscription while idle`() = runTest {
        listener().start()
        runCurrent()
        assertEquals(0, remote.pendingSubscriptions)
    }

    @Test
    fun `subscribes while ringing and unsubscribes when the ring ends`() = runTest {
        listener().start()
        ringState.value = ring
        runCurrent()
        assertEquals(1, remote.activePendingSubscriptions)
        ringState.value = null
        runCurrent()
        assertEquals(0, remote.activePendingSubscriptions)
    }

    @Test
    fun `stays attached while snoozed and detaches when the ring ends`() = runTest {
        listener().start()
        ringState.value = ring
        runCurrent()
        ringState.value = ring.copy(snoozedUntil = t0.plusSeconds(540))
        runCurrent()
        assertEquals(1, remote.activePendingSubscriptions)
        ringState.value = ring.copy(snoozedUntil = null, ringId = "r2")
        runCurrent()
        assertEquals(1, remote.activePendingSubscriptions)
        assertEquals(1, remote.pendingSubscriptions)
        ringState.value = ring.copy(snoozedUntil = t0.plusSeconds(540))
        ringState.value = null
        runCurrent()
        assertEquals(0, remote.activePendingSubscriptions)
    }

    @Test
    fun `ring state changes that keep it active do not resubscribe`() = runTest {
        listener().start()
        ringState.value = ring
        runCurrent()
        ringState.value = ring.copy(ringId = "r2")
        runCurrent()
        assertEquals(1, remote.pendingSubscriptions)
    }

    @Test
    fun `start is idempotent`() = runTest {
        val l = listener()
        l.start()
        l.start()
        ringState.value = ring
        runCurrent()
        assertEquals(1, remote.pendingSubscriptions)
    }

    @Test
    fun `pending stop ringing while ringing reaches applyPending`() = runTest {
        listener().start()
        ringState.value = ring
        runCurrent()
        remote.pending.tryEmit(listOf(cmd("c1")))
        runCurrent()
        assertEquals(listOf("c1"), applied.map { it.second.id })
        assertEquals(hh, applied.single().first)
    }

    @Test
    fun `a failing apply does not stop later commands`() = runTest {
        failFor = "c1"
        listener().start()
        ringState.value = ring
        runCurrent()
        remote.pending.tryEmit(listOf(cmd("c1"), cmd("c2")))
        runCurrent()
        remote.pending.tryEmit(listOf(cmd("c3")))
        runCurrent()
        assertEquals(listOf("c1", "c2", "c3"), applied.map { it.second.id })
    }

    @Test
    fun `no subscription while ringing without a household`() = runTest {
        household.value = null
        listener().start()
        ringState.value = ring
        runCurrent()
        assertEquals(0, remote.pendingSubscriptions)
    }

    @Test
    fun `resubscribes after the pending flow completes while ringing`() = runTest {
        remote.pendingCompletes = true
        listener().start()
        ringState.value = ring
        runCurrent()
        assertEquals(1, remote.pendingSubscriptions)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, remote.pendingSubscriptions)
        ringState.value = null
        runCurrent()
        val n = remote.pendingSubscriptions
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(n, remote.pendingSubscriptions)
    }
}
