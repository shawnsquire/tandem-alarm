package me.shawnsquire.tandem.sync

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeCommandApplier
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FcmCommandHandlerTest {
    private val now = Instant.ofEpochMilli(1_700_000_500_000)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    // Built like firebase/functions/src/commands.ts: epoch-millis strings, optionals omitted.
    private val payload = mapOf(
        "householdId" to "h1", "commandId" to "c1", "fromUid" to "u1", "to" to "u2",
        "type" to "SKIP_NEXT", "alarmId" to "a1", "occurrenceAt" to "1700000060000",
        "createdAt" to "1700000000000", "expiresAt" to "1700086400000", "status" to "pending",
    )

    private val recorded = mutableListOf<Instant>()
    private var fallbacks = 0

    private val hh = Household("h1", "u2", "u1")

    private fun handler(applier: CommandApplier, household: Flow<Household?> = flowOf(hh)) =
        FcmCommandHandler(applier, household, { recorded += it }, clock, { fallbacks++ }, 10.seconds)

    @Test
    fun validPayloadAppliesAndRecords() = runTest {
        val applier = FakeCommandApplier()
        handler(applier).handle(payload)
        assertEquals(
            listOf(
                Command(
                    "c1", "u1", "u2", CommandType.SKIP_NEXT, "a1", Instant.ofEpochMilli(1_700_000_060_000), null,
                    Instant.ofEpochMilli(1_700_000_000_000), Instant.ofEpochMilli(1_700_086_400_000),
                ),
            ),
            applier.applied,
        )
        assertEquals(listOf(now), recorded)
        assertEquals(0, fallbacks)
    }

    @Test
    fun malformedRecordsOnly() = runTest {
        val applier = FakeCommandApplier()
        handler(applier).handle(payload - "commandId")
        assertEquals(listOf(now), recorded)
        assertTrue(applier.applied.isEmpty())
        assertEquals(0, fallbacks)
    }

    @Test
    fun hangingApplierFallsBackAfterBudget() = runTest {
        val h = handler(object : CommandApplier {
            override suspend fun apply(cmd: Command) = awaitCancellation()
        })
        val job = launch { h.handle(payload) }
        runCurrent()
        advanceTimeBy(9.seconds)
        assertEquals(0, fallbacks)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1, fallbacks)
        assertEquals(listOf(now), recorded)
    }

    @Test
    fun throwingApplierFallsBack() = runTest {
        handler(object : CommandApplier {
            override suspend fun apply(cmd: Command) = throw IllegalStateException("boom")
        }).handle(payload)
        assertEquals(1, fallbacks)
    }

    @Test
    fun recorderFailureStillApplies() = runTest {
        val applier = FakeCommandApplier()
        FcmCommandHandler(applier, flowOf(hh), { error("disk") }, clock, { fallbacks++ }, 10.seconds).handle(payload)
        assertEquals(1, applier.applied.size)
        assertFalse(fallbacks > 0)
    }

    @Test
    fun householdMismatchIsDroppedWithoutApplyOrFallback() = runTest {
        val applier = FakeCommandApplier()
        handler(applier).handle(payload + ("householdId" to "other"))
        assertTrue(applier.applied.isEmpty())
        assertEquals(0, fallbacks)
        assertEquals(listOf(now), recorded)
    }

    @Test
    fun missingHouseholdIdInPayloadIsDropped() = runTest {
        val applier = FakeCommandApplier()
        handler(applier).handle(payload - "householdId")
        assertTrue(applier.applied.isEmpty())
        assertEquals(0, fallbacks)
    }

    @Test
    fun unknownHouseholdFallsBackWithoutApply() = runTest {
        val applier = FakeCommandApplier()
        handler(applier, flowOf(null)).handle(payload)
        assertTrue(applier.applied.isEmpty())
        assertEquals(1, fallbacks)
    }

    @Test
    fun householdNeverEmittingFallsBackAfterWait() = runTest {
        val applier = FakeCommandApplier()
        handler(applier, MutableSharedFlow()).handle(payload)
        assertEquals(2_000, testScheduler.currentTime)
        assertTrue(applier.applied.isEmpty())
        assertEquals(1, fallbacks)
    }

    @Test
    fun notVerifiedFallsBack() = runTest {
        handler(object : CommandApplier {
            override suspend fun apply(cmd: Command) = throw CommandNotVerifiedException("status unknown")
        }).handle(payload)
        assertEquals(1, fallbacks)
    }
}
