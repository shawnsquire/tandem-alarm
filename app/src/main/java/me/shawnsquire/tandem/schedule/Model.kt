package me.shawnsquire.tandem.schedule

import java.time.DayOfWeek
import java.time.Instant

/**
 * An alarm owned by this phone. Per I2 the local Room DB is the single source of truth
 * and the partner never writes alarm data. A row with [link] is the joiner's copy of a
 * partner's alarm: it is still this phone's own row (its joiner-owned data is written only
 * here, per I2), but its copied fields (label, hour, minute, days, enabled, [lateRing]) are written
 * only by LinkSync.
 *
 * Empty [days] means a one-shot alarm. [skippedOccurrenceAt] skips exactly that occurrence;
 * a skip whose instant has passed is ignored.
 *
 * [lateRing] is the owner's answer to "still ring at the old time?" after moving a joined alarm
 * earlier. On an own row it is that answer; on a linked row it is copied from the owner's
 * published alarm and written only by LinkSync, like hour/minute.
 *
 * [vibration], [vibrationOn] and [soundOn] pick what a ring does besides showing the screen:
 * [soundOn] is a switch, so the [ringtoneUri] is kept while it is off. Read them through
 * [effectiveOutputs], never directly, so a linked row's overrides and the "never silent" rule apply.
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
    val lateRing: LateRing? = null,
    val vibration: VibrationPattern = VibrationPattern.PULSE,
    val vibrationOn: Boolean = true,
    val soundOn: Boolean = true,
)

/**
 * The owner's answer to "should it still ring at the old time?" for a joined alarm moved earlier.
 * `ring = false` asks the joiner not to ring late at [occurrenceAt], the old occurrence.
 */
data class LateRing(val occurrenceAt: Instant, val ring: Boolean)

/** The shape of a ring's vibration. Stored and synced by name, so never rename a value. */
enum class VibrationPattern { PULSE, HEARTBEAT, RAPID, SLOW_WAVE, ESCALATING }

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
 * [overrideVibration], [overrideVibrationOn] and [overrideSoundOn] do the same for the ring's
 * vibration and sound; null means use the copied value.
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
    val overrideVibration: VibrationPattern? = null,
    val overrideVibrationOn: Boolean? = null,
    val overrideSoundOn: Boolean? = null,
)

/** What a ring actually drives: sound, vibration, and the vibration's [vibration] pattern. At least one of the two is on. */
data class RingOutputs(val soundOn: Boolean, val vibrationOn: Boolean, val vibration: VibrationPattern) {
    companion object {
        /** An alarm that can't be read rings in full, with the pattern every ring had before patterns (I1). */
        val UNKNOWN = RingOutputs(soundOn = true, vibrationOn = true, vibration = VibrationPattern.PULSE)
    }
}

/**
 * What a merged ring drives: [outputs], and [soundFrom], the index (in ring order) of the member whose
 * sound plays, null while sound is off or when there are no members (the ring's own media).
 */
data class MergedOutputs(val outputs: RingOutputs, val soundFrom: Int?)

/**
 * One ring for several alarms ([members]: each one's [effectiveOutputs] in ring order, primary first;
 * null for a member that can't be read, which counts as [RingOutputs.UNKNOWN], I1). Sound plays when any
 * member has it on, from the first such member. Vibration is off only when every member's is off; its
 * pattern is the primary's when its vibration is on, else the first member's in ring order whose is (with
 * the primary first, both are "the first with vibration on"). Pure, so a member change that leaves this
 * result alone changes nothing on the phone.
 */
fun mergedOutputs(members: List<RingOutputs?>): MergedOutputs {
    if (members.isEmpty()) return MergedOutputs(RingOutputs.UNKNOWN, soundFrom = null)
    val known = members.map { it ?: RingOutputs.UNKNOWN }
    val soundFrom = known.indexOfFirst { it.soundOn }.takeIf { it >= 0 }
    val vibrating = known.firstOrNull { it.vibrationOn }
    return MergedOutputs(
        RingOutputs(soundOn = soundFrom != null, vibrationOn = vibrating != null, vibration = (vibrating ?: known.first()).vibration),
        soundFrom,
    )
}

/**
 * A ring's outputs from its members' rows in ring order (null: unreadable, which counts as
 * [RingOutputs.UNKNOWN] since it could be a Ring alarm). A Notify link only notifies, so its own
 * settings never drive a full ring and it is left out; a ring of Notify links alone rings as unknown,
 * sound and Pulse, as it always has when its notification can't alert (I1). [MergedOutputs.soundFrom]
 * indexes [members] as given.
 */
fun ringOutputs(members: List<Alarm?>): MergedOutputs {
    val counted = members.indices.filter { members[it]?.link?.mode != LinkMode.NOTIFY }
    val merged = mergedOutputs(counted.map { members[it]?.effectiveOutputs() })
    return merged.copy(soundFrom = merged.soundFrom?.let { counted[it] })
}

/**
 * The joiner's override else the row's own (or copied) value, for each of sound, vibration and
 * pattern. An alarm that would be silent and still (bad data, or an override combination) rings
 * with both on, so a ring is never missed.
 */
fun Alarm.effectiveOutputs(): RingOutputs {
    val sound = link?.overrideSoundOn ?: soundOn
    val vibrate = link?.overrideVibrationOn ?: vibrationOn
    val pattern = link?.overrideVibration ?: vibration
    return if (!sound && !vibrate) RingOutputs(true, true, pattern) else RingOutputs(sound, vibrate, pattern)
}

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
