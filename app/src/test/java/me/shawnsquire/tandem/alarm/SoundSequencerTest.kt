package me.shawnsquire.tandem.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundSequencerTest {
    private val bundled = "android.resource://p/raw/alarm_fallback"

    private fun SoundDecision.tryUri() = (this as SoundDecision.Try).uri
    private fun SoundDecision.gen() = (this as SoundDecision.Try).generation

    @Test fun beginTriesFirstCandidate() {
        val s = SoundSequencer(listOf("a", "b"), bundled)
        assertEquals("a", s.begin().tryUri())
    }

    @Test fun preparedStarts() {
        val s = SoundSequencer(listOf("a"), bundled)
        val t = s.begin()
        assertEquals(SoundDecision.Play, s.onPrepared(t.gen()))
        assertEquals(SoundDecision.Ignore, s.onPrepared(t.gen()))
    }

    @Test fun timeoutAdvances() {
        val s = SoundSequencer(listOf("a", "b"), bundled)
        val a = s.begin()
        assertEquals("b", s.onTimeout(a.gen()).tryUri())
    }

    @Test fun errorAdvances() {
        val s = SoundSequencer(listOf("a", "b"), bundled)
        val a = s.begin()
        assertEquals("b", s.onError(a.gen()).tryUri())
    }

    @Test fun staleEventsAreIgnored() {
        val s = SoundSequencer(listOf("a", "b"), bundled)
        val a = s.begin()
        val b = s.onTimeout(a.gen())
        assertEquals(SoundDecision.Ignore, s.onPrepared(a.gen()))
        assertEquals(SoundDecision.Ignore, s.onError(a.gen()))
        assertEquals(SoundDecision.Ignore, s.onTimeout(a.gen()))
        assertEquals(SoundDecision.Play, s.onPrepared(b.gen()))
    }

    @Test fun timeoutAfterPlayingIsIgnored() {
        val s = SoundSequencer(listOf("a", "b"), bundled)
        val a = s.begin()
        s.onPrepared(a.gen())
        assertEquals(SoundDecision.Ignore, s.onTimeout(a.gen()))
    }

    @Test fun midRingErrorContinuesFromNextCandidate() {
        val s = SoundSequencer(listOf("a", "b", "c"), bundled)
        val a = s.begin()
        s.onPrepared(a.gen())
        val b = s.onError(a.gen())
        assertEquals("b", b.tryUri())
        assertEquals("c", s.onError(b.gen()).tryUri())
    }

    @Test fun bundledToneIsAlwaysLastAndNotDuplicated() {
        val s = SoundSequencer(listOf("a", bundled, "b"), bundled)
        var d = s.begin()
        val seen = mutableListOf<String>()
        while (d is SoundDecision.Try) { seen += d.uri; d = s.onError(d.generation) }
        assertEquals(listOf("a", "b", bundled), seen)
        assertEquals(SoundDecision.Exhausted, d)
    }

    @Test fun emptyCandidatesGoStraightToBundled() {
        val s = SoundSequencer(emptyList(), bundled)
        assertEquals(bundled, s.begin().tryUri())
    }

    @Test fun lastCandidateTimeoutKeepsWaiting() {
        val s = SoundSequencer(emptyList(), bundled)
        val b = s.begin()
        assertEquals(SoundDecision.Ignore, s.onTimeout(b.gen()))
        assertEquals(SoundDecision.Play, s.onPrepared(b.gen()))
    }

    @Test fun exhaustedAfterBundledFails() {
        val s = SoundSequencer(emptyList(), bundled)
        val b = s.begin()
        assertEquals(SoundDecision.Exhausted, s.onError(b.gen()))
        assertEquals(SoundDecision.Ignore, s.onError(b.gen()))
    }

    @Test fun stopIgnoresEverything() {
        val s = SoundSequencer(listOf("a", "b"), bundled)
        val a = s.begin()
        s.stop()
        assertEquals(SoundDecision.Ignore, s.onPrepared(a.gen()))
        assertEquals(SoundDecision.Ignore, s.onError(a.gen()))
        assertEquals(SoundDecision.Ignore, s.onTimeout(a.gen()))
        assertTrue(s.begin() == SoundDecision.Ignore)
    }
}
