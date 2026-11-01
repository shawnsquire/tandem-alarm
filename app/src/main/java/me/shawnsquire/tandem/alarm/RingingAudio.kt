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
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Loops the first candidate that prepares, then falls back to the system alarm, ringtone and
 * notification defaults and finally the bundled tone. One instance per [RingingSession].
 *
 * Audio never blocks the ring: [start] returns at once and all player work runs on a dedicated
 * "alarm-audio" thread. [stop] is idempotent and safe from any thread.
 */
class MediaPlayerAlarmSound(private val context: Context) : AlarmSound {
    private var run: Run? = null

    @Synchronized
    override fun start(candidates: List<String>) {
        run?.stop()
        val bundled = bundledToneUri(context)
        val all = candidates + listOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_RINGTONE,
            RingtoneManager.TYPE_NOTIFICATION,
        ).mapNotNull { runCatching { RingtoneManager.getDefaultUri(it) }.getOrNull()?.toString() }
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
 * Service-level vibration ownership. `Vibrator.cancel()` cancels all of the app's vibration, so it
 * runs only when the last session that started vibrating stops: a superseded session's late
 * `stop()`, or a session that merged into a sounding ring, must not silence the one still ringing.
 */
class VibrationArbiter(
    private val startVibration: () -> Unit,
    private val cancelVibration: () -> Unit,
) {
    private val owners: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

    @Synchronized
    fun start(owner: Any) {
        // Before starting: a start that throws is still undone by the owner's stop.
        owners += owner
        startVibration()
    }

    @Synchronized
    fun stop(owner: Any) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        cancelVibration()
    }

    companion object {
        private val PATTERN = longArrayOf(0, 800, 600)

        fun forContext(context: Context): VibrationArbiter {
            val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
            return VibrationArbiter(
                startVibration = {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(PATTERN, 0),
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                    )
                },
                cancelVibration = { vibrator.cancel() },
            )
        }
    }
}

/** One instance per [RingingSession]; all instances of a service share one [VibrationArbiter]. */
class SystemAlarmVibration(private val arbiter: VibrationArbiter) : AlarmVibration {
    override fun start() = arbiter.start(this)
    override fun stop() = arbiter.stop(this)
}
