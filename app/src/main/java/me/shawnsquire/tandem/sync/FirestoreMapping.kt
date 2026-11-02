package me.shawnsquire.tandem.sync

import me.shawnsquire.tandem.data.toVibrationPattern
import me.shawnsquire.tandem.media.AlarmMedia
import me.shawnsquire.tandem.media.MediaRef
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.toJavaDuration
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.FIRE_GRACE
import me.shawnsquire.tandem.schedule.LateRing
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.WallpaperFrame
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
 * A solo skip that is not after [now] is omitted likewise. `alarms[].lateRing` (`occurrenceAt` Timestamp,
 * `ring` Boolean) is written only while its `occurrenceAt` is within [FIRE_GRACE] of [now] or later.
 * `alarms[].vibration` (the pattern's name), `vibrationOn` and `soundOn` are always written, and so is
 * `alarms[].textPosition` (its name); `alarms[].wallpaperFrame` (`{zoom, x, y}`) only when the alarm has a frame.
 * The frame and position sit beside `media`, never inside it, so they never touch a media hash.
 * A link's `hour`, `minute` and `days` are written only when its schedule is known.
 *
 * Top-level newer keys are emitted only when there is something to say: `links` when [links] is
 * non-null (even empty), `defaultWallpaper` and `defaultWallpaperFrame` (null when [defaultWallpaperFrame]
 * is, so a cleared frame replaces the stored one) when [media] is non-null, `alarms[].media` for alarms
 * with an entry in [PublishedMedia.alarms]. Inside a non-null `ringing` map every key is always
 * written: `snoozedUntil`, `occurrenceAt` (Timestamp or null) and `merged` (possibly empty).
 * `ringing.alarmId` and `merged[].alarmId` name a linked row by its owner's alarm id, looked up in
 * [ringOwnerIds] (row id to `link.ownerAlarmId`): the owner knows only that id, and its Partner-tab
 * STOP_RINGING carries it back for the joiner to map to the row. Other ids are published as they are.
 *
 * The writer merges (SetOptions.merge), which merges nested maps deeply: an omitted key inside
 * `ringing` would keep the previous ring's value, hence the always-written keys above. An omitted
 * top-level key (`links`, `defaultWallpaper`, `defaultWallpaperFrame`) keeps its stored value, but `alarms` is an array and
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
    ringOwnerIds: Map<String, String> = emptyMap(),
    defaultWallpaperFrame: WallpaperFrame? = null,
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
                a.lateRing?.takeIf { it.occurrenceAt.plus(FIRE_GRACE.toJavaDuration()) > now }?.let {
                    put("lateRing", mapOf("occurrenceAt" to it.occurrenceAt.toTimestamp(), "ring" to it.ring))
                }
                put("vibration", a.vibration.name)
                put("vibrationOn", a.vibrationOn)
                put("soundOn", a.soundOn)
                put("textPosition", a.textPosition.name)
                a.wallpaperFrame?.let { put("wallpaperFrame", it.toMap()) }
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
                put("alarmId", ringOwnerIds[it.alarmId] ?: it.alarmId)
                put("ringId", it.ringId)
                put("startedAt", it.startedAt.toTimestamp())
                put("snoozedUntil", it.snoozedUntil?.toTimestamp())
                put("occurrenceAt", it.occurrenceAt?.toTimestamp())
                put("merged", it.merged.map { m -> mapOf("alarmId" to (ringOwnerIds[m.alarmId] ?: m.alarmId), "occurrenceAt" to m.occurrenceAt.toTimestamp()) })
            }
        },
    )
    if (links != null) put("links", links.map { it.toMap() })
    if (media != null) {
        put("defaultWallpaper", media.defaultWallpaper?.toMap())
        put("defaultWallpaperFrame", defaultWallpaperFrame?.toMap())
    }
}

private fun WallpaperFrame.toMap(): Map<String, Any?> = mapOf("zoom" to zoom.toDouble(), "x" to focusX.toDouble(), "y" to focusY.toDouble())

/** A frame from a doc value; null (centred) when absent, not a map, a part missing or not a finite number. */
private fun wallpaperFrameFrom(v: Any?): WallpaperFrame? {
    val m = v as? Map<*, *> ?: return null
    fun part(key: String): Float? = (m[key] as? Number)?.toFloat()?.takeIf { it.isFinite() }
    return WallpaperFrame(part("zoom") ?: return null, part("x") ?: return null, part("y") ?: return null).sanitized()
}

private fun PublishedLink.toMap(): Map<String, Any?> = buildMap {
    put("ownerAlarmId", ownerAlarmId)
    put("mode", mode.name)
    if (hour != null && minute != null && days != null) {
        put("hour", hour)
        put("minute", minute)
        put("days", days.sorted().map { it.name })
    }
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
    // All-or-nothing, and bad day names are not dropped as partnerAlarmFrom does: a dropped day would make
    // the owner see a false "not confirmed". The schedule's presence is also the only sign that the joiner
    // reads `lateRing`; an older joiner would ignore a Skip answer, so it must read as unknown and the
    // owner won't prompt.
    val hour = (m["hour"] as? Number)?.toInt()?.takeIf { it in 0..23 }
    val minute = (m["minute"] as? Number)?.toInt()?.takeIf { it in 0..59 }
    val days = (m["days"] as? List<*>)?.map { n -> DayOfWeek.entries.firstOrNull { it.name == n } }
        ?.takeIf { d -> d.all { it != null } }?.filterNotNull()?.toSet()
    if (hour == null || minute == null || days == null) return PublishedLink(id, mode)
    return PublishedLink(id, mode, hour, minute, days)
}

private fun lateRingFrom(v: Any?): LateRing? {
    val m = v as? Map<*, *> ?: return null
    val at = (m["occurrenceAt"] as? Timestamp)?.toInstantCompat() ?: return null
    val ring = m["ring"] as? Boolean ?: return null
    return LateRing(at, ring)
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
        defaultWallpaperFrame = wallpaperFrameFrom(data["defaultWallpaperFrame"]),
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
        lateRing = lateRingFrom(m["lateRing"]),
        // A missing field (an older partner build) or an unreadable one (a newer build's pattern) takes the default.
        vibration = (m["vibration"] as? String)?.toVibrationPattern() ?: VibrationPattern.PULSE,
        vibrationOn = m["vibrationOn"] as? Boolean ?: true,
        soundOn = m["soundOn"] as? Boolean ?: true,
        // Same for an older partner (no field) or a newer one (an unknown position): centred, the plain crop.
        textPosition = TextPosition.entries.firstOrNull { it.name == m["textPosition"] } ?: TextPosition.MIDDLE,
        wallpaperFrame = wallpaperFrameFrom(m["wallpaperFrame"]),
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
