package me.shawnsquire.tandem.schedule

import java.time.DayOfWeek
import java.time.Instant

/**
 * An alarm owned by this phone. Per I2 the local Room DB is the single source of truth
 * and the partner never writes alarm data. A row with [link] is the joiner's copy of a
 * partner's alarm: it is still this phone's own row (its joiner-owned data is written only
 * here, per I2), but its copied fields (label, hour, minute, days, enabled) are written only
 * by LinkSync.
 *
 * Empty [days] means a one-shot alarm. [skippedOccurrenceAt] skips exactly that occurrence;
 * a skip whose instant has passed is ignored.
 */
data class Alarm(
    val id: String,
    val label: String,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek>,
    val enabled: Boolean,
    val skippedOccurrenceAt: Instant?,
    val ringtoneUri: String?,
    val wallpaperPath: String?,
    val link: AlarmLink? = null,
    val soloSkippedOccurrenceAt: Instant? = null,
)

/**
 * How a joiner's linked alarm behaves on this phone: [RING] rings like a normal alarm,
 * [NOTIFY] only notifies when the owner's alarm fires.
 */
enum class LinkMode { RING, NOTIFY }

/** Where a ring started: [LOCAL] fired on this phone, [REMOTE] was started by the linked phone. */
enum class RingOrigin { LOCAL, REMOTE }

/**
 * A "both of us" command sent by the joiner and not yet reflected in the owner's doc.
 * [type] is SKIP_NEXT, UNSKIP, DISABLE or ENABLE; [occurrenceAt] pins it (I3) and [commandId]
 * identifies the sent command. [createdAt] starts its 24 h window (I3), so a restart does not
 * restart the window; null only for a command stored before it was recorded, which then gets a
 * full window from the first time it is watched.
 */
data class PendingBoth(
    val type: CommandType,
    val occurrenceAt: Instant?,
    val commandId: String,
    val createdAt: Instant? = null,
)

/**
 * The joiner-side link from a local alarm row to the owner's alarm. A linked row's hour, minute,
 * days and enabled are copied from the owner and only written by LinkSync.
 *
 * [ownerSkippedOccurrenceAt] is copied from the owner's device doc. [joinerEnabled] is the
 * joiner's own on/off. [pendingBoth] is a both-of-us command not yet reflected in the owner's doc.
 * The override fields replace the owner's label, ringtone and wallpaper on this phone only.
 */
data class AlarmLink(
    val ownerUid: String,
    val ownerAlarmId: String,
    val mode: LinkMode,
    val ownerSkippedOccurrenceAt: Instant?,
    val joinerEnabled: Boolean,
    val pendingBoth: PendingBoth?,
    val overrideLabel: String?,
    val overrideRingtoneUri: String?,
    val overrideWallpaperPath: String?,
)

/**
 * One alarm folded into a merged ring: [alarmId] at its scheduled [occurrenceAt]. Per I3
 * `(ownerAlarmId, occurrenceAt)` is the shared ring key that identifies the same ring on both phones.
 *
 * [alarmId] is this phone's local row id: on the owner it equals the owner's alarm id; on the joiner
 * it is the linked row's id, which RingMirror and the applier map to `link.ownerAlarmId` when building
 * or matching commands, and the device doc's `ringing` publishes as `link.ownerAlarmId`. So the shared
 * key across phones is `(ownerAlarmId, occurrenceAt)`, not [alarmId].
 */
data class RingMember(val alarmId: String, val occurrenceAt: Instant)

/** The currently ringing (or snoozed) alarm. [ringId] pins stop-ringing commands (I3).
 * [occurrenceAt] is the scheduled occurrence this ring belongs to; [merged] lists other alarms
 * folded into this ring. */
data class RingState(
    val alarmId: String,
    val ringId: String,
    val startedAt: Instant,
    val snoozedUntil: Instant?,
    val occurrenceAt: Instant? = null,
    val merged: List<RingMember> = emptyList(),
)

/** Partner-to-owner command kinds (I2: the partner only sends commands). */
enum class CommandType { SKIP_NEXT, UNSKIP, DISABLE, ENABLE, STOP_RINGING, SNOOZE, INVITE_LINK }

/**
 * A command from the partner (I2). Pinned to what the partner saw (I3): skip, unskip and
 * turn-off carry [occurrenceAt]; stop-ringing carries [ringId]. Expires at [expiresAt]
 * (24 h after creation) and is idempotent by [id]. [snoozedUntil] (SNOOZE only) is absolute,
 * so both phones resume together.
 */
data class Command(
    val id: String,
    val from: String,
    val to: String,
    val type: CommandType,
    val alarmId: String,
    val occurrenceAt: Instant?,
    val ringId: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val snoozedUntil: Instant? = null,
)

/** Owner's verdict on a command (I3): apply it, or reject with a reason. */
sealed interface CommandDecision {
    data object Apply : CommandDecision
    data class Reject(val reason: String) : CommandDecision
}

/** Lifecycle of a sent command as seen by the sender. */
enum class CommandStatus { PENDING, APPLIED, REJECTED }
