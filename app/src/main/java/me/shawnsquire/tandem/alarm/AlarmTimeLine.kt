package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.schedule.RingState
import java.time.Instant

/**
 * The ring screen's line under the clock: "6:30 alarm", "6:30 and 6:45 alarms", "6:30, 6:45 and
 * 7:00 alarms", then " · snoozed 2×" once the ring was snoozed. The times are the occurrences that
 * rang ([members]: primary first), not the alarms' current hour and minute, formatted by [format]
 * and listed once each in ring order. Null without a known occurrence (the fallback ring).
 */
fun alarmTimeLine(ring: RingState, format: (Instant) -> String): String? {
    val times = ring.members().map { format(it.occurrenceAt) }.distinct()
    if (times.isEmpty()) return null
    val list = if (times.size == 1) times[0] else times.dropLast(1).joinToString(", ") + " and " + times.last()
    val line = "$list alarm" + if (times.size > 1) "s" else ""
    return if (ring.snoozeCount > 0) "$line · snoozed ${ring.snoozeCount}×" else line
}
