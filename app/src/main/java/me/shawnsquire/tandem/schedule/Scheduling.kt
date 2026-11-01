package me.shawnsquire.tandem.schedule

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** How long after an occurrence fires that commands and skips pinned to it are still honored. */
val FIRE_GRACE: Duration = 2.minutes

/** True when the alarm has no repeat days (rings once, then is done). */
fun Alarm.isOneShot(): Boolean = days.isEmpty()

/**
 * Next time [alarm] should ring strictly after [now] in [zone], or null if none.
 *
 * - Honors [Alarm.days] weekdays; empty days is a one-shot (next time the clock hits hour:minute).
 * - Returns null when the alarm is disabled.
 * - [Alarm.skippedOccurrenceAt] skips exactly that occurrence (the following one is returned);
 *   a skip that has already passed is ignored.
 *
 * L1 extends this (design "Data model") to also skip the joiner's own skip,
 * `link.ownerSkippedOccurrenceAt`, a pending "both" skip, and on the owner the solo skip
 * ([Alarm.soloSkippedOccurrenceAt]).
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
 * Snooze-ledger view for validation; mirrors alarm.SnoozeEntry without depending on the alarm
 * package (schedule is java.time only).
 */
data class ParkedSnooze(val alarmId: String, val occurrenceAt: Instant?, val until: Instant)

/**
 * Extra state [validate] needs for ring commands (STOP_RINGING{occurrenceAt}, SNOOZE).
 * [ring] is the slot's ring, [merged] its merged members, [snoozes] every snooze-ledger entry
 * (current and parked), [lastRang] is FiredLedger.lastRang for the command's alarm.
 */
data class RingContext(
    val ring: RingState?,
    val merged: List<RingMember>,
    val snoozes: List<ParkedSnooze>,
    val lastRang: Instant?,
)

/**
 * Whether [alarm] can ring at all.
 *
 * L1 rule (design "Data model"): for a linked row (`link != null`) armed only when
 * `link.joinerEnabled` is true and the owner side is on: the copied `enabled` is true (or a
 * "both on" ENABLE is pending in `link.pendingBoth`) and no "both off" DISABLE is pending.
 * For an own row: `enabled`. Today it returns `enabled` for every row.
 */
fun isArmed(alarm: Alarm): Boolean = alarm.enabled

/**
 * Owner-side validation of a partner [cmd] against local state (I2, I3).
 * Rejects expired commands (24 h), commands whose target occurrence doesn't match [alarm]'s
 * next occurrence, and stop-ringing whose ringId doesn't match [ring].
 *
 * SKIP_NEXT and DISABLE are also rejected while the alarm is ringing (the partner should send
 * STOP_RINGING). They are pinned to the occurrence after `now - FIRE_GRACE` or after `now`, so
 * a command for an occurrence that has just fired still applies for [FIRE_GRACE]; the second
 * form keeps the genuinely current next occurrence valid during that window.
 * SKIP_NEXT on a one-shot alarm is validated like DISABLE; for a one-shot alarm the applier/UI
 * disables it instead of setting a skip.
 *
 * [context] is null today and ignored. L1 rule (design "Ring command validation") for
 * STOP_RINGING{occurrenceAt: X} and SNOOZE{X, T}, checked in order against the slot's ring, its
 * merged members and the snooze ledger:
 * 1. Expired or duplicate: reject (I3).
 * 2. Not ringing here ("skipped here"): this phone's own state says X wouldn't ring: its
 *    `skippedOccurrenceAt` or `soloSkippedOccurrenceAt`, `link.ownerSkippedOccurrenceAt`, a
 *    pending "both" skip or off, copied `enabled = false`, or `joinerEnabled = false`.
 * 3. Already resolved ("already rang or changed"): X was dismissed, auto-silenced or dismissed in
 *    advance on this phone. Checked against FiredLedger (`lastRang`) plus the live ring state, so
 *    it holds inside [FIRE_GRACE] too and a late SNOOZE can't re-arm a dismissed ring.
 * 4. Live: X is sounding, snoozed (current or parked), or a member of a merged ring: apply. A
 *    SNOOZE whose T is not later than X's current snooze end is rejected ("already snoozed
 *    later"), so two crossing snoozes settle on the later T.
 * 5. Early: X is the next occurrence and at most [FIRE_GRACE] away. STOP_RINGING dismisses it in
 *    advance; SNOOZE arms the snooze with no ring first.
 * 6. Otherwise reject.
 *
 * Also for L1: SKIP_NEXT upgrades a solo skip to a shared skip; UNSKIP clears only the shared
 * skip; INVITE_LINK is checked for validity (expiry); a partner command aimed at a linked row's
 * `id` is rejected (owner-to-joiner commands are matched by `link.ownerAlarmId`). STOP_RINGING
 * may carry `ringId` (today's check) or `occurrenceAt`. SNOOZE and INVITE_LINK are rejected
 * "not supported yet" until then.
 */
fun validate(
    cmd: Command,
    alarm: Alarm?,
    ring: RingState?,
    now: Instant,
    zone: ZoneId,
    context: RingContext? = null,
): CommandDecision {
    if (now >= cmd.expiresAt) return CommandDecision.Reject("expired")
    if (alarm == null) return CommandDecision.Reject("alarm not found")
    val stale = CommandDecision.Reject("already rang or changed")
    val ringing = ring != null && ring.alarmId == cmd.alarmId
    val grace = FIRE_GRACE.toJavaDuration()
    fun pinned(): Boolean {
        val target = cmd.occurrenceAt ?: return false
        return target == nextOccurrence(alarm, now.minus(grace), zone) ||
            target == nextOccurrence(alarm, now, zone)
    }
    fun disableLike(): CommandDecision = when {
        !alarm.enabled -> CommandDecision.Reject("already off")
        ringing -> CommandDecision.Reject("already ringing")
        pinned() -> CommandDecision.Apply
        else -> stale
    }
    return when (cmd.type) {
        CommandType.SKIP_NEXT -> {
            val skipped = alarm.skippedOccurrenceAt
            when {
                alarm.isOneShot() -> disableLike()
                ringing -> CommandDecision.Reject("already ringing")
                skipped != null && skipped > now.minus(grace) -> CommandDecision.Reject("already skipped")
                pinned() -> CommandDecision.Apply
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
        CommandType.DISABLE -> disableLike()
        CommandType.ENABLE -> CommandDecision.Apply
        CommandType.STOP_RINGING ->
            if (ring != null && ring.alarmId == cmd.alarmId && cmd.ringId != null && ring.ringId == cmd.ringId) {
                CommandDecision.Apply
            } else {
                CommandDecision.Reject("not ringing")
            }
        CommandType.SNOOZE, CommandType.INVITE_LINK -> CommandDecision.Reject("not supported yet")
    }
}
