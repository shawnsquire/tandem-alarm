package me.shawnsquire.tandem.ui.alarms.linked

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.isArmed
import me.shawnsquire.tandem.schedule.isOneShot
import me.shawnsquire.tandem.schedule.nextOccurrence
import me.shawnsquire.tandem.sync.LinkRepository
import me.shawnsquire.tandem.ui.alarms.AlarmEdits
import java.time.Instant
import java.time.ZoneId

/** Which answers to "Just me or both of us?" the joined row's switch offers right now. */
data class LinkedSwitchOptions(val justMe: Boolean, val both: Boolean)

/** Whether the owner's side is on: the copied `enabled` or a pending ENABLE, and no pending DISABLE. */
internal fun ownerSideOn(alarm: Alarm): Boolean {
    val pending = alarm.link?.pendingBoth?.type
    return (alarm.enabled || pending == CommandType.ENABLE) && pending != CommandType.DISABLE
}

/**
 * The next occurrence as the owner sees the shared alarm: the copied hour, minute, days and enabled plus the
 * owner's own shared skip, which is what the owner's validate checks a "both" DISABLE or SKIP_NEXT against.
 * The joiner's own skips, a pending "both" command and the joiner's own off switch do not move it.
 */
internal fun sharedNextOccurrence(alarm: Alarm, now: Instant, zone: ZoneId): Instant? =
    nextOccurrence(
        alarm.copy(
            skippedOccurrenceAt = null, soloSkippedOccurrenceAt = null,
            link = alarm.link?.copy(joinerEnabled = true, pendingBoth = null),
        ),
        now, zone,
    )

/**
 * Turning off is always possible just for me, and for both only with an occurrence to pin (the shared one). Turning on just for me needs the owner's side on; for both it is an unpinned ENABLE.
 */
internal fun linkedSwitchOptions(alarm: Alarm, now: Instant, zone: ZoneId): LinkedSwitchOptions =
    if (isArmed(alarm)) {
        LinkedSwitchOptions(justMe = true, both = sharedNextOccurrence(alarm, now, zone) != null)
    } else {
        LinkedSwitchOptions(justMe = ownerSideOn(alarm), both = true)
    }

/**
 * The joiner's skip and on/off rules for a joined row, shared by the Alarms list and the Linked alarm
 * sheet. A joined row's `enabled` is the owner's, so nothing here writes it. "Both of us" is pinned to
 * [sharedNextOccurrence], computed from the copied hour, minute and days. The owner's published next
 * occurrence skips past a solo skip, and the row's own skips would move it past what the owner validates, so
 * neither is used. A stale local copy is simply rejected by the owner (I3).
 */
class LinkedAlarmActions(
    private val links: LinkRepository,
    private val edits: AlarmEdits,
) {
    /** A repeating row skips its next occurrence; a one-shot is turned off for me only. */
    suspend fun skipJustMe(alarm: Alarm, now: Instant, zone: ZoneId) {
        if (alarm.isOneShot()) {
            links.setJoinerEnabled(alarm.id, false)
        } else {
            nextOccurrence(alarm, now, zone)?.let { edits.setSkip(alarm.id, it) }
        }
    }

    suspend fun unskipJustMe(id: String) {
        edits.setSkip(id, null)
    }

    /** False when there is no occurrence to pin, so nothing was sent. */
    suspend fun skipBoth(alarm: Alarm, now: Instant, zone: ZoneId): Boolean {
        val pin = sharedNextOccurrence(alarm, now, zone) ?: return false
        links.sendBoth(alarm.id, CommandType.SKIP_NEXT, pin)
        return true
    }

    /** False when [on] is not allowed for just me (the owner's side is off), so nothing was written. */
    suspend fun setJustMe(alarm: Alarm, on: Boolean): Boolean {
        if (on && !ownerSideOn(alarm)) return false
        links.setJoinerEnabled(alarm.id, on)
        return true
    }

    /** False when turning off has no occurrence to pin, so nothing was sent. */
    suspend fun setBoth(alarm: Alarm, now: Instant, zone: ZoneId, on: Boolean): Boolean {
        if (on) {
            links.sendBoth(alarm.id, CommandType.ENABLE, null)
        } else {
            val pin = sharedNextOccurrence(alarm, now, zone) ?: return false
            links.sendBoth(alarm.id, CommandType.DISABLE, pin)
        }
        return true
    }
}
