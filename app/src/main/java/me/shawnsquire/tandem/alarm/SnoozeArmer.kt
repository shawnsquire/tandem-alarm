package me.shawnsquire.tandem.alarm

import java.time.Instant

/** Registers the snooze alarm. Throws on failure so a snooze is never recorded without an alarm behind it. */
fun interface SnoozeArmer {
    fun armSnooze(alarmId: String, ringId: String, at: Instant)
}
