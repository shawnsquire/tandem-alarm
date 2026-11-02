package me.shawnsquire.tandem.alarm

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.schedule.VibrationPattern

/**
 * Loops the first candidate that prepares, then falls back to the system alarm, ringtone and
 * notification defaults (unless the caller's candidates already carry them) and finally the
 * bundled tone. One instance per [RingingSession].
 *
 * Audio never blocks the ring: [start] returns at once and all player work runs on a dedicated
 * "alarm-audio" thread. [stop] is idempotent and safe from any thread.
 */
class MediaPlayerAlarmSound(private val context: Context) : AlarmSound {
    private var run: Run? = null

    @Synchronized
    override fun start(candidates: List<String>, appendSystemDefaults: Boolean) {
        run?.stop()
        val bundled = bundledToneUri(context)
        val defaults = if (!appendSystemDefaults) emptyList() else listOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_RINGTONE,
            RingtoneManager.TYPE_NOTIFICATION,
        ).mapNotNull { runCatching { RingtoneManager.getDefaultUri(it) }.getOrNull()?.toString() }
        val all = candidates + defaults
        run = Run(context.applicationContext, SoundSequencer(all, bundled)).also { it.begin() }
    }

    @Synchronized
    override fun stop() {
        run?.stop()
        run = null
    }

    /** Wires a [SoundSequencer] to MediaPlayer and a Handler. Sequencer and player live on the audio thread. */
    private class Run(private val context: Context, private val sequencer: SoundSequencer) {
        private val thread = HandlerThread("alarm-audio").also { it.start() }
        private val handler = Handler(thread.looper)
        private var player: MediaPlayer? = null
        private var timeout: Runnable? = null
        private var currentGeneration = -1

        fun begin() {
            handler.post { apply(sequencer.begin()) }
        }

        fun stop() {
            // If the thread already quit, the post is dropped and there is nothing left to release.
            handler.post {
                sequencer.stop()
                releasePlayer()
                thread.quitSafely()
            }
        }

        private fun apply(decision: SoundDecision) {
            when (decision) {
                is SoundDecision.Try -> {
                    releasePlayer()
                    open(decision.uri, decision.generation)
                }
                SoundDecision.Play -> try {
                    player?.start()
                    timeout?.let { handler.removeCallbacks(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "start failed", e)
                    apply(sequencer.onError(currentGeneration))
                }
                SoundDecision.Exhausted -> {
                    releasePlayer()
                    Log.e(TAG, "no ringtone could be played")
                }
                SoundDecision.Prepare, SoundDecision.Ignore -> Unit
            }
        }

        /** One player's lifecycle. [sourceDone] and [released] are touched on the audio thread, [abandoned] from both. */
        private class Attempt(val mp: MediaPlayer, val generation: Int) {
            @Volatile var abandoned = false
            var sourceDone = false
        }

        private var attempt: Attempt? = null

        private fun open(uri: String, generation: Int) {
            currentGeneration = generation
            // Created here so its callbacks arrive on the audio looper.
            val at = Attempt(MediaPlayer(), generation)
            attempt = at
            player = at.mp
            // Hard bound on the whole attempt, source open included: posted before anything can hang.
            val t = Runnable {
                Log.w(TAG, "attempt timed out: $uri")
                apply(sequencer.onTimeout(generation))
            }
            timeout = t
            handler.postDelayed(t, PREPARE_TIMEOUT_MS)
            try {
                at.mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                runCatching { at.mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK) }
                at.mp.isLooping = true
            } catch (e: Exception) {
                Log.w(TAG, "ringtone failed to configure: $uri", e)
                apply(sequencer.onError(generation))
                return
            }
            // setDataSource can block on a stuck content provider: never on the audio thread.
            EXECUTOR.execute {
                var error: Exception? = null
                try {
                    at.mp.setDataSource(context, Uri.parse(uri))
                } catch (e: Exception) { // IOException, SecurityException, IllegalState/Argument
                    error = e
                }
                if (at.abandoned) { // the chain moved on while we were stuck
                    releaseQuietly(at.mp)
                    return@execute
                }
                if (!handler.post { onSourceOpened(at, uri, error) }) releaseQuietly(at.mp)
            }
        }

        private fun onSourceOpened(at: Attempt, uri: String, error: Exception?) {
            at.sourceDone = true
            if (at.abandoned || at !== attempt) { releaseQuietly(at.mp); return }
            if (error != null) {
                Log.w(TAG, "ringtone failed to open: $uri", error)
                apply(sequencer.onError(at.generation))
                return
            }
            val d = sequencer.onSourceOpened(at.generation)
            if (d != SoundDecision.Prepare) { releaseQuietly(at.mp); return }
            try {
                at.mp.setOnPreparedListener { apply(sequencer.onPrepared(at.generation)) }
                at.mp.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "playback error what=$what extra=$extra uri=$uri")
                    apply(sequencer.onError(at.generation))
                    true
                }
                at.mp.prepareAsync()
            } catch (e: Exception) {
                Log.w(TAG, "ringtone failed to prepare: $uri", e)
                apply(sequencer.onError(at.generation))
            }
        }

        private fun releaseQuietly(mp: MediaPlayer) {
            try { mp.stop() } catch (e: Exception) { /* never started */ }
            runCatching { mp.release() }
        }

        private fun releasePlayer() {
            timeout?.let { handler.removeCallbacks(it) }
            timeout = null
            val at = attempt ?: return
            attempt = null
            player = null
            at.abandoned = true
            if (!at.sourceDone) return // setDataSource still running; its worker releases the player
            at.mp.setOnPreparedListener(null)
            at.mp.setOnErrorListener(null)
            releaseQuietly(at.mp)
        }
    }

    companion object {
        private const val TAG = "AlarmSound"
        const val PREPARE_TIMEOUT_MS = 2_000L
        private val EXECUTOR = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "alarm-audio-open").apply { isDaemon = true }
        }

        /** `android.resource://<pkg>/raw/alarm_fallback`: a synthesized beep that needs no storage or provider. */
        fun bundledToneUri(context: Context): String =
            Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.packageName)
                .appendPath("raw")
                .appendPath(context.resources.getResourceEntryName(R.raw.alarm_fallback))
                .build()
                .toString()
    }
}

/**
 * Process-wide vibration ownership: the app has one vibrator and
 * [`Vibrator.cancel()`](https://developer.android.com/reference/android/os/Vibrator#cancel()) turns
 * all of it off, so several sessions share it through owners. A superseded session's late `stop()`,
 * or a session that merged into a sounding ring, must not silence or change the one still ringing.
 *
 * Each owner holds a pattern; the earliest owner still holding is the one that plays. It re-vibrates
 * only when that pattern changes (its owner switched, or it stopped and the next owner's differs) and
 * cancels only when the last owner stops. So a merging fire's early vibration and its `finish()`
 * leave the primary's pattern alone.
 *
 * [start] returns false, owning nothing, when
 * [`Vibrator.hasVibrator()`](https://developer.android.com/reference/android/os/Vibrator#hasVibrator())
 * says there is no vibrator, and rethrows a vibrate that throws (the owner is recorded first, so its
 * stop still undoes it). Either way the session rings with sound (I1). A failed vibrate is never
 * taken as playing: the next start or stop tries again. When the earliest owner stops and the next
 * one's pattern throws, Pulse plays instead: that owner's session was told it vibrates.
 *
 * [preview] is the editor's: one non-repeating pass, only while no ring owns the vibrator, and a
 * ring's start replaces it. [stopPreview] cancels only a preview, never a ring.
 */
class VibrationArbiter(
    private val hasVibrator: () -> Boolean,
    /** Vibrates [VibrationPattern]'s waveform: looped for a ring, once (`repeating = false`) for a preview. */
    private val vibrate: (VibrationPattern, repeating: Boolean) -> Unit,
    private val cancelVibration: () -> Unit,
) {
    /** In start order, compared by identity. */
    private val owners = mutableListOf<Pair<Any, VibrationPattern>>()
    private var playing: VibrationPattern? = null
    private var previewing = false

    @Synchronized
    fun start(owner: Any, pattern: VibrationPattern): Boolean {
        if (!hasVibrator()) return false
        // Before vibrating: a start that throws is still undone by the owner's stop.
        val i = owners.indexOfFirst { it.first === owner }
        if (i >= 0) owners[i] = owner to pattern else owners += owner to pattern
        play(owners.first().second)
        return true
    }

    @Synchronized
    fun stop(owner: Any) {
        val i = owners.indexOfFirst { it.first === owner }
        if (i < 0) return
        owners.removeAt(i)
        if (owners.isNotEmpty()) {
            val next = owners.first().second
            try {
                play(next)
            } catch (e: Throwable) {
                // The next owner's session believes it vibrates (its start found another pattern
                // playing), so it won't ring with sound instead: keep the phone vibrating (I1).
                if (next != VibrationPattern.PULSE) runCatching { play(VibrationPattern.PULSE) }
                throw e
            }
            return
        }
        playing = null
        cancelVibration()
    }

    /** One pass of [pattern] for the editor. False, playing nothing, while a ring vibrates or with no vibrator. */
    @Synchronized
    fun preview(pattern: VibrationPattern): Boolean {
        if (owners.isNotEmpty() || !hasVibrator()) return false
        previewing = true
        vibrate(pattern, false)
        return true
    }

    /** Ends a preview early; a no-op once a ring owns the vibrator. */
    @Synchronized
    fun stopPreview() {
        if (!previewing || owners.isNotEmpty()) return
        previewing = false
        cancelVibration()
    }

    private fun play(pattern: VibrationPattern) {
        if (pattern == playing) return
        if (previewing) {
            previewing = false
            runCatching { cancelVibration() }
        }
        playing = null // until the vibrate below succeeds
        vibrate(pattern, true)
        playing = pattern
    }

    companion object {
        @Volatile private var shared: VibrationArbiter? = null

        /** The process's one arbiter: the ringing service's sessions and the editor's preview share it. */
        fun get(context: Context): VibrationArbiter =
            shared ?: synchronized(this) { shared ?: forContext(context.applicationContext).also { shared = it } }

        private fun forContext(context: Context): VibrationArbiter {
            val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
            // Read once: a capability of the hardware, not something that changes while ringing.
            val amplitudeControl = runCatching { vibrator.hasAmplitudeControl() }.getOrDefault(false)
            // Here, not in the companion: android.os classes can't initialize in a JVM test.
            val alarm = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
            return VibrationArbiter(
                hasVibrator = { vibrator.hasVibrator() },
                vibrate = { pattern, repeating ->
                    vibrator.vibrate(vibrationWaveform(pattern, amplitudeControl, repeating).toEffect(), alarm)
                },
                cancelVibration = { vibrator.cancel() },
            )
        }

        private fun Waveform.toEffect(): VibrationEffect =
            if (amplitudes == null) VibrationEffect.createWaveform(timings.toLongArray(), repeat)
            else VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), repeat)
    }
}

/** One instance per [RingingSession]; all instances share the process's [VibrationArbiter]. */
class SystemAlarmVibration(private val arbiter: VibrationArbiter) : AlarmVibration {
    override fun start(pattern: VibrationPattern): Boolean = arbiter.start(this, pattern)
    override fun stop() = arbiter.stop(this)
}
