package me.shawnsquire.tandem.alarm

import java.time.Instant

/** Registers snooze alarms, one per ringId. Arming throws on failure so a snooze is never recorded without an alarm behind it. */
interface SnoozeArmer {
    fun armSnooze(alarmId: String, ringId: String, at: Instant)
    fun cancelSnooze(ringId: String)
}
