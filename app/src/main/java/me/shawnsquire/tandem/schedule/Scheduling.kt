package me.shawnsquire.tandem.schedule

import java.time.Instant
import java.time.ZoneId

/**
 * Next time [alarm] should ring strictly after [now] in [zone], or null if none.
 *
 * - Honors [Alarm.days] weekdays; empty days is a one-shot (next time the clock hits hour:minute).
 * - Returns null when the alarm is disabled.
 * - [Alarm.skippedOccurrenceAt] skips exactly that occurrence (the following one is returned);
 *   a skip that has already passed is ignored.
 */
fun nextOccurrence(alarm: Alarm, now: Instant, zone: ZoneId): Instant? = TODO("U1")

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
): CommandDecision = TODO("U1")
