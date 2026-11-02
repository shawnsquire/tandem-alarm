package me.shawnsquire.tandem.sync

import me.shawnsquire.tandem.media.AlarmMedia
import me.shawnsquire.tandem.media.MediaRef
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.FIRE_GRACE
import me.shawnsquire.tandem.schedule.LateRing
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.WallpaperFrame
import kotlin.time.toJavaDuration
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreMappingTest {
    private val now = Instant.parse("2026-03-02T05:00:00Z") // Monday
    private val zone = ZoneId.of("UTC")

    @Test
    fun statusStrings() {
        for (s in CommandStatus.entries) {
            assertEquals(s, commandStatusFromString(commandStatusToString(s)))
        }
        assertEquals("pending", commandStatusToString(CommandStatus.PENDING))
        assertNull(commandStatusFromString("PENDING"))
    }

    @Test
    fun createMapKeysAndValues() {
        val occ = now.plusSeconds(3600)
        val m = commandCreateMap("u1", "u2", CommandType.SKIP_NEXT, "a1", occ, null, now)
        assertEquals(
            setOf("from", "to", "type", "alarmId", "createdAt", "expiresAt", "status", "occurrenceAt", "ringId"),
            m.keys,
        )
        assertEquals("SKIP_NEXT", m["type"])
        assertEquals("pending", m["status"])
        assertEquals(Timestamp(now.epochSecond, 0), m["createdAt"])
        assertEquals(Timestamp(now.epochSecond + 86_400, 0), m["expiresAt"])
        assertEquals(Timestamp(occ.epochSecond, 0), m["occurrenceAt"])
        assertNull(m["ringId"])
        assertTrue(m.values.none { it is Long })
    }

    @Test
    fun commandDocRoundTrip() {
        val occ = now.plusSeconds(60)
        val m = commandCreateMap("u1", "u2", CommandType.DISABLE, "a1", occ, "r1", now)
        val c = commandFromDoc("c1", m)
        assertEquals(Command("c1", "u1", "u2", CommandType.DISABLE, "a1", occ, "r1", now, now.plusSeconds(86_400)), c)
    }

    @Test
    fun commandDocInvalid() {
        val good = commandCreateMap("u1", "u2", CommandType.ENABLE, "a1", null, null, now)
        assertNull(commandFromDoc("c", good + ("type" to "NOPE")))
        assertNull(commandFromDoc("c", good - "to"))
        assertNull(commandFromDoc("c", good + ("createdAt" to null)))
        assertNull(commandFromDoc("c", good + ("expiresAt" to 5L)))
    }

    private val fcm = mapOf(
        "householdId" to "h1", "commandId" to "c1", "fromUid" to "u1", "to" to "u2",
        "type" to "STOP_RINGING", "alarmId" to "a1", "ringId" to "r1",
        "createdAt" to "1700000000000", "expiresAt" to "1700086400000", "status" to "pending",
    )

    @Test
    fun fcmParses() {
        assertEquals(
            Command(
                "c1", "u1", "u2", CommandType.STOP_RINGING, "a1", null, "r1",
                Instant.ofEpochMilli(1_700_000_000_000), Instant.ofEpochMilli(1_700_086_400_000),
            ),
            commandFromFcm(fcm),
        )
        assertEquals("h1", householdIdFromFcm(fcm))
        assertEquals(CommandStatus.PENDING, statusFromFcm(fcm))
        assertNull(reasonFromFcm(fcm))
        val withOcc = fcm + ("occurrenceAt" to "1700000060000") + ("status" to "rejected") + ("reason" to "expired")
        assertEquals(Instant.ofEpochMilli(1_700_000_060_000), commandFromFcm(withOcc)?.occurrenceAt)
        assertEquals(CommandStatus.REJECTED, statusFromFcm(withOcc))
        assertEquals("expired", reasonFromFcm(withOcc))
    }

    @Test
    fun fcmMalformed() {
        assertNull(commandFromFcm(fcm - "commandId"))
        assertNull(commandFromFcm(fcm - "fromUid"))
        assertNull(commandFromFcm(fcm + ("type" to "x")))
        assertNull(commandFromFcm(fcm + ("createdAt" to "abc")))
        assertNull(commandFromFcm(fcm + ("occurrenceAt" to "abc")))
        assertNull(householdIdFromFcm(fcm - "householdId"))
        assertNull(statusFromFcm(fcm + ("status" to "weird")))
    }

    private val alarm = Alarm(
        id = "a1", label = "Work", hour = 7, minute = 30,
        days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        enabled = true, skippedOccurrenceAt = null,
        ringtoneUri = "content://secret/ringtone", wallpaperPath = "/data/secret/wall.jpg",
    )

    @Test
    fun deviceDocNeverLeaksRingtoneOrWallpaper() {
        val m = deviceDocMap("Pixel", "tok", listOf(alarm), null, now, zone)
        val s = m.toString()
        assertFalse(s.contains("ringtone", ignoreCase = true))
        assertFalse(s.contains("wallpaper", ignoreCase = true))
        assertFalse(s.contains("secret"))
        val a = (m["alarms"] as List<*>).single() as Map<*, *>
        assertEquals(
            setOf("id", "label", "hour", "minute", "days", "enabled", "nextOccurrenceAt", "skippedOccurrenceAt",
                "vibration", "vibrationOn", "soundOn", "textPosition"),
            a.keys,
        )
    }

    @Test
    fun deviceDocShape() {
        val m = deviceDocMap("Pixel", null, listOf(alarm), null, now, zone)
        assertFalse(m.containsKey("fcmToken"))
        assertTrue(m.containsKey("ringing") && m["ringing"] == null)
        assertEquals(FieldValue.serverTimestamp(), m["updatedAt"])
        val a = (m["alarms"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("MONDAY", "WEDNESDAY", "FRIDAY"), a["days"])
        assertEquals(Timestamp(Instant.parse("2026-03-02T07:30:00Z").epochSecond, 0), a["nextOccurrenceAt"])
        assertEquals("tok", deviceDocMap("P", "tok", emptyList(), null, now, zone)["fcmToken"])
    }

    @Test
    fun deviceDocOmitsPassedSkip() {
        val past = alarm.copy(skippedOccurrenceAt = now.minusSeconds(60))
        val atNow = alarm.copy(skippedOccurrenceAt = now)
        val future = alarm.copy(skippedOccurrenceAt = now.plusSeconds(60))
        val skips = deviceDocMap("P", null, listOf(past, atNow, future), null, now, zone)
            .let { (it["alarms"] as List<*>).map { a -> (a as Map<*, *>)["skippedOccurrenceAt"] } }
        assertEquals(listOf(null, null, now.plusSeconds(60).toTimestamp()), skips)
    }

    @Test
    fun deviceDocRoundTrip() {
        val skip = Instant.parse("2026-03-04T07:30:00Z")
        val a2 = alarm.copy(id = "a2", days = emptySet(), skippedOccurrenceAt = skip, enabled = false)
        val ring = RingState("a1", "r1", now, now.plusSeconds(300))
        val seen = Instant.parse("2026-03-02T05:00:01Z")
        val m = deviceDocMap("Pixel", "tok", listOf(alarm, a2), ring, now, zone) + ("updatedAt" to Timestamp(seen.epochSecond, 0))
        val expected = PartnerState(
            displayName = "Pixel",
            alarms = listOf(
                PartnerAlarm("a1", "Work", 7, 30, alarm.days, true, Instant.parse("2026-03-02T07:30:00Z"), null),
                PartnerAlarm("a2", "Work", 7, 30, emptySet(), false, null, skip),
            ),
            ringing = ring,
            lastSeen = seen,
        )
        assertEquals(expected, partnerStateFromDoc(m))
    }

    @Test
    fun partnerStateToleratesGarbage() {
        assertNull(partnerStateFromDoc(emptyMap()))
        val s = partnerStateFromDoc(
            mapOf(
                "displayName" to "P",
                "updatedAt" to null,
                "alarms" to listOf(
                    mapOf("id" to "x"),
                    "junk",
                    mapOf("id" to "ok", "label" to "L", "hour" to 6L, "minute" to 5L, "enabled" to true, "days" to listOf("TUESDAY", "FUNDAY")),
                ),
                "ringing" to mapOf("alarmId" to "a"),
            ),
        )!!
        assertNull(s.lastSeen)
        assertNull(s.ringing)
        assertEquals(1, s.alarms.size)
        assertEquals(setOf(DayOfWeek.TUESDAY), s.alarms[0].days)
        assertEquals(6, s.alarms[0].hour)
    }

    @Test
    fun snoozedUntilAbsentFromCreateMapWhenNull() {
        val m = commandCreateMap("u1", "u2", CommandType.SKIP_NEXT, "a1", null, null, now)
        assertFalse(m.containsKey("snoozedUntil"))
        assertNull(commandFromDoc("c1", m)?.snoozedUntil)
    }

    @Test
    fun snoozedUntilDocRoundTrip() {
        val until = now.plusSeconds(540)
        val m = commandCreateMap("u1", "u2", CommandType.SNOOZE, "a1", null, "r1", now, until)
        assertEquals(Timestamp(until.epochSecond, 0), m["snoozedUntil"])
        assertEquals(
            Command("c1", "u1", "u2", CommandType.SNOOZE, "a1", null, "r1", now, now.plusSeconds(86_400), until),
            commandFromDoc("c1", m),
        )
        assertNull(commandFromDoc("c1", m + ("snoozedUntil" to 5L))?.snoozedUntil)
    }

    @Test
    fun snoozedUntilFcmRoundTrip() {
        val until = Instant.ofEpochMilli(1_700_000_540_000)
        val data = fcm + ("type" to "SNOOZE") + ("snoozedUntil" to until.toEpochMilli().toString())
        assertEquals(until, commandFromFcm(data)?.snoozedUntil)
        assertNull(commandFromFcm(fcm)?.snoozedUntil)
        assertNull(commandFromFcm(data + ("snoozedUntil" to "abc")))
    }

    @Test
    fun deviceDocDefaultsHaveNoNewKeys() {
        val ring = RingState("a1", "r1", now, null)
        val m = deviceDocMap("P", null, listOf(alarm), ring, now, zone)
        assertFalse(m.containsKey("links"))
        assertFalse(m.containsKey("defaultWallpaper"))
        assertEquals(
            setOf("alarmId", "ringId", "startedAt", "snoozedUntil", "occurrenceAt", "merged"),
            (m["ringing"] as Map<*, *>).keys,
        )
    }

    @Test
    fun ringingMapAlwaysCarriesOccurrenceAndMerged() {
        // SetOptions.merge merges nested maps deeply, so omitted keys would keep the previous ring's values.
        val bare = deviceDocMap("P", null, listOf(alarm), RingState("a1", "r1", now, null), now, zone)["ringing"] as Map<*, *>
        assertTrue(bare.containsKey("occurrenceAt") && bare["occurrenceAt"] == null)
        assertEquals(emptyList<Any>(), bare["merged"])
        val occ = now.plusSeconds(60)
        val full = deviceDocMap(
            "P", null, listOf(alarm),
            RingState("a1", "r1", now, null, occurrenceAt = occ, merged = listOf(RingMember("a2", occ))), now, zone,
        )["ringing"] as Map<*, *>
        assertEquals(occ.toTimestamp(), full["occurrenceAt"])
        assertEquals(listOf(mapOf("alarmId" to "a2", "occurrenceAt" to occ.toTimestamp())), full["merged"])
    }

    @Test
    fun ringingPublishesTheOwnerIdForLinkedMembers() {
        // The partner's Partner tab sends STOP_RINGING{alarmId} back; the joiner maps only the owner's id.
        val occ = now.plusSeconds(60)
        val ring = RingState("L1", "r1", now, null, occ, listOf(RingMember("L2", occ), RingMember("a2", occ)))
        val m = deviceDocMap("P", null, listOf(alarm), ring, now, zone, ringOwnerIds = mapOf("L1" to "o1", "L2" to "o2"))
        val ringing = m["ringing"] as Map<*, *>
        assertEquals("o1", ringing["alarmId"])
        assertEquals(listOf("o2", "a2"), (ringing["merged"] as List<*>).map { (it as Map<*, *>)["alarmId"] })
        assertEquals("r1", ringing["ringId"])
        // Linked rows are never published as alarms[].
        assertEquals(listOf("a1"), (m["alarms"] as List<*>).map { (it as Map<*, *>)["id"] })
    }

    @Test
    fun deviceDocLinksAndMediaRoundTrip() {
        val solo = now.plusSeconds(7200)
        val wall = MediaRef("w1", 100L)
        val tone = MediaRef("t1", 2000L, "Birdsong")
        val def = MediaRef("d1", 5L)
        val a2 = alarm.copy(id = "a2", soloSkippedOccurrenceAt = solo)
        val past = alarm.copy(id = "a3", soloSkippedOccurrenceAt = now)
        val links = listOf(PublishedLink("o1", LinkMode.RING), PublishedLink("o2", LinkMode.NOTIFY))
        val media = PublishedMedia(def, mapOf("a1" to AlarmMedia(wall, tone), "a2" to AlarmMedia(null, null)))
        val occ = Instant.parse("2026-03-02T07:30:00Z")
        val ring = RingState("a1", "r1", now, null, occ, listOf(RingMember("a2", occ.plusSeconds(60))))
        val m = deviceDocMap("P", null, listOf(alarm, a2, past), ring, now, zone, links, media)
        val alarmMaps = (m["alarms"] as List<*>).map { it as Map<*, *> }
        assertFalse(alarmMaps[0].containsKey("soloSkippedOccurrenceAt"))
        assertFalse(alarmMaps[2].containsKey("soloSkippedOccurrenceAt"))
        assertFalse(alarmMaps[2].containsKey("media"))
        val s = partnerStateFromDoc(m + ("updatedAt" to Timestamp(now.epochSecond, 0)))!!
        assertEquals(links, s.links)
        assertEquals(def, s.defaultWallpaper)
        assertEquals(AlarmMedia(wall, tone), s.alarms[0].media)
        assertEquals(AlarmMedia(null, null), s.alarms[1].media)
        assertNull(s.alarms[2].media)
        assertEquals(solo, s.alarms[1].soloSkippedOccurrenceAt)
        assertNull(s.alarms[2].soloSkippedOccurrenceAt)
        assertEquals(ring, s.ringing)
        assertEquals(emptyList<PublishedLink>(), partnerStateFromDoc(deviceDocMap("P", null, listOf(alarm), null, now, zone, emptyList()))!!.links)
        val cleared = deviceDocMap("P", null, listOf(alarm), null, now, zone, null, PublishedMedia(null, emptyMap()))
        assertTrue(cleared.containsKey("defaultWallpaper") && cleared["defaultWallpaper"] == null)
    }

    private fun linkOf(vararg extra: Pair<String, Any?>) =
        partnerStateFromDoc(mapOf("displayName" to "P", "links" to listOf(mapOf("ownerAlarmId" to "o", "mode" to "RING") + extra)))!!.links.single()

    @Test
    fun linkScheduleRoundTripsIncludingOneShot() {
        val links = listOf(
            PublishedLink("o1", LinkMode.RING, 6, 30, setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)),
            PublishedLink("o2", LinkMode.NOTIFY, 0, 0, emptySet()),
            PublishedLink("o3", LinkMode.NOTIFY),
        )
        val m = deviceDocMap("P", null, listOf(alarm), null, now, zone, links)
        val maps = (m["links"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("MONDAY", "WEDNESDAY"), maps[0]["days"])
        assertEquals(setOf("ownerAlarmId", "mode"), maps[2].keys)
        assertEquals(links, partnerStateFromDoc(m)!!.links)
    }

    @Test
    fun linkWithoutAScheduleParsesWithNullSchedule() {
        val l = linkOf()
        assertEquals(PublishedLink("o", LinkMode.RING), l)
        assertNull(l.hour)
        assertNull(l.days)
    }

    @Test
    fun linkScheduleIsAllOrNothing() {
        val good = arrayOf("hour" to 6L, "minute" to 30L, "days" to listOf("MONDAY"))
        assertEquals(PublishedLink("o", LinkMode.RING, 6, 30, setOf(DayOfWeek.MONDAY)), linkOf(*good))
        val bad = listOf(
            mapOf("hour" to 6L, "days" to listOf("MONDAY")),
            mapOf("hour" to 24L, "minute" to 30L, "days" to listOf("MONDAY")),
            mapOf("hour" to 6L, "minute" to 60L, "days" to listOf("MONDAY")),
            mapOf("hour" to 6L, "minute" to 30L, "days" to listOf("MONDAY", "FUNDAY")),
            mapOf("hour" to 6L, "minute" to 30L, "days" to "MONDAY"),
            mapOf("hour" to 6L, "minute" to 30L),
        )
        for (b in bad) assertEquals(b.toString(), PublishedLink("o", LinkMode.RING), linkOf(*b.toList().toTypedArray()))
    }

    @Test
    fun lateRingRoundTripsAndExpiresAfterTheGrace() {
        val x = now.plusSeconds(1800)
        val yes = alarm.copy(id = "y", lateRing = LateRing(x, true))
        val no = alarm.copy(id = "n", lateRing = LateRing(x, false))
        val s = partnerStateFromDoc(deviceDocMap("P", null, listOf(yes, no), null, now, zone))!!
        assertEquals(LateRing(x, true), s.alarms[0].lateRing)
        assertEquals(LateRing(x, false), s.alarms[1].lateRing)
        val at = x.plus(FIRE_GRACE.toJavaDuration())
        val just = deviceDocMap("P", null, listOf(yes), null, at.minusMillis(1), zone)
        assertTrue((just["alarms"] as List<*>).first().let { (it as Map<*, *>).containsKey("lateRing") })
        val gone = deviceDocMap("P", null, listOf(yes), null, at, zone)
        assertFalse((gone["alarms"] as List<*>).first().let { (it as Map<*, *>).containsKey("lateRing") })
        assertFalse((deviceDocMap("P", null, listOf(alarm), null, now, zone)["alarms"] as List<*>).first().let { (it as Map<*, *>).containsKey("lateRing") })
    }

    @Test
    fun vibrationAndSwitchesRoundTrip() {
        val a = alarm.copy(vibration = VibrationPattern.HEARTBEAT, vibrationOn = false, soundOn = false)
        val entry = (deviceDocMap("P", null, listOf(a), null, now, zone)["alarms"] as List<*>).first() as Map<*, *>
        assertEquals("HEARTBEAT", entry["vibration"])
        assertEquals(false, entry["vibrationOn"])
        assertEquals(false, entry["soundOn"])
        val p = partnerStateFromDoc(deviceDocMap("P", null, listOf(a), null, now, zone))!!.alarms.single()
        assertEquals(VibrationPattern.HEARTBEAT, p.vibration)
        assertFalse(p.vibrationOn)
        assertFalse(p.soundOn)
    }

    @Test
    fun missingOrUnreadableVibrationFieldsDefaultToPulseAndOn() {
        val base = mapOf("id" to "a", "hour" to 6L, "minute" to 5L, "enabled" to true)
        val s = partnerStateFromDoc(
            mapOf(
                "displayName" to "P",
                "alarms" to listOf(
                    base,
                    base + mapOf("vibration" to "TSUNAMI", "vibrationOn" to "no", "soundOn" to 0L),
                    base + mapOf("vibration" to 3L),
                ),
            ),
        )!!
        assertEquals(3, s.alarms.size)
        assertTrue(s.alarms.all { it.vibration == VibrationPattern.PULSE && it.vibrationOn && it.soundOn })
    }

    @Test
    fun frameAndTextPositionRoundTripBesideTheMediaRefs() {
        val frame = WallpaperFrame(2.5f, 0.25f, 0.75f)
        val defFrame = WallpaperFrame(1.5f, 0.5f, 0.125f)
        val a = alarm.copy(wallpaperFrame = frame, textPosition = TextPosition.BOTTOM)
        val wall = MediaRef("w".repeat(64), 5)
        val media = PublishedMedia(MediaRef("d".repeat(64), 7), mapOf("a1" to AlarmMedia(wall, null)))
        val m = deviceDocMap("P", null, listOf(a), null, now, zone, null, media, defaultWallpaperFrame = defFrame)
        val entry = (m["alarms"] as List<*>).single() as Map<*, *>
        assertEquals("BOTTOM", entry["textPosition"])
        assertEquals(mapOf("zoom" to 2.5, "x" to 0.25, "y" to 0.75), entry["wallpaperFrame"])
        assertEquals(mapOf("zoom" to 1.5, "x" to 0.5, "y" to 0.125), m["defaultWallpaperFrame"])
        // Nothing of the framing leaks into the content-addressed refs.
        assertEquals(setOf("sha", "size"), ((entry["media"] as Map<*, *>)["wallpaper"] as Map<*, *>).keys)
        assertEquals(setOf("sha", "size"), (m["defaultWallpaper"] as Map<*, *>).keys)
        val s = partnerStateFromDoc(m + ("updatedAt" to Timestamp(now.epochSecond, 0)))!!
        assertEquals(frame, s.alarms.single().wallpaperFrame)
        assertEquals(TextPosition.BOTTOM, s.alarms.single().textPosition)
        assertEquals(AlarmMedia(wall, null), s.alarms.single().media)
        assertEquals(defFrame, s.defaultWallpaperFrame)
    }

    @Test
    fun anAlarmWithoutAFrameOmitsItAndAClearedDefaultFrameIsWrittenNull() {
        val entry = (deviceDocMap("P", null, listOf(alarm), null, now, zone)["alarms"] as List<*>).single() as Map<*, *>
        assertFalse(entry.containsKey("wallpaperFrame"))
        assertEquals("MIDDLE", entry["textPosition"])
        // The writer merges, so a reset default frame must replace the stored one with an explicit null.
        val cleared = deviceDocMap("P", null, listOf(alarm), null, now, zone, null, PublishedMedia(null, emptyMap()))
        assertTrue(cleared.containsKey("defaultWallpaperFrame") && cleared["defaultWallpaperFrame"] == null)
        assertFalse(deviceDocMap("P", null, listOf(alarm), null, now, zone).containsKey("defaultWallpaperFrame"))
        val s = partnerStateFromDoc(cleared)!!
        assertNull(s.defaultWallpaperFrame)
        assertNull(s.alarms.single().wallpaperFrame)
    }

    @Test
    fun anOlderDocWithoutFrameFieldsParsesCentredAtMiddle() {
        val s = partnerStateFromDoc(
            mapOf("displayName" to "P", "alarms" to listOf(mapOf("id" to "a", "hour" to 6L, "minute" to 5L, "enabled" to true))),
        )!!
        assertNull(s.alarms.single().wallpaperFrame)
        assertEquals(TextPosition.MIDDLE, s.alarms.single().textPosition)
        assertNull(s.defaultWallpaperFrame)
    }

    @Test
    fun unknownOrMalformedFrameAndPositionNeverDropTheAlarmOrDoc() {
        val base = mapOf("id" to "a", "hour" to 6L, "minute" to 5L, "enabled" to true)
        val s = partnerStateFromDoc(
            mapOf(
                "displayName" to "P",
                "defaultWallpaperFrame" to mapOf("zoom" to 2.0, "x" to Double.NaN, "y" to 0.5),
                "alarms" to listOf(
                    base + mapOf("textPosition" to "SIDEWAYS", "wallpaperFrame" to "x"),
                    base + mapOf("textPosition" to 3L, "wallpaperFrame" to mapOf("zoom" to 2.0, "x" to 0.5)),
                    base + mapOf("wallpaperFrame" to mapOf("zoom" to "2", "x" to 0.5, "y" to 0.5)),
                    base + mapOf("wallpaperFrame" to mapOf("zoom" to Double.NaN, "x" to 0.5, "y" to 0.5)),
                    base + mapOf("wallpaperFrame" to mapOf("zoom" to Double.POSITIVE_INFINITY, "x" to 0.5, "y" to 0.5)),
                ),
            ),
        )!!
        assertEquals(5, s.alarms.size)
        assertTrue(s.alarms.all { it.wallpaperFrame == null && it.textPosition == TextPosition.MIDDLE })
        assertNull(s.defaultWallpaperFrame)
    }

    @Test
    fun anOutOfRangeFrameIsSanitized() {
        val base = mapOf("id" to "a", "hour" to 6L, "minute" to 5L, "enabled" to true)
        val s = partnerStateFromDoc(
            mapOf(
                "displayName" to "P",
                "alarms" to listOf(base + ("wallpaperFrame" to mapOf("zoom" to 99L, "x" to -3L, "y" to 7.5))),
            ),
        )!!
        assertEquals(WallpaperFrame(4f, 0f, 1f), s.alarms.single().wallpaperFrame)
    }

    @Test
    fun malformedLateRingParsesAsNullWithoutDroppingTheAlarm() {
        val base = mapOf("id" to "a", "hour" to 6L, "minute" to 5L, "enabled" to true)
        val s = partnerStateFromDoc(
            mapOf(
                "displayName" to "P",
                "alarms" to listOf(
                    base + ("lateRing" to mapOf("occurrenceAt" to Timestamp(5, 0))),
                    base + ("lateRing" to mapOf("ring" to true)),
                    base + ("lateRing" to "x"),
                ),
            ),
        )!!
        assertEquals(3, s.alarms.size)
        assertTrue(s.alarms.all { it.lateRing == null })
    }

    @Test
    fun malformedNewFieldsNeverDropAlarmOrRing() {
        val s = partnerStateFromDoc(
            mapOf(
                "displayName" to "P",
                "links" to listOf(mapOf("ownerAlarmId" to "o", "mode" to "WEIRD"), "junk", mapOf("ownerAlarmId" to "o", "mode" to "RING")),
                "defaultWallpaper" to "nope",
                "alarms" to listOf(
                    mapOf(
                        "id" to "ok", "hour" to 6L, "minute" to 5L, "enabled" to true,
                        "media" to mapOf("wallpaper" to mapOf("sha" to 3), "ringtone" to mapOf("sha" to "t", "size" to 7)),
                        "soloSkippedOccurrenceAt" to "x",
                    ),
                    mapOf("id" to "ok2", "hour" to 6L, "minute" to 5L, "enabled" to true, "media" to 5),
                ),
                "ringing" to mapOf(
                    "alarmId" to "a", "ringId" to "r", "startedAt" to Timestamp(1, 0),
                    "occurrenceAt" to "bad", "merged" to listOf("x", mapOf("alarmId" to "m"), mapOf("alarmId" to "m", "occurrenceAt" to Timestamp(5, 0))),
                ),
            ),
        )!!
        assertEquals(listOf(PublishedLink("o", LinkMode.RING)), s.links)
        assertNull(s.defaultWallpaper)
        assertEquals(2, s.alarms.size)
        assertEquals(AlarmMedia(null, MediaRef("t", 7L)), s.alarms[0].media)
        assertNull(s.alarms[0].soloSkippedOccurrenceAt)
        assertNull(s.alarms[1].media)
        val ring = s.ringing!!
        assertNull(ring.occurrenceAt)
        assertEquals(listOf(RingMember("m", Instant.ofEpochSecond(5))), ring.merged)
    }

    @Test
    fun householdShapes() {
        assertEquals(Household("h", "me", "you"), householdFrom("h", "me", listOf("you", "me")))
        assertNull(householdFrom("h", "me", listOf("me")))
        assertNull(householdFrom("h", "me", listOf("a", "b")))
        assertNull(householdFrom("h", "me", listOf("me", "me")))
        assertNull(householdFrom("h", "me", null))
    }
}
