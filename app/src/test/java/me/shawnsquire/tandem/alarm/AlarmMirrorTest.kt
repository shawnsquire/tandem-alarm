package me.shawnsquire.tandem.alarm

import android.content.Context
import me.shawnsquire.tandem.awaitTandemStartup
import me.shawnsquire.tandem.schedule.Alarm
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
