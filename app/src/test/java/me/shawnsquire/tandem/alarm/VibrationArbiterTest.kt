package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.VibrationPattern.ESCALATING
import me.shawnsquire.tandem.schedule.VibrationPattern.HEARTBEAT
import me.shawnsquire.tandem.schedule.VibrationPattern.PULSE
import me.shawnsquire.tandem.schedule.VibrationPattern.RAPID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VibrationArbiterTest {
    /** Each vibrate as (pattern, repeating). */
    private val vibrated = mutableListOf<Pair<VibrationPattern, Boolean>>()
    private var cancels = 0
    private var hasVibrator = true
    private var failNext = false
    /** Patterns whose waveform the device rejects, every time. */
    private var failing = emptySet<VibrationPattern>()
    private val arbiter = VibrationArbiter(
        hasVibrator = { hasVibrator },
        vibrate = { p, repeating ->
            if (failNext || p in failing) { failNext = false; throw IllegalArgumentException("waveform") }
            vibrated += p to repeating
        },
        cancelVibration = { cancels++ },
    )
    private val starts get() = vibrated.size

    @Test fun staleOwnerStopDoesNotCancelNewOwner() {
        val a = Any()
        val b = Any()
        arbiter.start(a, PULSE)
        arbiter.start(b, PULSE)

        arbiter.stop(a)
        assertEquals(0, cancels)

        arbiter.stop(b)
        assertEquals(1, cancels)
        assertEquals(1, starts) // same pattern: never re-vibrated
    }

    @Test fun vibrationStopsOnlyWhenTheLastOwnerStops() {
        val a = Any()
        val b = Any()
        arbiter.start(a, PULSE)
        arbiter.start(b, PULSE) // a merging session vibrates before it learns it plays nothing

        arbiter.stop(b)
        assertEquals(0, cancels)

        arbiter.stop(a)
        assertEquals(1, cancels)
    }

    @Test fun stopOfAnOwnerThatNeverStartedIsANoOp() {
        val a = Any()
        arbiter.start(a, PULSE)
        arbiter.stop(Any())
        assertEquals(0, cancels)
    }

    @Test fun stopIsIdempotent() {
        val a = Any()
        arbiter.start(a, PULSE)
        arbiter.stop(a)
        arbiter.stop(a)
        assertEquals(1, cancels)
    }

    @Test fun vibratesWithTheOwnersPatternOnALoop() {
        assertTrue(arbiter.start(Any(), HEARTBEAT))
        assertEquals(listOf(HEARTBEAT to true), vibrated)
    }

    @Test fun aSecondOwnerDoesNotChangeThePlayingPattern() {
        val primary = Any()
        val merging = Any()
        arbiter.start(primary, HEARTBEAT)
        // A merging fire's early vibration: the earliest owner's pattern keeps playing.
        assertTrue(arbiter.start(merging, RAPID))
        assertEquals(listOf(HEARTBEAT to true), vibrated)
        // Its finish() neither cancels nor changes the primary's vibration.
        arbiter.stop(merging)
        assertEquals(listOf(HEARTBEAT to true), vibrated)
        assertEquals(0, cancels)
    }

    @Test fun whenTheEarliestOwnerStopsTheNextOwnersPatternPlays() {
        val stale = Any()
        val current = Any()
        arbiter.start(stale, PULSE)
        arbiter.start(current, ESCALATING)
        arbiter.stop(stale) // a superseded session stopping late
        assertEquals(listOf(PULSE to true, ESCALATING to true), vibrated)
        assertEquals(0, cancels)
    }

    @Test fun anOwnerChangingItsPatternRevibratesOnlyWhenItPlays() {
        val a = Any()
        val b = Any()
        arbiter.start(a, PULSE)
        arbiter.start(b, RAPID)
        arbiter.start(b, HEARTBEAT) // not the earliest: nothing to play
        arbiter.start(a, PULSE) // same pattern: no re-vibrate
        assertEquals(listOf(PULSE to true), vibrated)
        arbiter.start(a, RAPID) // the merged rule changed the earliest owner's pattern
        assertEquals(listOf(PULSE to true, RAPID to true), vibrated)
    }

    @Test fun noVibratorStartsNothingAndSaysSo() {
        hasVibrator = false
        val a = Any()
        assertFalse(arbiter.start(a, PULSE))
        assertTrue(vibrated.isEmpty())
        arbiter.stop(a)
        assertEquals(0, cancels)
    }

    @Test fun aStartThatThrowsPropagatesAndTheNextStartTriesAgain() {
        val a = Any()
        failNext = true
        assertThrows(IllegalArgumentException::class.java) { arbiter.start(a, ESCALATING) }
        assertTrue(vibrated.isEmpty())
        // Not taken as playing: the same pattern is tried again, and the owner's stop still cancels.
        assertTrue(arbiter.start(a, ESCALATING))
        assertEquals(listOf(ESCALATING to true), vibrated)
        arbiter.stop(a)
        assertEquals(1, cancels)
    }

    @Test fun aPreviewPlaysOnceOnlyWhileNoRingVibrates() {
        assertTrue(arbiter.preview(RAPID))
        assertEquals(listOf(RAPID to false), vibrated)
        arbiter.stopPreview()
        assertEquals(1, cancels)

        val ring = Any()
        arbiter.start(ring, PULSE)
        assertFalse(arbiter.preview(HEARTBEAT)) // a live ring's vibration is never replaced
        arbiter.stopPreview() // nor cancelled
        assertEquals(1, cancels)
        assertEquals(listOf(RAPID to false, PULSE to true), vibrated)
    }

    @Test fun aRingStartingDuringAPreviewTakesOverTheVibrator() {
        arbiter.preview(PULSE)
        val ring = Any()
        assertTrue(arbiter.start(ring, PULSE)) // same pattern, but the preview's isn't the ring's loop
        assertEquals(listOf(PULSE to false, PULSE to true), vibrated)
        assertEquals(1, cancels) // the preview, cleared before the ring's vibrate
        arbiter.stopPreview() // the editor leaving later must not cancel the ring
        assertEquals(1, cancels)
    }

    @Test fun noPreviewWithoutAVibrator() {
        hasVibrator = false
        assertFalse(arbiter.preview(PULSE))
        assertTrue(vibrated.isEmpty())
    }

    @Test fun whenTheNextOwnersPatternFailsPulseKeepsThePhoneVibrating() {
        val stale = Any()
        val current = Any()
        arbiter.start(stale, PULSE)
        assertTrue(arbiter.start(current, ESCALATING)) // told it vibrates: PULSE is playing
        failNext = true
        assertThrows(IllegalArgumentException::class.java) { arbiter.stop(stale) }
        assertEquals(listOf(PULSE to true, PULSE to true), vibrated)
        assertEquals(0, cancels)
    }

    @Test fun theStopPathsPulseFallbackIsRecordedSoTheFailingPatternIsNotRetried() {
        val stale = Any()
        val current = Any()
        arbiter.start(stale, PULSE)
        arbiter.start(current, ESCALATING)
        failing = setOf(ESCALATING)
        assertThrows(IllegalArgumentException::class.java) { arbiter.stop(stale) }
        assertEquals(listOf(PULSE to true, PULSE to true), vibrated)
        // A later owner's start finds Pulse playing for the head: nothing retried, nothing thrown.
        assertTrue(arbiter.start(Any(), RAPID))
        assertEquals(listOf(PULSE to true, PULSE to true), vibrated)
    }

    @Test fun theHeadsPatternFailingInsideAnotherOwnersStartFallsBackToPulseForTheHead() {
        val head = Any()
        val other = Any()
        failing = setOf(ESCALATING)
        // The head's own start fails: its own session hears it and rings with sound.
        assertThrows(IllegalArgumentException::class.java) { arbiter.start(head, ESCALATING) }
        // Replaying the head's pattern in another owner's start fails again: that throw isn't the
        // caller's. The head's session believes it vibrates, so Pulse plays for it, and the caller vibrates.
        assertTrue(arbiter.start(other, RAPID))
        assertEquals(listOf(PULSE to true), vibrated)
        assertTrue(arbiter.start(other, RAPID)) // recorded: the failing pattern isn't retried
        assertEquals(listOf(PULSE to true), vibrated)
        arbiter.stop(head)
        assertEquals(listOf(PULSE to true, RAPID to true), vibrated)
        assertEquals(0, cancels)
    }

    @Test fun anotherOwnersStartStillThrowsWhenEvenPulseFails() {
        val head = Any()
        failing = setOf(ESCALATING, PULSE)
        assertThrows(IllegalArgumentException::class.java) { arbiter.start(head, ESCALATING) }
        // Nothing vibrates at all, so the caller must hear it and ring with sound (I1).
        assertThrows(IllegalArgumentException::class.java) { arbiter.start(Any(), RAPID) }
        assertTrue(vibrated.isEmpty())
    }

    @Test fun aPreviewThatThrowsReturnsFalseAndLeavesNoPreviewBehind() {
        failNext = true
        assertFalse(arbiter.preview(RAPID))
        arbiter.stopPreview() // nothing to stop
        assertEquals(0, cancels)
        assertTrue(arbiter.preview(RAPID))
        assertEquals(listOf(RAPID to false), vibrated)
    }
}
