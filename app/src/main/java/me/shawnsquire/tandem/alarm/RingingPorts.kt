package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.schedule.Alarm
import java.time.Instant

/** Loops the first candidate URI that opens; the adapter appends system-default fallbacks after [candidates]. */
interface AlarmSound {
    fun start(candidates: List<String>)
    fun stop()
}

interface AlarmVibration {
    fun start()
    fun stop()
}

/** UI side effects of ringing. Implemented with NotificationManager in the Android layer. */
interface RingingNotifier {
    /** Update the foreground notification: label plus Stop and Snooze actions. */
    fun showRinging(alarm: Alarm?, ringId: String)

    /** Non-foreground notification with a Dismiss action. */
    fun showSnoozed(ringId: String, until: Instant)
    fun cancelSnoozed()
}

/**
 * Tries each candidate in order and returns the first non-null result of [open].
 * An exception from one candidate moves on to the next; all failing yields null.
 */
internal fun <T> openFirst(candidates: List<String>, open: (String) -> T?): T? {
    for (candidate in candidates) {
        val opened = try {
            open(candidate)
        } catch (e: Exception) {
            null
        }
        if (opened != null) return opened
    }
    return null
}
