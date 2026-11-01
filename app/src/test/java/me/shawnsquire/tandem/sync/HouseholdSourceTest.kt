package me.shawnsquire.tandem.sync

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HouseholdSourceTest {
    private val user = mapOf<String, Any?>("householdId" to "h1")
    private val hh = mapOf<String, Any?>("members" to listOf("me", "them"))

    @Test fun `resubscribes after a listener error and emits the household again`() = runTest {
        val opens = mutableListOf<Pair<String, Long>>()
        var userListens = 0
        val docs = { path: String ->
            when (path) {
                "users/me" -> flow {
                    opens += path to testScheduler.currentTime
                    if (++userListens == 1) throw IllegalStateException("denied")
                    emit(user); awaitCancellation()
                }
                else -> flow { opens += path to testScheduler.currentTime; emit(hh); awaitCancellation() }
            }
        }
        val source = HouseholdSource(flowOf("me"), docs, backgroundScope)
        source.household.test {
            assertEquals(null, awaitItem())
            expectNoEvents()
            advanceTimeBy(1_999); runCurrent()
            expectNoEvents()
            advanceTimeBy(2); runCurrent()
            assertEquals(Household("h1", "me", "them"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("users/me" to 0L, "users/me" to 2_000L, "households/h1" to 2_000L), opens)
    }

    @Test fun `a household listener error is retried and recovers`() = runTest {
        var listens = 0
        val docs = { path: String ->
            if (path == "users/me") {
                flow { emit(user); awaitCancellation() }
            } else {
                flow {
                    if (++listens == 1) { emit(hh); throw IllegalStateException("unavailable") }
                    emit(hh); awaitCancellation()
                }
            }
        }
        HouseholdSource(flowOf("me"), docs, backgroundScope).household.test {
            val expected = Household("h1", "me", "them")
            assertEquals(expected, awaitItem())
            assertEquals(null, awaitItem())
            advanceTimeBy(2_001); runCurrent()
            assertEquals(expected, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

}
