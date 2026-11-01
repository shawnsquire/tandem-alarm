package me.shawnsquire.tandem.sync

import app.cash.turbine.test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandSenderImplTest {
    private val now = Instant.parse("2026-03-02T05:00:00Z")
    private val occurrence = Instant.parse("2026-03-02T06:30:00Z")
    private val hh = Household("h1", "me", "them")
    private val remote = FakeCommandRemote()
    private val household = MutableStateFlow<Household?>(hh)
    private val sender = CommandSenderImpl(remote, household, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `creates the command once with the callers pins`() {
        sender.send(CommandType.SKIP_NEXT, "a1", occurrence, null)
        val c = remote.created.single()
        assertEquals(
            FakeCommandRemote.Created("h1", "me", "them", CommandType.SKIP_NEXT, "a1", occurrence, null, now),
            c,
        )
    }

    @Test
    fun `snoozedUntil reaches the remote`() {
        val until = now.plusSeconds(540)
        sender.send(CommandType.SNOOZE, "a1", null, "r1", until)
        assertEquals(until, remote.created.single().snoozedUntil)
        sender.send(CommandType.SKIP_NEXT, "a1", occurrence, null)
        assertEquals(null, remote.created.last().snoozedUntil)
    }

    @Test
    fun `stop ringing carries the ring id`() {
        sender.send(CommandType.STOP_RINGING, "a1", null, "r1")
        val c = remote.created.single()
        assertEquals("r1", c.ringId)
        assertEquals(null, c.occurrenceAt)
    }

    @Test
    fun `creating does not need a collector and collecting twice does not resend`() = runTest {
        val flow = sender.send(CommandType.SKIP_NEXT, "a1", occurrence, null)
        assertEquals(1, remote.created.size)
        remote.updatesFor("c1").tryEmit(CommandStatus.APPLIED to null)
        flow.toList()
        flow.toList()
        assertEquals(1, remote.created.size)
    }

    @Test
    fun `emits pending then applied then completes`() = runTest {
        val flow = sender.send(CommandType.SKIP_NEXT, "a1", occurrence, null)
        flow.test {
            assertEquals(CommandStatus.PENDING to null, awaitItem())
            remote.updatesFor("c1").emit(CommandStatus.PENDING to null)
            remote.updatesFor("c1").emit(CommandStatus.APPLIED to null)
            assertEquals(CommandStatus.APPLIED to null, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `rejected carries the reason`() = runTest {
        val flow = sender.send(CommandType.SKIP_NEXT, "a1", occurrence, null)
        flow.test {
            assertEquals(CommandStatus.PENDING to null, awaitItem())
            remote.updatesFor("c1").emit(CommandStatus.REJECTED to "stale")
            assertEquals(CommandStatus.REJECTED to "stale", awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `unpaired is rejected and creates nothing`() = runTest {
        household.value = null
        val result = sender.send(CommandType.SKIP_NEXT, "a1", occurrence, null).toList()
        assertEquals(listOf(CommandStatus.REJECTED to "not paired"), result)
        assertTrue(remote.created.isEmpty())
    }

    @Test
    fun `a failing create is rejected`() = runTest {
        remote.createFailure = IllegalStateException("boom")
        val result = sender.send(CommandType.SKIP_NEXT, "a1", occurrence, null).toList()
        assertEquals(listOf(CommandStatus.REJECTED to "couldn't send"), result)
    }
}
