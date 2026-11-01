package me.shawnsquire.tandem.sync

import java.time.Instant
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
}
