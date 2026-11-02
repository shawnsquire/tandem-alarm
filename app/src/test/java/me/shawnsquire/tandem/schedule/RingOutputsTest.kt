package me.shawnsquire.tandem.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class RingOutputsTest {
    private val own = Alarm("a", "Work", 6, 30, emptySet(), true, null, null, null)
    private val link = AlarmLink("o", "oa", LinkMode.RING, null, true, null, null, null, null)

    @Test fun defaultsRingWithSoundAndPulseVibration() {
        assertEquals(RingOutputs(soundOn = true, vibrationOn = true, vibration = VibrationPattern.PULSE), own.effectiveOutputs())
    }

    @Test fun ownAlarmUsesItsOwnValues() {
        val a = own.copy(soundOn = false, vibrationOn = true, vibration = VibrationPattern.RAPID)
        assertEquals(RingOutputs(false, true, VibrationPattern.RAPID), a.effectiveOutputs())
    }

    @Test fun nullOverridesFallBackToTheRowsCopiedValues() {
        val a = own.copy(link = link, soundOn = true, vibrationOn = false, vibration = VibrationPattern.SLOW_WAVE)
        assertEquals(RingOutputs(true, false, VibrationPattern.SLOW_WAVE), a.effectiveOutputs())
    }

    @Test fun eachOverrideWins() {
        val base = own.copy(soundOn = true, vibrationOn = true, vibration = VibrationPattern.PULSE)
        assertEquals(
            VibrationPattern.ESCALATING,
            base.copy(link = link.copy(overrideVibration = VibrationPattern.ESCALATING)).effectiveOutputs().vibration,
        )
        assertEquals(
            RingOutputs(true, false, VibrationPattern.PULSE),
            base.copy(link = link.copy(overrideVibrationOn = false)).effectiveOutputs(),
        )
        assertEquals(
            RingOutputs(false, true, VibrationPattern.PULSE),
            base.copy(link = link.copy(overrideSoundOn = false)).effectiveOutputs(),
        )
    }

    @Test fun overrideCanTurnAnOutputBackOn() {
        val a = own.copy(link = link.copy(overrideSoundOn = true), soundOn = false, vibrationOn = true)
        assertEquals(RingOutputs(true, true, VibrationPattern.PULSE), a.effectiveOutputs())
    }

    @Test fun bothOffOnTheRowNormalizesToBothOnAtTheEffectivePattern() {
        val a = own.copy(soundOn = false, vibrationOn = false, vibration = VibrationPattern.HEARTBEAT)
        assertEquals(RingOutputs(true, true, VibrationPattern.HEARTBEAT), a.effectiveOutputs())
    }

    @Test fun bothOffFromAnOverrideCombinationNormalizesWithTheOverridePattern() {
        val a = own.copy(
            soundOn = true, vibrationOn = false,
            link = link.copy(overrideSoundOn = false, overrideVibration = VibrationPattern.RAPID),
        )
        assertEquals(RingOutputs(true, true, VibrationPattern.RAPID), a.effectiveOutputs())
    }
}
