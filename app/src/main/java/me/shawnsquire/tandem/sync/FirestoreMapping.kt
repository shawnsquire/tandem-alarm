package me.shawnsquire.tandem.sync

import me.shawnsquire.tandem.media.AlarmMedia
import me.shawnsquire.tandem.media.MediaRef
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
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.RingMember
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
 * Fields for a new command doc. Keys are a subset of the create rule's `hasOnly(...)` (`snoozedUntil` is
 * added only when non-null, pending L3); the command id is the doc id, so there is no `id` field,
 * and `reason` is only written by the owner's resolve.
 */
fun commandCreateMap(
    from: String,
    to: String,
    type: CommandType,
    alarmId: String,
    occurrenceAt: Instant?,
    ringId: String?,
    now: Instant,
    snoozedUntil: Instant? = null,
): Map<String, Any?> = buildMap {
    putAll(mapOf(
        "from" to from,
        "to" to to,
        "type" to type.name,
        "alarmId" to alarmId,
        "createdAt" to now.toTimestamp(),
        "expiresAt" to now.plus(COMMAND_TTL).toTimestamp(),
        "status" to commandStatusToString(CommandStatus.PENDING),
        "occurrenceAt" to occurrenceAt?.toTimestamp(),
        "ringId" to ringId,
    ))
    // Only when set: the create rule's hasOnly(...) denies unknown keys until the rules allow it.
    if (snoozedUntil != null) put("snoozedUntil", snoozedUntil.toTimestamp())
}

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
        snoozedUntil = (data["snoozedUntil"] as? Timestamp)?.toInstantCompat(),
    )
}

/**
 * Parses an FCM command payload (keys: householdId, commandId, fromUid, to, type, alarmId,
 * occurrenceAt?, snoozedUntil?, ringId?, createdAt, expiresAt, status, reason?; times are epoch-millis strings).
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
    val snoozedUntil = data["snoozedUntil"]?.let { s -> (s.toLongOrNull() ?: return null).let(Instant::ofEpochMilli) }
    return Command(id, from, to, type, alarmId, occurrenceAt, data["ringId"]?.takeIf(String::isNotEmpty), createdAt, expiresAt, snoozedUntil)
}

/** `householdId` of an FCM command payload, or null if absent/blank. */
fun householdIdFromFcm(data: Map<String, String>): String? = data["householdId"]?.takeIf(String::isNotEmpty)

/** `status` of an FCM command payload, or null if absent/unknown. */
fun statusFromFcm(data: Map<String, String>): CommandStatus? = data["status"]?.let(::commandStatusFromString)

/** `reason` of an FCM command payload (set only for rejected commands). */
fun reasonFromFcm(data: Map<String, String>): String? = data["reason"]?.takeIf(String::isNotEmpty)

/**
 * Body of `households/{hid}/devices/{uid}`. Never includes ringtone or wallpaper data (only
 * content-addressed [MediaRef]s when [media] is passed).
 * `fcmToken` is omitted when unknown; `updatedAt` is a server timestamp. A skip that is not after
 * [now] is written as null: it is meaningless to the partner, who would send an UNSKIP that is always rejected.
 * A solo skip that is not after [now] is omitted likewise.
 *
 * Top-level newer keys are emitted only when there is something to say: `links` when [links] is
 * non-null (even empty), `defaultWallpaper` when [media] is non-null, `alarms[].media` for alarms
 * with an entry in [PublishedMedia.alarms]. Inside a non-null `ringing` map every key is always
 * written: `snoozedUntil`, `occurrenceAt` (Timestamp or null) and `merged` (possibly empty).
 *
 * The writer merges (SetOptions.merge), which merges nested maps deeply: an omitted key inside
 * `ringing` would keep the previous ring's value, hence the always-written keys above. An omitted
 * top-level key (`links`, `defaultWallpaper`) keeps its stored value, but `alarms` is an array and
 * is replaced whole, so `alarms[].media` survives only if [media] is passed every time.
 */
fun deviceDocMap(
    displayName: String,
    fcmToken: String?,
    alarms: List<Alarm>,
    ring: RingState?,
    now: Instant,
    zone: ZoneId,
    links: List<PublishedLink>? = null,
    media: PublishedMedia? = null,
): Map<String, Any?> = buildMap {
    put("displayName", displayName)
    if (fcmToken != null) put("fcmToken", fcmToken)
    put("updatedAt", FieldValue.serverTimestamp())
    put(
        "alarms",
        alarms.map { a ->
            buildMap {
                put("id", a.id)
                put("label", a.label)
                put("hour", a.hour)
                put("minute", a.minute)
                put("days", a.days.sorted().map { it.name })
                put("enabled", a.enabled)
                put("nextOccurrenceAt", nextOccurrence(a, now, zone)?.toTimestamp())
                put("skippedOccurrenceAt", a.skippedOccurrenceAt?.takeIf { it > now }?.toTimestamp())
                a.soloSkippedOccurrenceAt?.takeIf { it > now }?.let { put("soloSkippedOccurrenceAt", it.toTimestamp()) }
                media?.alarms?.get(a.id)?.let { m ->
                    put("media", mapOf("wallpaper" to m.wallpaper?.toMap(), "ringtone" to m.ringtone?.toMap(withTitle = true)))
                }
            }
        },
    )
    put(
        "ringing",
        ring?.let {
            buildMap {
                put("alarmId", it.alarmId)
                put("ringId", it.ringId)
                put("startedAt", it.startedAt.toTimestamp())
                put("snoozedUntil", it.snoozedUntil?.toTimestamp())
                put("occurrenceAt", it.occurrenceAt?.toTimestamp())
                put("merged", it.merged.map { m -> mapOf("alarmId" to m.alarmId, "occurrenceAt" to m.occurrenceAt.toTimestamp()) })
            }
        },
    )
    if (links != null) put("links", links.map { mapOf("ownerAlarmId" to it.ownerAlarmId, "mode" to it.mode.name) })
    if (media != null) put("defaultWallpaper", media.defaultWallpaper?.toMap())
}

private fun MediaRef.toMap(withTitle: Boolean = false): Map<String, Any?> {
    val ref = this
    return buildMap {
        put("sha", ref.sha)
        put("size", ref.size)
        if (withTitle) put("title", ref.title)
    }
}

private fun mediaRefFrom(v: Any?): MediaRef? {
    val m = v as? Map<*, *> ?: return null
    val sha = (m["sha"] as? String)?.takeIf(String::isNotEmpty) ?: return null
    val size = (m["size"] as? Number)?.toLong() ?: return null
    return MediaRef(sha, size, m["title"] as? String)
}

private fun alarmMediaFrom(v: Any?): AlarmMedia? {
    val m = v as? Map<*, *> ?: return null
    return AlarmMedia(mediaRefFrom(m["wallpaper"]), mediaRefFrom(m["ringtone"]))
}

private fun linkFrom(v: Any?): PublishedLink? {
    val m = v as? Map<*, *> ?: return null
    val id = m["ownerAlarmId"] as? String ?: return null
    val mode = LinkMode.entries.firstOrNull { it.name == m["mode"] } ?: return null
    return PublishedLink(id, mode)
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
        links = (data["links"] as? List<*>).orEmpty().mapNotNull(::linkFrom),
        defaultWallpaper = mediaRefFrom(data["defaultWallpaper"]),
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
        media = alarmMediaFrom(m["media"]),
        soloSkippedOccurrenceAt = (m["soloSkippedOccurrenceAt"] as? Timestamp)?.toInstantCompat(),
    )
}

private fun ringStateFrom(m: Map<*, *>): RingState? {
    val alarmId = m["alarmId"] as? String ?: return null
    val ringId = m["ringId"] as? String ?: return null
    val startedAt = (m["startedAt"] as? Timestamp)?.toInstantCompat() ?: return null
    val merged = (m["merged"] as? List<*>).orEmpty().mapNotNull { e ->
        val em = e as? Map<*, *> ?: return@mapNotNull null
        val id = em["alarmId"] as? String ?: return@mapNotNull null
        val at = (em["occurrenceAt"] as? Timestamp)?.toInstantCompat() ?: return@mapNotNull null
        RingMember(id, at)
    }
    return RingState(
        alarmId, ringId, startedAt, (m["snoozedUntil"] as? Timestamp)?.toInstantCompat(),
        occurrenceAt = (m["occurrenceAt"] as? Timestamp)?.toInstantCompat(),
        merged = merged,
    )
}
