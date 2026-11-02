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

    // ---- merged rings: mergedOutputs ----

    private val soundOnly = RingOutputs(true, false, VibrationPattern.HEARTBEAT)
    private val vibrateOnly = RingOutputs(false, true, VibrationPattern.RAPID)
    private val both = RingOutputs(true, true, VibrationPattern.SLOW_WAVE)

    @Test fun aSingleMemberRingsWithItsOwnOutputs() {
        assertEquals(MergedOutputs(vibrateOnly, soundFrom = null), mergedOutputs(listOf(vibrateOnly)))
        assertEquals(MergedOutputs(both, soundFrom = 0), mergedOutputs(listOf(both)))
    }

    @Test fun vibrateOnlyMemberJoiningASoundRingKeepsTheSoundAndAddsItsVibration() {
        // The primary's vibration is off, so the first member with vibration on supplies the pattern.
        assertEquals(
            MergedOutputs(RingOutputs(true, true, VibrationPattern.RAPID), soundFrom = 0),
            mergedOutputs(listOf(soundOnly, vibrateOnly)),
        )
    }

    @Test fun soundMemberJoiningAVibrateOnlyRingStartsSoundFromThatMember() {
        assertEquals(
            MergedOutputs(RingOutputs(true, true, VibrationPattern.RAPID), soundFrom = 1),
            mergedOutputs(listOf(vibrateOnly, soundOnly)),
        )
    }

    @Test fun thePrimarysPatternWinsWhileItsVibrationIsOn() {
        assertEquals(VibrationPattern.RAPID, mergedOutputs(listOf(vibrateOnly, both)).outputs.vibration)
        assertEquals(VibrationPattern.SLOW_WAVE, mergedOutputs(listOf(both, vibrateOnly)).outputs.vibration)
    }

    @Test fun elseTheFirstMemberInRingOrderWithVibrationOn() {
        val escalating = RingOutputs(false, true, VibrationPattern.ESCALATING)
        assertEquals(
            MergedOutputs(RingOutputs(true, true, VibrationPattern.ESCALATING), soundFrom = 0),
            mergedOutputs(listOf(soundOnly, soundOnly, escalating, vibrateOnly)),
        )
    }

    @Test fun vibrationIsOffOnlyWhenEveryMembersIsOff() {
        assertEquals(
            MergedOutputs(RingOutputs(true, false, VibrationPattern.HEARTBEAT), soundFrom = 0),
            mergedOutputs(listOf(soundOnly, soundOnly.copy(vibration = VibrationPattern.PULSE))),
        )
    }

    @Test fun aMemberThatDoesNotChangeTheResultLeavesItAlone() {
        // Same outputs and sound source before and after the join: the ring re-vibrates nothing.
        assertEquals(mergedOutputs(listOf(both)), mergedOutputs(listOf(both, vibrateOnly)))
        assertEquals(mergedOutputs(listOf(both)).outputs, mergedOutputs(listOf(both, soundOnly)).outputs)
    }

    @Test fun anUnreadableMemberCountsAsSoundAndPulse() {
        assertEquals(RingOutputs.UNKNOWN, RingOutputs(soundOn = true, vibrationOn = true, vibration = VibrationPattern.PULSE))
        assertEquals(MergedOutputs(RingOutputs.UNKNOWN, soundFrom = 0), mergedOutputs(listOf(null)))
        // Into a vibrate-only ring: its sound starts, from that member; the primary's pattern stays.
        assertEquals(
            MergedOutputs(RingOutputs(true, true, VibrationPattern.RAPID), soundFrom = 1),
            mergedOutputs(listOf(vibrateOnly, null)),
        )
        // Into a sound-only ring: its Pulse is the first vibration on.
        assertEquals(
            MergedOutputs(RingOutputs(true, true, VibrationPattern.PULSE), soundFrom = 0),
            mergedOutputs(listOf(soundOnly, null)),
        )
    }

    @Test fun noMembersAtAllRingsInFull() {
        assertEquals(MergedOutputs(RingOutputs.UNKNOWN, soundFrom = null), mergedOutputs(emptyList()))
    }
}
