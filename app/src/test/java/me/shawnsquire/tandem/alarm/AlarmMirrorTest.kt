package me.shawnsquire.tandem.alarm

import android.content.Context
import me.shawnsquire.tandem.awaitTandemStartup
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.PendingBoth
import me.shawnsquire.tandem.schedule.VibrationPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.DayOfWeek
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class AlarmMirrorTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val locked = LockedContext(context)

    /** TandemApp's startup reschedule writes the mirror; let it finish, then start each test empty. */
    @Before
    fun clearMirror() {
        awaitTandemStartup()
        check(deviceProtectedPrefs(context, "alarm_mirror").edit().clear().commit())
    }

    private val full = MirroredAlarm(
        Alarm(
            id = "a", label = "Work", hour = 6, minute = 30,
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY),
            enabled = true, skippedOccurrenceAt = Instant.parse("2026-03-02T11:30:00Z"),
            ringtoneUri = "content://media/1", wallpaperPath = null,
        ),
        nextTriggerAt = Instant.parse("2026-03-04T11:30:00Z"),
    )
    private val bare = MirroredAlarm(
        Alarm("b", "", 0, 0, emptySet(), false, null, null, null),
        nextTriggerAt = null,
    )
    private val snapshot = MirrorSnapshot(listOf(full, bare), snoozeMinutes = 7, defaultRingtoneUri = "content://media/9")

    private fun exercise(mirror: AlarmMirror, fresh: () -> AlarmMirror = { mirror }) {
        assertEquals(MirrorSnapshot.EMPTY, fresh().read())
        mirror.write(snapshot)
        assertEquals(snapshot, fresh().read())
        assertEquals(snapshot, fresh().readOrNull())
        mirror.update { it.copy(snoozeMinutes = null, defaultRingtoneUri = null, alarms = it.alarms.drop(1)) }
        assertEquals(MirrorSnapshot(listOf(bare), null, null), fresh().read())
    }

    @Test fun sharedPrefsMirrorRoundTripsEveryField() =
        exercise(SharedPrefsAlarmMirror(context)) { SharedPrefsAlarmMirror(context) }

    @Test fun inMemoryMirrorBehavesTheSame() = exercise(InMemoryAlarmMirror())

    @Test fun wallpaperIsNeverMirrored() {
        val mirror = SharedPrefsAlarmMirror(context)
        mirror.write(MirrorSnapshot(listOf(MirroredAlarm(full.alarm.copy(wallpaperPath = "/data/files/w.jpg"), null)), null, null))
        assertNull(SharedPrefsAlarmMirror(context).read().alarms.single().alarm.wallpaperPath)
    }

    private val plainLink = AlarmLink("o", "oa", LinkMode.RING, null, true, null, null, null, null)
    private val links = listOf(
        plainLink,
        plainLink.copy(mode = LinkMode.NOTIFY, joinerEnabled = false),
        plainLink.copy(
            ownerSkippedOccurrenceAt = Instant.parse("2026-03-02T11:30:00Z"),
            pendingBoth = PendingBoth(CommandType.SKIP_NEXT, Instant.parse("2026-03-04T11:30:00Z"), "c1", Instant.parse("2026-03-02T05:00:00Z")),
            overrideLabel = "Mine", overrideRingtoneUri = "content://media/2",
        ),
        plainLink.copy(pendingBoth = PendingBoth(CommandType.DISABLE, null, "c2")), // no createdAt: a legacy mirror
    )

    @Test fun linkAndSoloSkipRoundTrip() {
        val alarms = links.mapIndexed { i, link ->
            MirroredAlarm(
                full.alarm.copy(id = "l$i", link = link, soloSkippedOccurrenceAt = Instant.parse("2026-03-03T11:30:00Z").takeIf { i % 2 == 0 }),
                nextTriggerAt = null,
            )
        }
        val snap = MirrorSnapshot(alarms, null, null)
        SharedPrefsAlarmMirror(context).write(snap)
        assertEquals(snap, SharedPrefsAlarmMirror(context).readOrNull())
    }

    @Test fun vibrationAndSoundSettingsAndOverridesRoundTrip() {
        val link = plainLink.copy(overrideVibration = VibrationPattern.ESCALATING, overrideVibrationOn = false, overrideSoundOn = true)
        val alarms = listOf(
            MirroredAlarm(full.alarm.copy(id = "v1", vibration = VibrationPattern.HEARTBEAT, vibrationOn = false, soundOn = true), null),
            MirroredAlarm(full.alarm.copy(id = "v2", vibration = VibrationPattern.SLOW_WAVE, vibrationOn = true, soundOn = false, link = link), null),
            MirroredAlarm(full.alarm.copy(id = "v3", link = plainLink.copy(overrideSoundOn = false)), null),
        )
        val snap = MirrorSnapshot(alarms, null, null)
        SharedPrefsAlarmMirror(context).write(snap)
        assertEquals(snap, SharedPrefsAlarmMirror(context).readOrNull())
    }

    @Test fun mirrorWrittenBeforeVibrationDecodesWithDefaults() {
        val link = plainLink.copy(overrideLabel = "Mine")
        SharedPrefsAlarmMirror(context).write(MirrorSnapshot(listOf(MirroredAlarm(full.alarm.copy(link = link), null)), null, null))
        // Strip every v5 key, as an upgraded phone that has not opened the app still has the old record.
        val root = org.json.JSONObject(raw()!!)
        val o = root.getJSONArray("alarms").getJSONObject(0)
        listOf("vibration", "vibrationOn", "soundOn").forEach { o.remove(it) }
        o.getJSONObject("link").let { l -> listOf("overrideVibration", "overrideVibrationOn", "overrideSoundOn").forEach { l.remove(it) } }
        putRaw(root.toString())

        val alarm = SharedPrefsAlarmMirror(context).readOrNull()!!.alarms.single().alarm
        assertEquals(VibrationPattern.PULSE, alarm.vibration)
        assertTrue(alarm.vibrationOn)
        assertTrue(alarm.soundOn)
        assertEquals(link, alarm.link)
        assertNull(alarm.link!!.overrideVibration)
        assertNull(alarm.link!!.overrideVibrationOn)
        assertNull(alarm.link!!.overrideSoundOn)
    }

    @Test fun unknownPatternNamesDecodeAsPulseButAnUnknownOverrideAsNull() {
        val link = plainLink.copy(overrideVibration = VibrationPattern.RAPID)
        SharedPrefsAlarmMirror(context).write(MirrorSnapshot(listOf(MirroredAlarm(full.alarm.copy(vibration = VibrationPattern.RAPID, link = link), null)), null, null))
        val root = org.json.JSONObject(raw()!!)
        val o = root.getJSONArray("alarms").getJSONObject(0)
        o.put("vibration", "FROM_THE_FUTURE")
        o.getJSONObject("link").put("overrideVibration", "FROM_THE_FUTURE")
        putRaw(root.toString())

        val alarm = SharedPrefsAlarmMirror(context).readOrNull()!!.alarms.single().alarm
        assertEquals(VibrationPattern.PULSE, alarm.vibration)
        assertNull(alarm.link!!.overrideVibration)
    }

    @Test fun linkOverrideWallpaperIsNeverMirrored() {
        val link = plainLink.copy(overrideWallpaperPath = "/data/files/w.jpg")
        val written = MirrorSnapshot(listOf(MirroredAlarm(full.alarm.copy(link = link), null)), null, null)
        val expected = link.copy(overrideWallpaperPath = null)
        SharedPrefsAlarmMirror(context).write(written)
        assertEquals(expected, SharedPrefsAlarmMirror(context).read().alarms.single().alarm.link)
        assertEquals(expected, InMemoryAlarmMirror(written).read().alarms.single().alarm.link)
    }

    @Test fun mirrorWithoutLinkKeysDecodesStrictlyAsUnlinked() {
        SharedPrefsAlarmMirror(context).write(snapshot)
        val root = org.json.JSONObject(raw()!!)
        for (i in 0 until root.getJSONArray("alarms").length()) {
            val o = root.getJSONArray("alarms").getJSONObject(i)
            assertTrue(!o.has("link") && !o.has("soloSkippedOccurrenceAt"))
        }
        val alarm = SharedPrefsAlarmMirror(context).readOrNull()!!.alarms.first().alarm
        assertNull(alarm.link)
        assertNull(alarm.soloSkippedOccurrenceAt)
    }

    private fun putRaw(raw: String) = check(deviceProtectedPrefs(context, "alarm_mirror").edit().putString("snapshot", raw).commit())
    private fun raw() = deviceProtectedPrefs(context, "alarm_mirror").getString("snapshot", null)

    @Test fun garbageReadsAsEmptyButUnreadable() {
        for (garbage in listOf("not json", "[1,2]", "{\"alarms\":\"nope\"}")) {
            putRaw(garbage)
            assertEquals(garbage, MirrorSnapshot.EMPTY, SharedPrefsAlarmMirror(context).read())
            assertNull(garbage, SharedPrefsAlarmMirror(context).readOrNull())
        }
    }

    @Test fun absentIsUnreadable() {
        // The updater always writes a snapshot, even with no alarms, so a missing key never means "no alarms".
        assertNull(SharedPrefsAlarmMirror(context).readOrNull())
        SharedPrefsAlarmMirror(context).write(MirrorSnapshot.EMPTY)
        assertEquals(MirrorSnapshot.EMPTY, SharedPrefsAlarmMirror(context).readOrNull())
    }

    @Test fun oneUndecodableAlarmMakesTheSnapshotUnreadable() {
        SharedPrefsAlarmMirror(context).write(snapshot)
        val root = org.json.JSONObject(raw()!!)
        root.getJSONArray("alarms").put(org.json.JSONObject().put("id", "broken"))
        putRaw(root.toString())

        assertNull(SharedPrefsAlarmMirror(context).readOrNull())
        // The lenient read still has the settings and the decodable alarms.
        assertEquals(snapshot, SharedPrefsAlarmMirror(context).read())
    }

    @Test fun updateRefusesAnUnreadableMirror() {
        for (mirror in listOf(SharedPrefsAlarmMirror(context), InMemoryAlarmMirror(initial = null))) {
            putRaw("not json")
            val e = runCatching { mirror.update { snapshot } }.exceptionOrNull()
            assertTrue("$mirror: expected IllegalStateException, got $e", e is IllegalStateException)
            assertNull(mirror.readOrNull())
        }
        assertEquals("not json", raw())
    }

    @Test fun onlyTouchesDeviceStorage() = exercise(SharedPrefsAlarmMirror(locked)) { SharedPrefsAlarmMirror(locked) }
}
