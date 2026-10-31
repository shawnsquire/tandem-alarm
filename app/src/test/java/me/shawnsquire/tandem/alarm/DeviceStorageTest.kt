package me.shawnsquire.tandem.alarm

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class DeviceStorageTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val t1 = Instant.parse("2026-03-02T07:10:00Z")
    private val t2 = Instant.parse("2026-03-02T07:20:00Z")

    /** TandemApp's startup builds the AlarmEngine, which already migrated; start each test unmigrated. */
    @Before
    fun clearMigrationMarker() {
        check(context.createDeviceProtectedStorageContext().getSharedPreferences("alarm_storage", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private fun cePrefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun writeCe(name: String, key: String, value: String) =
        check(cePrefs(name).edit().putString(key, value).commit())

    @Test
    fun unlockedMigrationMovesAllThreeFilesToDeviceStorage() {
        writeCe("alarm_snoozes", "ring-1", "${t1.toEpochMilli()}|a")
        check(cePrefs("alarm_fired").edit().putLong("a", t2.toEpochMilli()).commit())
        check(cePrefs("alarm_scheduler").edit().putStringSet("scheduled_records", setOf("1|a")).commit())

        migrateAlarmPrefsToDeviceStorage(context, unlocked = true)

        val locked = LockedContext(context)
        assertEquals(SnoozeEntry("ring-1", "a", t1), SharedPrefsSnoozeLedger(locked).get("ring-1"))
        assertEquals(t2, SharedPrefsFiredLedger(locked).lastRang("a"))
        val de = context.createDeviceProtectedStorageContext()
        assertEquals(setOf("1|a"), de.getSharedPreferences("alarm_scheduler", Context.MODE_PRIVATE).getStringSet("scheduled_records", null))
        assertTrue(cePrefs("alarm_snoozes").all.isEmpty())
    }

    @Test
    fun lockedMigrationMovesNothingAndALaterUnlockedCallKeepsDeviceData() {
        check(cePrefs("alarm_fired").edit().putLong("a", t1.toEpochMilli()).commit())

        migrateAlarmPrefsToDeviceStorage(LockedContext(context), unlocked = false)

        val ledger = SharedPrefsFiredLedger(context)
        assertNull(ledger.lastRang("a"))
        ledger.markRang("a", t2) // a ring while locked
        check(cePrefs("alarm_fired").edit().putLong("a", t1.toEpochMilli()).commit()) // conflicting CE leftover

        migrateAlarmPrefsToDeviceStorage(context, unlocked = true)

        assertEquals(t2, SharedPrefsFiredLedger(context).lastRang("a"))
        assertEquals(t1.toEpochMilli(), cePrefs("alarm_fired").getLong("a", 0L))
    }

    @Test
    fun migrationRunsOnlyOnce() {
        writeCe("alarm_snoozes", "ring-1", "${t1.toEpochMilli()}|a")
        migrateAlarmPrefsToDeviceStorage(context, unlocked = true)
        writeCe("alarm_snoozes", "ring-2", "${t2.toEpochMilli()}|b")

        migrateAlarmPrefsToDeviceStorage(context, unlocked = true)

        val ledger = SharedPrefsSnoozeLedger(context)
        assertEquals(listOf(SnoozeEntry("ring-1", "a", t1)), ledger.all())
        assertEquals("${t2.toEpochMilli()}|b", cePrefs("alarm_snoozes").getString("ring-2", null))
    }

    @Test
    fun ledgersOnlyTouchDeviceStorage() {
        val locked = LockedContext(context)
        SharedPrefsSnoozeLedger(locked).put("ring-1", "a", t1)
        SharedPrefsFiredLedger(locked).markRang("a", t2)
        assertEquals(t1, SharedPrefsSnoozeLedger(locked).get("ring-1")!!.until)
        assertEquals(t2, SharedPrefsFiredLedger(locked).lastRang("a"))
    }
}
