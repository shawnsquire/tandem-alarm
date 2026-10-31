package me.shawnsquire.tandem.sync

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.nextOccurrence

// Pure Firestore/FCM mapping. Timestamps are always Firestore Timestamps (never epoch Longs) in
// documents; FCM payloads carry epoch-millis strings.

private val COMMAND_TTL: Duration = Duration.ofHours(24)

fun commandStatusToString(status: CommandStatus): String = when (status) {
    CommandStatus.PENDING -> "pending"
    CommandStatus.APPLIED -> "applied"
    CommandStatus.REJECTED -> "rejected"
}

fun commandStatusFromString(s: String): CommandStatus? = when (s) {
    "pending" -> CommandStatus.PENDING
    "applied" -> CommandStatus.APPLIED
    "rejected" -> CommandStatus.REJECTED
    else -> null
}

internal fun Instant.toTimestamp(): Timestamp = Timestamp(epochSecond, nano)

internal fun Timestamp.toInstantCompat(): Instant = Instant.ofEpochSecond(seconds, nanoseconds.toLong())

/**
 * Fields for a new command doc. Keys match the create rule's `hasOnly(...)` exactly; the command
 * id is the doc id, so there is no `id` field, and `reason` is only written by the owner's resolve.
 */
fun commandCreateMap(
    from: String,
    to: String,
    type: CommandType,
    alarmId: String,
    occurrenceAt: Instant?,
    ringId: String?,
    now: Instant,
): Map<String, Any?> = mapOf(
    "from" to from,
    "to" to to,
    "type" to type.name,
    "alarmId" to alarmId,
    "createdAt" to now.toTimestamp(),
    "expiresAt" to now.plus(COMMAND_TTL).toTimestamp(),
    "status" to commandStatusToString(CommandStatus.PENDING),
    "occurrenceAt" to occurrenceAt?.toTimestamp(),
    "ringId" to ringId,
)

/** Parses a command doc; null when a required field is missing or invalid. */
fun commandFromDoc(id: String, data: Map<String, Any?>): Command? {
    val from = data["from"] as? String ?: return null
    val to = data["to"] as? String ?: return null
    val type = (data["type"] as? String)?.let { n -> CommandType.entries.firstOrNull { it.name == n } } ?: return null
    val alarmId = data["alarmId"] as? String ?: return null
    val createdAt = (data["createdAt"] as? Timestamp)?.toInstantCompat() ?: return null
    val expiresAt = (data["expiresAt"] as? Timestamp)?.toInstantCompat() ?: return null
    return Command(
        id = id,
        from = from,
        to = to,
        type = type,
        alarmId = alarmId,
        occurrenceAt = (data["occurrenceAt"] as? Timestamp)?.toInstantCompat(),
        ringId = data["ringId"] as? String,
        createdAt = createdAt,
        expiresAt = expiresAt,
    )
}

/**
 * Parses an FCM command payload (keys: householdId, commandId, fromUid, to, type, alarmId,
 * occurrenceAt?, ringId?, createdAt, expiresAt, status, reason?; times are epoch-millis strings).
 * `fromUid` becomes [Command.from] because FCM reserves "from". Null when malformed.
 */
fun commandFromFcm(data: Map<String, String>): Command? {
    val id = data["commandId"]?.takeIf(String::isNotEmpty) ?: return null
    val from = data["fromUid"]?.takeIf(String::isNotEmpty) ?: return null
    val to = data["to"]?.takeIf(String::isNotEmpty) ?: return null
    val type = data["type"]?.let { n -> CommandType.entries.firstOrNull { it.name == n } } ?: return null
    val alarmId = data["alarmId"]?.takeIf(String::isNotEmpty) ?: return null
    val createdAt = data["createdAt"]?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null
    val expiresAt = data["expiresAt"]?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null
    val occurrenceAt = data["occurrenceAt"]?.let { s -> (s.toLongOrNull() ?: return null).let(Instant::ofEpochMilli) }
    return Command(id, from, to, type, alarmId, occurrenceAt, data["ringId"]?.takeIf(String::isNotEmpty), createdAt, expiresAt)
}

/** `householdId` of an FCM command payload, or null if absent/blank. */
fun householdIdFromFcm(data: Map<String, String>): String? = data["householdId"]?.takeIf(String::isNotEmpty)

/** `status` of an FCM command payload, or null if absent/unknown. */
fun statusFromFcm(data: Map<String, String>): CommandStatus? = data["status"]?.let(::commandStatusFromString)

/** `reason` of an FCM command payload (set only for rejected commands). */
fun reasonFromFcm(data: Map<String, String>): String? = data["reason"]?.takeIf(String::isNotEmpty)

/**
 * Body of `households/{hid}/devices/{uid}`. Never includes ringtone or wallpaper data.
 * `fcmToken` is omitted when unknown; `updatedAt` is a server timestamp.
 */
fun deviceDocMap(
    displayName: String,
    fcmToken: String?,
    alarms: List<Alarm>,
    ring: RingState?,
    now: Instant,
    zone: ZoneId,
): Map<String, Any?> = buildMap {
    put("displayName", displayName)
    if (fcmToken != null) put("fcmToken", fcmToken)
    put("updatedAt", FieldValue.serverTimestamp())
    put(
        "alarms",
        alarms.map { a ->
            mapOf(
                "id" to a.id,
                "label" to a.label,
                "hour" to a.hour,
                "minute" to a.minute,
                "days" to a.days.sorted().map { it.name },
                "enabled" to a.enabled,
                "nextOccurrenceAt" to nextOccurrence(a, now, zone)?.toTimestamp(),
                "skippedOccurrenceAt" to a.skippedOccurrenceAt?.toTimestamp(),
            )
        },
    )
    put(
        "ringing",
        ring?.let {
            mapOf(
                "alarmId" to it.alarmId,
                "ringId" to it.ringId,
                "startedAt" to it.startedAt.toTimestamp(),
                "snoozedUntil" to it.snoozedUntil?.toTimestamp(),
            )
        },
    )
}

/** Inverse of [deviceDocMap] for a partner's doc; malformed alarm entries are skipped. Null when unusable. */
fun partnerStateFromDoc(data: Map<String, Any?>): PartnerState? {
    if (data.isEmpty()) return null
    val alarms = (data["alarms"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::partnerAlarmFrom) }
    return PartnerState(
        displayName = data["displayName"] as? String ?: "",
        alarms = alarms,
        ringing = (data["ringing"] as? Map<*, *>)?.let(::ringStateFrom),
        lastSeen = (data["updatedAt"] as? Timestamp)?.toInstantCompat(),
    )
}

private fun partnerAlarmFrom(m: Map<*, *>): PartnerAlarm? {
    val id = m["id"] as? String ?: return null
    val hour = (m["hour"] as? Number)?.toInt() ?: return null
    val minute = (m["minute"] as? Number)?.toInt() ?: return null
    val enabled = m["enabled"] as? Boolean ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    val days = (m["days"] as? List<*>).orEmpty()
        .mapNotNull { n -> DayOfWeek.entries.firstOrNull { it.name == n } }
        .toSet()
    return PartnerAlarm(
        id = id,
        label = m["label"] as? String ?: "",
        hour = hour,
        minute = minute,
        days = days,
        enabled = enabled,
        nextOccurrenceAt = (m["nextOccurrenceAt"] as? Timestamp)?.toInstantCompat(),
        skippedOccurrenceAt = (m["skippedOccurrenceAt"] as? Timestamp)?.toInstantCompat(),
    )
}

private fun ringStateFrom(m: Map<*, *>): RingState? {
    val alarmId = m["alarmId"] as? String ?: return null
    val ringId = m["ringId"] as? String ?: return null
    val startedAt = (m["startedAt"] as? Timestamp)?.toInstantCompat() ?: return null
    return RingState(alarmId, ringId, startedAt, (m["snoozedUntil"] as? Timestamp)?.toInstantCompat())
}
