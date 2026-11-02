package me.shawnsquire.tandem.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.coroutines.runBlocking
import me.shawnsquire.tandem.schedule.LateRing
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.VibrationPattern
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
            .addMigrations(TandemDatabase.MIGRATION_1_2, TandemDatabase.MIGRATION_2_3, TandemDatabase.MIGRATION_3_4, TandemDatabase.MIGRATION_4_5, TandemDatabase.MIGRATION_5_6)
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

    @Test
    fun migrate2To3StampsPendingCommandsAndLeavesOthersNull() {
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO alarms (id, label, hour, minute, days, enabled, skippedOccurrenceAt, ringtoneUri, wallpaperPath, " +
                    "linkOwnerUid, linkOwnerAlarmId, linkMode, linkJoinerEnabled, " +
                    "linkPendingBothType, linkPendingBothOccurrenceAt, linkPendingBothCommandId) " +
                    "VALUES ('pend', 'Wake', 6, 30, 31, 1, NULL, NULL, NULL, 'them', 'o1', 'RING', 1, 'DISABLE', 1700000000000, 'c1')",
            )
            execSQL(
                "INSERT INTO alarms (id, label, hour, minute, days, enabled, skippedOccurrenceAt, ringtoneUri, wallpaperPath, " +
                    "linkOwnerUid, linkOwnerAlarmId, linkMode, linkJoinerEnabled) " +
                    "VALUES ('plain', 'Wake', 6, 30, 31, 1, NULL, NULL, NULL, 'them', 'o2', 'RING', 1)",
            )
            execSQL(
                "INSERT INTO alarms (id, label, hour, minute, days, enabled, skippedOccurrenceAt, ringtoneUri, wallpaperPath) " +
                    "VALUES ('solo', 'Dentist', 14, 5, 0, 0, NULL, NULL, NULL)",
            )
            close()
        }

        val before = System.currentTimeMillis()
        val migrated = helper.runMigrationsAndValidate(dbName, 3, true, TandemDatabase.MIGRATION_2_3)
        val after = System.currentTimeMillis()
        migrated.query("SELECT id, linkPendingBothCreatedAt FROM alarms").use { c ->
            val stamps = buildMap { while (c.moveToNext()) put(c.getString(0), if (c.isNull(1)) null else c.getLong(1)) }
            assertEquals(3, stamps.size)
            assertTrue(stamps.getValue("pend")!! in before..after)
            assertNull(stamps["plain"])
            assertNull(stamps["solo"])
        }
        migrated.close()

        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), TandemDatabase::class.java, dbName)
            .addMigrations(TandemDatabase.MIGRATION_1_2, TandemDatabase.MIGRATION_2_3, TandemDatabase.MIGRATION_3_4, TandemDatabase.MIGRATION_4_5, TandemDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val pending = db.alarmDao().get("pend")!!.toAlarm().link!!.pendingBoth!!
                assertEquals("c1", pending.commandId)
                assertTrue(pending.createdAt!!.toEpochMilli() in before..after)
                assertNull(db.alarmDao().get("plain")!!.toAlarm().link!!.pendingBoth)
            }
        } finally {
            db.close()
        }
    }

    private fun insertV3Rows(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO alarms (id, label, hour, minute, days, enabled, skippedOccurrenceAt, ringtoneUri, wallpaperPath, " +
                "linkOwnerUid, linkOwnerAlarmId, linkMode, linkJoinerEnabled) " +
                "VALUES ('linked', 'Wake', 6, 30, 31, 1, NULL, NULL, NULL, 'them', 'o1', 'RING', 1)",
        )
        db.execSQL(
            "INSERT INTO alarms (id, label, hour, minute, days, enabled, skippedOccurrenceAt, ringtoneUri, wallpaperPath) " +
                "VALUES ('own', 'Dentist', 14, 5, 0, 1, NULL, NULL, NULL)",
        )
    }

    private fun assertLateRingColumnsNullAndWritable(migrated: androidx.sqlite.db.SupportSQLiteDatabase) {
        migrated.query("SELECT lateRingOccurrenceAt, lateRingRing FROM alarms").use { c ->
            assertEquals(2, c.count)
            while (c.moveToNext()) {
                assertTrue(c.isNull(0))
                assertTrue(c.isNull(1))
            }
        }
        migrated.close()

        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), TandemDatabase::class.java, dbName)
            .addMigrations(TandemDatabase.MIGRATION_1_2, TandemDatabase.MIGRATION_2_3, TandemDatabase.MIGRATION_3_4, TandemDatabase.MIGRATION_4_5, TandemDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertNull(db.alarmDao().get("linked")!!.toAlarm().lateRing)
                assertNull(db.alarmDao().get("own")!!.toAlarm().lateRing)
                db.alarmDao().setLateRing("own", 1_700_000_000_000L, false)
                assertEquals(LateRing(Instant.ofEpochMilli(1_700_000_000_000L), false), db.alarmDao().get("own")!!.toAlarm().lateRing)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate3To4AddsLateRingColumnsAsNull() {
        helper.createDatabase(dbName, 3).apply {
            insertV3Rows(this)
            close()
        }
        assertLateRingColumnsNullAndWritable(
            helper.runMigrationsAndValidate(dbName, 4, true, TandemDatabase.MIGRATION_3_4),
        )
    }

    @Test
    fun migrate1To4RunsEveryMigrationOnce() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO alarms (id, label, hour, minute, days, enabled, skippedOccurrenceAt, ringtoneUri, wallpaperPath) " +
                    "VALUES ('own', 'Dentist', 14, 5, 0, 1, NULL, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO alarms (id, label, hour, minute, days, enabled, skippedOccurrenceAt, ringtoneUri, wallpaperPath) " +
                    "VALUES ('linked', 'Wake', 6, 30, 31, 1, NULL, NULL, NULL)",
            )
            close()
        }
        assertLateRingColumnsNullAndWritable(
            helper.runMigrationsAndValidate(
                dbName, 4, true,
                TandemDatabase.MIGRATION_1_2, TandemDatabase.MIGRATION_2_3, TandemDatabase.MIGRATION_3_4,
            ),
        )
    }

    @Test
    fun migrate4To5DefaultsVibrationAndSoundAndLeavesOverridesNull() {
        helper.createDatabase(dbName, 4).apply {
            insertV3Rows(this)
            close()
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 5, true, TandemDatabase.MIGRATION_4_5)
        migrated.close()

        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), TandemDatabase::class.java, dbName)
            .addMigrations(
                TandemDatabase.MIGRATION_1_2, TandemDatabase.MIGRATION_2_3, TandemDatabase.MIGRATION_3_4, TandemDatabase.MIGRATION_4_5,
                TandemDatabase.MIGRATION_5_6,
            )
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                for (id in listOf("own", "linked")) {
                    val alarm = db.alarmDao().get(id)!!.toAlarm()
                    assertEquals(id, VibrationPattern.PULSE, alarm.vibration)
                    assertTrue(id, alarm.vibrationOn)
                    assertTrue(id, alarm.soundOn)
                }
                val link = db.alarmDao().get("linked")!!.toAlarm().link!!
                assertNull(link.overrideVibration)
                assertNull(link.overrideVibrationOn)
                assertNull(link.overrideSoundOn)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate5To6LeavesFramesNullAndCentresTheText() {
        helper.createDatabase(dbName, 5).apply {
            insertV3Rows(this)
            close()
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 6, true, TandemDatabase.MIGRATION_5_6)
        migrated.close()

        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), TandemDatabase::class.java, dbName)
            .addMigrations(
                TandemDatabase.MIGRATION_1_2, TandemDatabase.MIGRATION_2_3, TandemDatabase.MIGRATION_3_4, TandemDatabase.MIGRATION_4_5,
                TandemDatabase.MIGRATION_5_6,
            )
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                for (id in listOf("own", "linked")) {
                    val alarm = db.alarmDao().get(id)!!.toAlarm()
                    assertNull(id, alarm.wallpaperFrame)
                    assertEquals(id, TextPosition.MIDDLE, alarm.textPosition)
                }
                val link = db.alarmDao().get("linked")!!.toAlarm().link!!
                assertNull(link.overrideWallpaperFrame)
                assertNull(link.overrideTextPosition)
            }
        } finally {
            db.close()
        }
    }
}
