package me.shawnsquire.tandem.schedule

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Next time [alarm] should ring strictly after [now] in [zone], or null if none.
 *
 * - Honors [Alarm.days] weekdays; empty days is a one-shot (next time the clock hits hour:minute).
 * - Returns null when the alarm is disabled.
 * - [Alarm.skippedOccurrenceAt] skips exactly that occurrence (the following one is returned);
 *   a skip that has already passed is ignored.
 */
fun nextOccurrence(alarm: Alarm, now: Instant, zone: ZoneId): Instant? {
    if (!alarm.enabled) return null
    val oneShot = alarm.days.isEmpty()
    val time = LocalTime.of(alarm.hour, alarm.minute)
    val today = now.atZone(zone).toLocalDate()
    // One-shot: only today or tomorrow. Weekly: enough days to find the occurrence after a skipped one.
    val span = if (oneShot) 2L else 15L
    val upcoming = (0 until span).asSequence()
        .map { today.plusDays(it) }
        .filter { oneShot || it.dayOfWeek in alarm.days }
        .map { ZonedDateTime.of(it, time, zone).toInstant() }
        .filter { it > now }
    // A skip at or before now can never equal a candidate, so it is ignored implicitly.
    if (oneShot) return upcoming.firstOrNull()?.takeIf { it != alarm.skippedOccurrenceAt }
    return upcoming.firstOrNull { it != alarm.skippedOccurrenceAt }
}

/**
 * Owner-side validation of a partner [cmd] against local state (I2, I3).
 * Rejects expired commands (24 h), commands whose target occurrence has passed or doesn't
 * match [alarm]'s current next occurrence, and stop-ringing whose ringId doesn't match [ring].
 */
fun validate(
    cmd: Command,
    alarm: Alarm?,
    ring: RingState?,
    now: Instant,
    zone: ZoneId,
): CommandDecision {
    if (now >= cmd.expiresAt) return CommandDecision.Reject("expired")
    if (alarm == null) return CommandDecision.Reject("alarm not found")
    val stale = CommandDecision.Reject("already rang or changed")
    return when (cmd.type) {
        CommandType.SKIP_NEXT -> {
            val skipped = alarm.skippedOccurrenceAt
            when {
                skipped != null && skipped > now -> CommandDecision.Reject("already skipped")
                cmd.occurrenceAt != null && cmd.occurrenceAt == nextOccurrence(alarm, now, zone) ->
                    CommandDecision.Apply
                else -> stale
            }
        }
        CommandType.UNSKIP -> {
            val skipped = alarm.skippedOccurrenceAt
            if (skipped != null && skipped > now && skipped == cmd.occurrenceAt) {
                CommandDecision.Apply
            } else {
                CommandDecision.Reject("nothing to unskip")
            }
        }
        CommandType.DISABLE -> when {
            !alarm.enabled -> CommandDecision.Reject("already off")
            cmd.occurrenceAt != null && cmd.occurrenceAt == nextOccurrence(alarm, now, zone) ->
                CommandDecision.Apply
            else -> stale
        }
        CommandType.ENABLE -> CommandDecision.Apply
        CommandType.STOP_RINGING ->
            if (ring != null && ring.alarmId == cmd.alarmId && cmd.ringId != null && ring.ringId == cmd.ringId) {
                CommandDecision.Apply
            } else {
                CommandDecision.Reject("not ringing")
            }
    }
}
