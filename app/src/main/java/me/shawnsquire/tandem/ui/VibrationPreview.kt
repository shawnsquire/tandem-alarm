package me.shawnsquire.tandem.ui

import android.content.Context
import me.shawnsquire.tandem.alarm.VibrationArbiter
import me.shawnsquire.tandem.schedule.VibrationPattern

/** The editor's vibration preview: one cycle of a pattern, stoppable. Never throws, and never touches a ring. */
interface VibrationPreview {
    fun play(pattern: VibrationPattern)

    /** Ends a preview early; a no-op when nothing is previewing. */
    fun stop()
}

/** Backed by the process's [VibrationArbiter], which refuses to preview while a ring owns the vibrator. */
class ArbiterVibrationPreview(private val context: Context) : VibrationPreview {
    override fun play(pattern: VibrationPattern) {
        VibrationArbiter.get(context).preview(pattern)
    }

    override fun stop() = VibrationArbiter.get(context).stopPreview()
}
