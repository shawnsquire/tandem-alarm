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
            setOf("id", "label", "hour", "minute", "days", "enabled", "nextOccurrenceAt", "skippedOccurrenceAt"),
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
