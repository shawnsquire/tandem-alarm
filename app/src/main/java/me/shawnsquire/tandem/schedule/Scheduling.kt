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
 * Every occurrence this alarm will not ring at, as a set of scheduled instants (no expiry
 * applied; a past skip simply never matches a future candidate). Answers for any occurrence,
 * so [nextOccurrence] and later "would X ring here" checks share one rule.
 *
 * - Own row (`link == null`): [Alarm.skippedOccurrenceAt] and [Alarm.soloSkippedOccurrenceAt].
 * - Linked row: [Alarm.skippedOccurrenceAt] (the joiner's own skip), `link.ownerSkippedOccurrenceAt`,
 *   and a pending SKIP_NEXT's `occurrenceAt`. The owner's solo skip never applies to a linked
 *   row, so [Alarm.soloSkippedOccurrenceAt] is ignored there. A pending UNSKIP at the same
 *   occurrence as the owner's skip cancels that owner skip (a pending "both" choice takes effect
 *   on the joiner at once); it leaves the joiner's own skip alone.
 */
internal fun Alarm.skippedOccurrences(): Set<Instant> {
    val link = link ?: return setOfNotNull(skippedOccurrenceAt, soloSkippedOccurrenceAt)
    val pending = link.pendingBoth
    val ownerSkip = link.ownerSkippedOccurrenceAt
        ?.takeUnless { pending?.type == CommandType.UNSKIP && pending.occurrenceAt == it }
    val pendingSkip = pending?.takeIf { it.type == CommandType.SKIP_NEXT }?.occurrenceAt
    return setOfNotNull(skippedOccurrenceAt, ownerSkip, pendingSkip)
}

/**
 * Next time [alarm] should ring strictly after [now] in [zone], or null if none.
 *
 * - Honors [Alarm.days] weekdays; empty days is a one-shot (next time the clock hits hour:minute).
 * - Returns null when the alarm is not [isArmed].
 * - Occurrences in [skippedOccurrences] are skipped (the following one is returned); a skip that
 *   has already passed is ignored. Own rows skip [Alarm.skippedOccurrenceAt] and
 *   [Alarm.soloSkippedOccurrenceAt]; linked rows skip their own [Alarm.skippedOccurrenceAt],
 *   `link.ownerSkippedOccurrenceAt` and a pending "both" SKIP_NEXT, and never the solo skip.
 * - A one-shot whose single candidate is skipped returns null. An own one-shot with any
 *   [Alarm.soloSkippedOccurrenceAt] returns null: before X it is skipped, and after X (while the
 *   solo skip is still present, until X + [FIRE_GRACE]) tomorrow's time must not ring. Skip expiry
 *   turns it off once X has passed.
 */
fun nextOccurrence(alarm: Alarm, now: Instant, zone: ZoneId): Instant? {
    if (!isArmed(alarm)) return null
    val oneShot = alarm.days.isEmpty()
    val time = LocalTime.of(alarm.hour, alarm.minute)
    val today = now.atZone(zone).toLocalDate()
    if (oneShot && alarm.link == null && alarm.soloSkippedOccurrenceAt != null) return null
    val skips = alarm.skippedOccurrences()
    // One-shot: only today or tomorrow. Weekly: up to three skips can stack on one weekday
    // (own, owner, pending). Counting from today the first candidate can be day 7 (today's
    // weekday, time already passed), and three skipped weeks push the answer to day 28, so
    // days 0..28 (29 days) are enough.
    val span = if (oneShot) 2L else 29L
    val upcoming = (0 until span).asSequence()
        .map { today.plusDays(it) }
        .filter { oneShot || it.dayOfWeek in alarm.days }
        .map { ZonedDateTime.of(it, time, zone).toInstant() }
        .filter { it > now }
    // A skip at or before now can never equal a candidate, so it is ignored implicitly.
    if (oneShot) return upcoming.firstOrNull()?.takeIf { it !in skips }
    return upcoming.firstOrNull { it !in skips }
}

/**
 * Whether [occurrence] is one of [alarm]'s scheduled times by its hour, minute and days alone, built
 * in [zone] the way [nextOccurrence] builds a candidate (so a DST-gap time shifted forward still
 * matches). Arming, skips and the solo skip are not consulted, so a skipped X still falls on it.
 * A one-shot matches any day. The day before is tried too, in case a gap pushes the time past midnight.
 */
fun fallsOnSchedule(alarm: Alarm, occurrence: Instant, zone: ZoneId): Boolean {
    val time = LocalTime.of(alarm.hour, alarm.minute)
    val date = occurrence.atZone(zone).toLocalDate()
    return listOf(date, date.minusDays(1)).any {
        (alarm.days.isEmpty() || it.dayOfWeek in alarm.days) &&
            ZonedDateTime.of(it, time, zone).toInstant() == occurrence
    }
}

/**
 * Whether [alarm]'s hour and minute fall earlier than [occurrence] on [occurrence]'s own local date in
 * [zone], on a day the alarm rings: its time was moved earlier that day, to a time already passed.
 * Built in [zone] the way [nextOccurrence] builds a candidate, so a DST-gap time is shifted the same
 * way. A one-shot counts on any day. Arming and skips are not consulted, as in [fallsOnSchedule].
 */
fun movedEarlierThatDay(alarm: Alarm, occurrence: Instant, zone: ZoneId): Boolean {
    val date = occurrence.atZone(zone).toLocalDate()
    return (alarm.days.isEmpty() || date.dayOfWeek in alarm.days) &&
        ZonedDateTime.of(date, LocalTime.of(alarm.hour, alarm.minute), zone).toInstant() < occurrence
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
 * An own row (`link == null`) is armed when `enabled`. A linked row is armed only when
 * `link.joinerEnabled` is true and the owner side is on: the copied `enabled` is true (or a
 * "both on" ENABLE is pending in `link.pendingBoth`) and no "both off" DISABLE is pending.
 */
fun isArmed(alarm: Alarm): Boolean {
    val link = alarm.link ?: return alarm.enabled
    val pending = link.pendingBoth?.type
    return link.joinerEnabled &&
        (alarm.enabled || pending == CommandType.ENABLE) &&
        pending != CommandType.DISABLE
}

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
 * Ring commands STOP_RINGING{occurrenceAt: X} and SNOOZE{X, T} are validated by occurrence when
 * [context] is non-null (design "Ring command validation"), in order, against the slot's ring,
 * its merged members and the snooze ledger. Live state is matched on `alarm.id` plus X, never
 * on X alone or `cmd.alarmId`, so on a joiner [alarm] is the resolved linked row. With a null
 * [context], SNOOZE is "not supported yet" (the only such case) and STOP_RINGING needs a
 * `ringId`. A SNOOZE missing X or T is rejected "incomplete snooze". Merged members come from
 * [RingContext.merged] and the slot ring's own `merged`. The rules keep the design's numbers but are
 * checked in the order listed, not numeric order:
 * 1. Expired or duplicate: reject (I3).
 * 3. Already resolved ("already rang or changed"): X was dismissed, auto-silenced or dismissed in
 *    advance on this phone and is not live. Checked against FiredLedger (`lastRang`) plus the live
 *    ring state, so it holds inside [FIRE_GRACE] too and a late SNOOZE can't re-arm a dismissed ring.
 * 4. Live: X is sounding, snoozed (current or parked), or a member of a merged ring: apply. A
 *    SNOOZE whose T is not later than X's current snooze end is rejected ("already snoozed
 *    later"), so two crossing snoozes settle on the later T. For a merged member the slot ring's
 *    `snoozedUntil` is that end.
 * 2. Not ringing here ("skipped here"), reached only when X isn't live: this phone's own state says
 *    X wouldn't ring: its `skippedOccurrenceAt` or `soloSkippedOccurrenceAt`,
 *    `link.ownerSkippedOccurrenceAt`, a pending "both" skip or off, copied `enabled = false`, or
 *    `joinerEnabled = false`. Live comes first because a ring that is sounding must be stoppable
 *    even when the row has since changed: the owner's one-shot disables itself on dismiss, and a
 *    joiner's linked copy can pick up `enabled = false` before the owner's STOP_RINGING{X} arrives.
 * 5. Early ("not live yet or just fired"): X is the occurrence after `now - FIRE_GRACE` or after
 *    `now` (the same two forms as the pinned checks, on the full alarm) and at most [FIRE_GRACE]
 *    from `now` on either side. Before X, STOP_RINGING dismisses it in advance and SNOOZE arms the
 *    snooze with no ring first. After X (inside the grace), the partner's dismissal beat this
 *    phone's pre-fire check, so it applies the same way. X is not resolved (rule 3 ran first).
 *    The window is X - FIRE_GRACE ..< X + FIRE_GRACE (inclusive before, exclusive after, like skip
 *    expiry).
 * 6. Otherwise reject ("already rang or changed").
 *
 * STOP_RINGING with a `ringId` and no `occurrenceAt` keeps the ringId rule against [ring]. Rule 1
 * (expiry) runs first for every command; duplicates are the applier's job.
 *
 * Solo skips: SKIP_NEXT while the owner has a solo skip on the same X applies (the applier
 * upgrades it to a shared skip). The pinned checks (SKIP_NEXT, DISABLE, one-shot SKIP_NEXT)
 * accept a target matching either view: the shared view, ignoring `soloSkippedOccurrenceAt`
 * (the linked row's copy still sees X), or the full view the owner publishes (which skips X,
 * so the Partner tab sends X'). "already skipped" looks only at the shared `skippedOccurrenceAt`.
 * UNSKIP counts only the shared skip ("nothing to unskip" if only a solo skip exists).
 *
 * INVITE_LINK applies unless expired, whatever [alarm] is (null, own or linked) and regardless of
 * [context]: it is validated on the joiner, where `cmd.alarmId` is the owner's id and there is no
 * local row. The L4 applier checks that the owner's alarm exists in `PartnerState` and isn't
 * already joined.
 *
 * Linked rows: the applier resolves owner-to-joiner ring commands to the linked row before
 * calling, so STOP_RINGING and SNOOZE accept a linked [alarm]. Every other type (SKIP_NEXT,
 * UNSKIP, DISABLE, ENABLE) aimed at a linked row is rejected "alarm not found", after the expiry
 * and missing-alarm checks.
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
    if (cmd.type == CommandType.INVITE_LINK) return CommandDecision.Apply
    if (alarm == null) return CommandDecision.Reject("alarm not found")
    if (alarm.link != null && cmd.type !in RING_COMMANDS) {
        return CommandDecision.Reject("alarm not found")
    }
    val stale = CommandDecision.Reject("already rang or changed")
    val ringing = ring != null && ring.alarmId == cmd.alarmId
    val grace = FIRE_GRACE.toJavaDuration()
    fun pinned(): Boolean {
        val target = cmd.occurrenceAt ?: return false
        // Accept both views of the next occurrence. The joiner's linked row copy still sees this
        // phone ringing at a solo-skipped X, so SKIP_NEXT{X} must match the shared view (and
        // upgrades the solo skip). The owner publishes the full view, which skips X, so the
        // Partner tab targets X' and that must match too.
        val shared = alarm.copy(soloSkippedOccurrenceAt = null)
        return listOf(shared, alarm).any {
            target == nextOccurrence(it, now.minus(grace), zone) || target == nextOccurrence(it, now, zone)
        }
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
            if (context != null && cmd.occurrenceAt != null) {
                validateRingOccurrence(cmd, alarm, now, zone, context)
            } else if (
                ring != null && ring.alarmId == cmd.alarmId && cmd.ringId != null && ring.ringId == cmd.ringId
            ) {
                CommandDecision.Apply
            } else {
                CommandDecision.Reject("not ringing")
            }
        CommandType.SNOOZE ->
            if (context != null) {
                validateRingOccurrence(cmd, alarm, now, zone, context)
            } else {
                CommandDecision.Reject("not supported yet")
            }
        CommandType.INVITE_LINK -> CommandDecision.Apply // handled above; kept for exhaustiveness
    }
}

private val RING_COMMANDS = setOf(CommandType.STOP_RINGING, CommandType.SNOOZE)

/** Ring-command rules 2 to 6 in [validate]'s listed order; expiry and a missing alarm are the caller's job. */
private fun validateRingOccurrence(
    cmd: Command,
    alarm: Alarm,
    now: Instant,
    zone: ZoneId,
    context: RingContext,
): CommandDecision {
    val x = cmd.occurrenceAt
    val until = cmd.snoozedUntil
    if (x == null || (cmd.type == CommandType.SNOOZE && until == null)) {
        return CommandDecision.Reject("incomplete snooze")
    }
    val ring = context.ring?.takeIf { it.alarmId == alarm.id && it.occurrenceAt == x }
    val merged = (context.merged + context.ring?.merged.orEmpty())
        .any { it.alarmId == alarm.id && it.occurrenceAt == x }
    val parked = context.snoozes.filter { it.alarmId == alarm.id && it.occurrenceAt == x }
    if (ring == null && !merged && parked.isEmpty()) {
        if (context.lastRang == x) return CommandDecision.Reject("already rang or changed")
        if (!isArmed(alarm) || x in alarm.skippedOccurrences()) return CommandDecision.Reject("skipped here")
        // Same two forms as pinned(), on the full alarm: the occurrence after now - FIRE_GRACE
        // (one that just fired) or the genuinely next one.
        val grace = FIRE_GRACE.toJavaDuration()
        val adjacent = x == nextOccurrence(alarm, now.minus(grace), zone) ||
            x == nextOccurrence(alarm, now, zone)
        val near = java.time.Duration.between(now, x).abs() <= grace
        return if (adjacent && near) CommandDecision.Apply else CommandDecision.Reject("already rang or changed")
    }
    // A merged member's current snooze end is the slot ring's.
    val slotEnd = context.ring?.takeIf { merged }?.snoozedUntil
    val snoozeEnd = (listOfNotNull(ring?.snoozedUntil, slotEnd) + parked.map { it.until }).maxOrNull()
    if (cmd.type == CommandType.SNOOZE && until != null && snoozeEnd != null && until <= snoozeEnd) {
        return CommandDecision.Reject("already snoozed later")
    }
    return CommandDecision.Apply
}
