package me.shawnsquire.tandem.alarm

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import java.time.Instant

/**
 * Refreshes the [AlarmMirror] after every reschedule, so the next boot can ring before unlock.
 * Only while [isAttached]: then the alarms came from Room. Locked, the mirror is the source and
 * locked writes already update it in place, so rewriting it from itself would add nothing.
 */
class MirrorUpdater(
    private val mirror: AlarmMirror,
    private val isAttached: () -> Boolean,
    private val settings: SettingsRepository,
) {
    /** Never throws. A settings value that can't be read keeps its previously mirrored value. */
    suspend fun onScheduled(alarms: List<Alarm>, triggers: Map<String, Long>) {
        try {
            if (!isAttached()) return
            val snooze = read("snooze minutes") { settings.snoozeMinutes.first() }
            val ringtone = read("default ringtone") { settings.defaultRingtoneUri.first() }
            val mirrored = alarms.map { alarm ->
                MirroredAlarm(alarm.copy(wallpaperPath = null), triggers[alarm.id]?.let(Instant::ofEpochMilli))
            }
            // Not update(), which refuses an unreadable mirror: Room is the source here, so this
            // also repairs a corrupt or missing one. Nothing else writes the mirror while attached.
            val old = mirror.read()
            mirror.write(
                MirrorSnapshot(
                    alarms = mirrored,
                    snoozeMinutes = snooze.getOrElse { old.snoozeMinutes },
                    defaultRingtoneUri = ringtone.getOrElse { old.defaultRingtoneUri },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("updating the alarm mirror failed", e)
        }
    }

    private suspend fun <T> read(what: String, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        log("reading $what for the alarm mirror failed", e)
        Result.failure(e)
    }

    // runCatching: Log is unavailable in plain JVM tests.
    private fun log(message: String, e: Throwable) {
        runCatching { Log.w(TAG, message, e) }
    }

    private companion object {
        const val TAG = "MirrorUpdater"
    }
}
