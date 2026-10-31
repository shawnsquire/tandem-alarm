package me.shawnsquire.tandem.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/**
 * Loops the first candidate that opens, then falls back to the system alarm, ringtone and
 * notification defaults. One instance per [RingingSession]; [stop] is idempotent.
 */
class MediaPlayerAlarmSound(private val context: Context) : AlarmSound {
    private var player: MediaPlayer? = null

    @Synchronized
    override fun start(candidates: List<String>) {
        stopLocked()
        val all = candidates + listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        ).map { it.toString() }
        player = openFirst(all) { open(it) }
        if (player == null) Log.e(TAG, "no ringtone could be opened")
    }

    private fun open(uri: String): MediaPlayer? {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(context, Uri.parse(uri))
            mp.isLooping = true
            mp.prepare()
            mp.start()
            return mp
        } catch (e: Exception) { // IOException, SecurityException, IllegalState/Argument
            Log.w(TAG, "ringtone failed to open: $uri", e)
            mp.release()
            return null
        }
    }

    @Synchronized
    override fun stop() = stopLocked()

    private fun stopLocked() {
        val mp = player ?: return
        player = null
        try { mp.stop() } catch (e: IllegalStateException) { /* never started */ }
        mp.release()
    }

    private companion object { const val TAG = "AlarmSound" }
}

/**
 * Service-level vibration ownership. `Vibrator.cancel()` cancels all of the app's vibration, so a
 * superseded session's late `stop()` must not silence the session that replaced it.
 */
class VibrationArbiter(
    private val startVibration: () -> Unit,
    private val cancelVibration: () -> Unit,
) {
    private var owner: Any? = null

    @Synchronized
    fun start(owner: Any) {
        this.owner = owner
        startVibration()
    }

    @Synchronized
    fun stop(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
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
