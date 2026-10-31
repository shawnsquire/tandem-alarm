package me.shawnsquire.tandem.sync

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
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
    fun householdShapes() {
        assertEquals(Household("h", "me", "you"), householdFrom("h", "me", listOf("you", "me")))
        assertNull(householdFrom("h", "me", listOf("me")))
        assertNull(householdFrom("h", "me", listOf("a", "b")))
        assertNull(householdFrom("h", "me", listOf("me", "me")))
        assertNull(householdFrom("h", "me", null))
    }
}
