package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.schedule.VibrationPattern

/**
 * One vibration as the platform's waveform takes it, kept free of android.os so the tables are
 * JVM-testable. [timings] are milliseconds starting with an off segment. With [amplitudes] (one per
 * timing, 0..255, 0 = motor off) it is
 * [`VibrationEffect.createWaveform(long[], int[], int)`](https://developer.android.com/reference/android/os/VibrationEffect#createWaveform(long[],%20int[],%20int));
 * null means the on/off form
 * [`createWaveform(long[], int)`](https://developer.android.com/reference/android/os/VibrationEffect#createWaveform(long[],%20int)),
 * whose segments alternate off and on at the device's default strength. [repeat] is the index the
 * waveform loops back to, or -1 to play once.
 */
data class Waveform(val timings: List<Long>, val amplitudes: List<Int>?, val repeat: Int)

/**
 * The waveform for [pattern]. [amplitudeControl] is
 * [`Vibrator.hasAmplitudeControl()`](https://developer.android.com/reference/android/os/Vibrator#hasAmplitudeControl()):
 * without it a pattern keeps its shape in on/off timings alone. [repeating] false is one cycle that
 * plays once (repeat -1), for the editor's preview; the ring loops.
 *
 * - PULSE: `0, 800, 600` looped, exactly the one pattern rings had before patterns existed.
 * - HEARTBEAT: two short beats and a pause.
 * - RAPID: short, fast pulses.
 * - SLOW_WAVE: a long soft swell up and down, then a rest; one long buzz and rest without amplitude control.
 * - ESCALATING: [ESCALATING_STEPS] beats of [ESCALATING_ON] ms on, [ESCALATING_OFF] ms off, from soft
 *   to nearly full (36 s), then a full-strength hold that alone repeats. Without amplitude control
 *   the on-times lengthen instead, toward the hold's. Its preview is that whole ramp once.
 */
fun vibrationWaveform(pattern: VibrationPattern, amplitudeControl: Boolean, repeating: Boolean = true): Waveform {
    val w = when (pattern) {
        VibrationPattern.PULSE -> Waveform(listOf(0L, 800L, 600L), null, 0)
        VibrationPattern.HEARTBEAT -> Waveform(listOf(0L, 150L, 120L, 150L, 800L), null, 0)
        VibrationPattern.RAPID -> Waveform(listOf(0L, 100L, 100L), null, 0)
        VibrationPattern.SLOW_WAVE -> if (amplitudeControl) slowWave() else Waveform(listOf(0L, 2_000L, 1_200L), null, 0)
        VibrationPattern.ESCALATING -> escalating(amplitudeControl)
    }
    return if (repeating) w else w.copy(repeat = -1)
}

/** 15 steps of 150 ms rising to 200 and back (2.25 s), then 1 s still. */
private fun slowWave(): Waveform {
    val up = (1..8).map { it * 25 }
    val down = (7 downTo 1).map { it * 25 }
    val swell = up + down
    return Waveform(
        timings = listOf(0L) + swell.map { 150L } + 1_000L,
        amplitudes = listOf(0) + swell + 0,
        repeat = 0,
    )
}

private fun escalating(amplitudeControl: Boolean): Waveform {
    val timings = mutableListOf(0L)
    val amplitudes = mutableListOf(0)
    val period = ESCALATING_ON + ESCALATING_OFF
    for (i in 0 until ESCALATING_STEPS) {
        if (amplitudeControl) {
            timings += ESCALATING_ON; amplitudes += 40 + 215 * i / ESCALATING_STEPS
            timings += ESCALATING_OFF; amplitudes += 0
        } else {
            val on = 100L + (ESCALATING_ON - 100L) * i / ESCALATING_STEPS
            timings += on
            timings += period - on
        }
    }
    val hold = timings.size // the hold's on-segment: what the ring repeats once the ramp is done
    timings += ESCALATING_ON; amplitudes += 255
    timings += ESCALATING_OFF; amplitudes += 0
    return Waveform(timings, amplitudes.takeIf { amplitudeControl }, hold)
}

private const val ESCALATING_STEPS = 30
private const val ESCALATING_ON = 800L
private const val ESCALATING_OFF = 400L
