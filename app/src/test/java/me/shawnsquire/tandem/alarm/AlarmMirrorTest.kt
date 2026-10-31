package me.shawnsquire.tandem.alarm

import android.content.Context
import me.shawnsquire.tandem.schedule.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun garbageReadsAsEmpty() {
        for (garbage in listOf("not json", "[1,2]", "{\"alarms\":\"nope\"}")) {
            deviceProtectedPrefs(context, "alarm_mirror").edit().putString("snapshot", garbage).commit()
            assertEquals(garbage, MirrorSnapshot.EMPTY, SharedPrefsAlarmMirror(context).read())
        }
    }

    @Test fun onlyTouchesDeviceStorage() = exercise(SharedPrefsAlarmMirror(locked)) { SharedPrefsAlarmMirror(locked) }
}
