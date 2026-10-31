package me.shawnsquire.tandem.schedule

import java.time.DayOfWeek
import java.time.Instant

/**
 * An alarm owned by this phone. Per I2 the local Room DB is the single source of truth
 * and the partner never writes alarm data.
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
)

/** The currently ringing (or snoozed) alarm. [ringId] pins stop-ringing commands (I3). */
data class RingState(
    val alarmId: String,
    val ringId: String,
    val startedAt: Instant,
    val snoozedUntil: Instant?,
)

/** Partner-to-owner command kinds (I2: the partner only sends commands). */
enum class CommandType { SKIP_NEXT, UNSKIP, DISABLE, ENABLE, STOP_RINGING }

/**
 * A command from the partner (I2). Pinned to what the partner saw (I3): skip, unskip and
 * turn-off carry [occurrenceAt]; stop-ringing carries [ringId]. Expires at [expiresAt]
 * (24 h after creation) and is idempotent by [id].
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
)

/** Owner's verdict on a command (I3): apply it, or reject with a reason. */
sealed interface CommandDecision {
    data object Apply : CommandDecision
    data class Reject(val reason: String) : CommandDecision
}

/** Lifecycle of a sent command as seen by the sender. */
enum class CommandStatus { PENDING, APPLIED, REJECTED }
