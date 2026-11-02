package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.schedule.VibrationPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VibrationWaveformsTest {
    private val all = VibrationPattern.entries.flatMap { p ->
        listOf(true, false).flatMap { amp -> listOf(true, false).map { rep -> Triple(p, amp, rep) } }
    }

    private fun Waveform.isOn(i: Int) = amplitudes?.let { it[i] > 0 } ?: (i % 2 == 1)

    @Test fun everyWaveformIsOneThePlatformAccepts() {
        for ((p, amp, rep) in all) {
            val w = vibrationWaveform(p, amp, rep)
            val what = "$p amplitude=$amp repeating=$rep"
            assertTrue(what, w.timings.isNotEmpty())
            assertTrue("$what: timings non-negative", w.timings.all { it >= 0 })
            assertTrue("$what: not all zero", w.timings.any { it > 0 })
            w.amplitudes?.let { a ->
                assertEquals("$what: one amplitude per timing", w.timings.size, a.size)
                assertTrue("$what: amplitudes within 0..255", a.all { it in 0..255 })
            }
            if (rep) {
                assertTrue("$what: repeat index inside the timings", w.repeat in w.timings.indices)
                // The looped tail must vibrate, or a ring would go still after its first pass.
                val tail = w.repeat until w.timings.size
                assertTrue("$what: tail has length", tail.sumOf { w.timings[it] } > 0)
                assertTrue("$what: tail vibrates", tail.any { w.isOn(it) && w.timings[it] > 0 })
            } else {
                assertEquals("$what: a preview plays once", -1, w.repeat)
            }
        }
    }

    @Test fun pulseIsExactlyTheOldPattern() {
        for (amp in listOf(true, false)) {
            val w = vibrationWaveform(VibrationPattern.PULSE, amp)
            assertEquals(listOf(0L, 800L, 600L), w.timings)
            assertNull(w.amplitudes)
            assertEquals(0, w.repeat)
        }
    }

    @Test fun aPreviewIsOneCycleOfTheSameShape() {
        for ((p, amp, _) in all) {
            val ringing = vibrationWaveform(p, amp, repeating = true)
            val preview = vibrationWaveform(p, amp, repeating = false)
            assertEquals("$p", ringing.timings, preview.timings)
            assertEquals("$p", ringing.amplitudes, preview.amplitudes)
            assertEquals(-1, preview.repeat)
        }
    }

    @Test fun amplitudesOnlyWhereTheVibratorHasAmplitudeControl() {
        for (p in VibrationPattern.entries) assertNull("$p", vibrationWaveform(p, amplitudeControl = false).amplitudes)
        assertNotNull(vibrationWaveform(VibrationPattern.SLOW_WAVE, amplitudeControl = true).amplitudes)
        assertNotNull(vibrationWaveform(VibrationPattern.ESCALATING, amplitudeControl = true).amplitudes)
    }

    @Test fun heartbeatIsTwoShortBeatsThenAPause() {
        val w = vibrationWaveform(VibrationPattern.HEARTBEAT, amplitudeControl = true)
        val on = w.timings.filterIndexed { i, _ -> i % 2 == 1 }
        val off = w.timings.filterIndexed { i, _ -> i > 0 && i % 2 == 0 }
        assertEquals(2, on.size)
        assertTrue(on.all { it <= 200 })
        assertTrue(off.last() > off.first()) // the pause after the second beat is the long gap
    }

    @Test fun rapidIsShortFastPulses() {
        val w = vibrationWaveform(VibrationPattern.RAPID, amplitudeControl = true)
        assertTrue(w.timings.drop(1).all { it in 1..150 })
    }

    @Test fun slowWaveSwellsUpAndDownSoftly() {
        val a = vibrationWaveform(VibrationPattern.SLOW_WAVE, amplitudeControl = true).amplitudes!!
        val peak = a.indexOf(a.max())
        assertTrue(a.max() < 255) // soft
        assertEquals(a.subList(1, peak + 1), a.subList(1, peak + 1).sorted())
        val down = a.subList(peak, a.size)
        assertEquals(down, down.sortedDescending())
        // Without amplitude control: one long swell's worth of on-time, then a long rest.
        val flat = vibrationWaveform(VibrationPattern.SLOW_WAVE, amplitudeControl = false).timings
        assertTrue(flat[1] >= 1_500 && flat[2] >= 1_000)
    }

    @Test fun escalatingRampsThenRepeatsOnlyItsHoldSegment() {
        val w = vibrationWaveform(VibrationPattern.ESCALATING, amplitudeControl = true)
        val a = w.amplitudes!!
        // The repeat index lands on the hold's on-segment at full strength; the tail is the hold alone.
        assertEquals(255, a[w.repeat])
        assertEquals(2, w.timings.size - w.repeat)
        assertEquals(0, a[w.repeat + 1])
        val ramp = (1 until w.repeat).filter { a[it] > 0 }.map { a[it] }
        assertEquals(ramp, ramp.sorted())
        assertTrue(ramp.first() < 100 && ramp.last() < 255)
        assertTrue("ramp lasts 30-60 s", w.timings.take(w.repeat).sum() in 30_000L..60_000L)
    }

    @Test fun escalatingWithoutAmplitudeControlLengthensItsOnTimes() {
        val w = vibrationWaveform(VibrationPattern.ESCALATING, amplitudeControl = false)
        assertNull(w.amplitudes)
        assertEquals(1, w.repeat % 2) // an on-segment: the 2-argument waveform alternates off, on
        val on = (1 until w.repeat step 2).map { w.timings[it] }
        assertEquals(on, on.sorted())
        assertTrue(on.first() < on.last())
        assertTrue(w.timings[w.repeat] >= on.last()) // the hold is the longest on-time
        assertEquals(2, w.timings.size - w.repeat)
        assertTrue("ramp lasts 30-60 s", w.timings.take(w.repeat).sum() in 30_000L..60_000L)
    }
}
