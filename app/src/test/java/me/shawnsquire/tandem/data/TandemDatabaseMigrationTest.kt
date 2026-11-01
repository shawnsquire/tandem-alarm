package me.shawnsquire.tandem.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private val NEW_COLUMNS = listOf(
    "linkOwnerUid", "linkOwnerAlarmId", "linkMode", "linkOwnerSkippedOccurrenceAt", "linkJoinerEnabled",
    "linkPendingBothType", "linkPendingBothOccurrenceAt", "linkPendingBothCommandId", "linkOverrideLabel",
    "linkOverrideRingtoneUri", "linkOverrideWallpaperPath", "soloSkippedOccurrenceAt",
)

@RunWith(RobolectricTestRunner::class)
class TandemDatabaseMigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TandemDatabase::class.java)

    @Test
    fun migrate1To2KeepsRowsAndLeavesNewColumnsNull() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO alarms (id, label, hour, minute, days, enabled, skippedOccurrenceAt, ringtoneUri, wallpaperPath) " +
                    "VALUES ('rep', 'Weekdays', 6, 45, 31, 1, 1700000000000, 'content://ring/1', '/wall/1.jpg')",
            )
            execSQL(
                "INSERT INTO alarms (id, label, hour, minute, days, enabled, skippedOccurrenceAt, ringtoneUri, wallpaperPath) " +
                    "VALUES ('once', 'Dentist', 14, 5, 0, 0, NULL, NULL, NULL)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 2, true, TandemDatabase.MIGRATION_1_2)
        migrated.query("SELECT * FROM alarms ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            fun str(col: String) = c.getString(c.getColumnIndexOrThrow(col))
            fun int(col: String) = c.getInt(c.getColumnIndexOrThrow(col))
            assertEquals("once", str("id"))
            assertEquals("Dentist", str("label"))
            assertEquals(14, int("hour"))
            assertEquals(5, int("minute"))
            assertEquals(0, int("days"))
            assertEquals(0, int("enabled"))
            assertTrue(c.isNull(c.getColumnIndexOrThrow("skippedOccurrenceAt")))
            assertTrue(c.isNull(c.getColumnIndexOrThrow("ringtoneUri")))
            assertTrue(c.isNull(c.getColumnIndexOrThrow("wallpaperPath")))
            c.moveToNext()
            assertEquals("rep", str("id"))
            assertEquals("Weekdays", str("label"))
            assertEquals(6, int("hour"))
            assertEquals(45, int("minute"))
            assertEquals(31, int("days"))
            assertEquals(1, int("enabled"))
            assertEquals(1_700_000_000_000L, c.getLong(c.getColumnIndexOrThrow("skippedOccurrenceAt")))
            assertEquals("content://ring/1", str("ringtoneUri"))
            assertEquals("/wall/1.jpg", str("wallpaperPath"))
            c.moveToFirst()
            do {
                NEW_COLUMNS.forEach { assertTrue("$it should be null", c.isNull(c.getColumnIndexOrThrow(it))) }
            } while (c.moveToNext())
        }
        migrated.close()

        // The migrated file also opens through Room and the entity conversion.
        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), TandemDatabase::class.java, dbName)
            .addMigrations(TandemDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val rep = db.alarmDao().get("rep")!!.toAlarm()
                assertNull(rep.link)
                assertNull(rep.soloSkippedOccurrenceAt)
                assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), rep.skippedOccurrenceAt)
                val once = db.alarmDao().get("once")!!.toAlarm()
                assertNull(once.link)
                assertNull(once.soloSkippedOccurrenceAt)
            }
        } finally {
            db.close()
        }
    }
}
