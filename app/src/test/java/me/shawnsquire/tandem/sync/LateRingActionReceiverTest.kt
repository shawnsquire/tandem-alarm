package me.shawnsquire.tandem.sync

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class LateRingActionReceiverTest {
    private val app: Context = RuntimeEnvironment.getApplication()
    private val at = Instant.parse("2026-03-02T06:30:00Z")
    private val calls = mutableListOf<Triple<String, Instant, Boolean>>()
    private val answer: suspend (String, Instant, Boolean) -> Unit = { id, t, ring -> calls += Triple(id, t, ring) }

    private fun intent(action: String) = Intent(app, LateRingActionReceiver::class.java).setAction(action)
        .putExtra(LateRingActionReceiver.EXTRA_ALARM_ID, "a1")
        .putExtra(LateRingActionReceiver.EXTRA_OCCURRENCE_AT, at.toEpochMilli())

    @Test fun `ring answers true for the extras given`() = runTest {
        handleLateRingAction(intent(LateRingActionReceiver.ACTION_RING), answer)
        assertEquals(listOf(Triple("a1", at, true)), calls)
    }

    @Test fun `skip answers false for the extras given`() = runTest {
        handleLateRingAction(intent(LateRingActionReceiver.ACTION_SKIP), answer)
        assertEquals(listOf(Triple("a1", at, false)), calls)
    }

    @Test fun `missing extras or unknown action do nothing`() = runTest {
        handleLateRingAction(Intent(LateRingActionReceiver.ACTION_RING), answer)
        handleLateRingAction(
            Intent(LateRingActionReceiver.ACTION_RING).putExtra(LateRingActionReceiver.EXTRA_ALARM_ID, "a1"), answer,
        )
        handleLateRingAction(intent("nope"), answer)
        assertTrue(calls.isEmpty())
    }
}
