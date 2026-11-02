package me.shawnsquire.tandem.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [AlarmEntity::class], version = 5, exportSchema = true)
abstract class TandemDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao

    companion object {
        /** Room opens the file lazily, so this does no I/O. */
        fun build(context: Context): TandemDatabase =
            Room.databaseBuilder(context.applicationContext, TandemDatabase::class.java, "tandem.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()

        private val LINK_COLUMNS = listOf(
            "linkOwnerUid" to "TEXT",
            "linkOwnerAlarmId" to "TEXT",
            "linkMode" to "TEXT",
            "linkOwnerSkippedOccurrenceAt" to "INTEGER",
            "linkJoinerEnabled" to "INTEGER",
            "linkPendingBothType" to "TEXT",
            "linkPendingBothOccurrenceAt" to "INTEGER",
            "linkPendingBothCommandId" to "TEXT",
            "linkOverrideLabel" to "TEXT",
            "linkOverrideRingtoneUri" to "TEXT",
            "linkOverrideWallpaperPath" to "TEXT",
            "soloSkippedOccurrenceAt" to "INTEGER",
        )

        /** Adds the linked-alarm columns; every one is nullable with no default, matching Room's schema. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                LINK_COLUMNS.forEach { (name, type) -> db.execSQL("ALTER TABLE alarms ADD COLUMN `$name` $type") }
            }
        }

        /**
         * Adds the pending "both" command's creation time. A command already pending is stamped with
         * the migration time, so its 24 h window runs from the upgrade rather than from every restart.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN `linkPendingBothCreatedAt` INTEGER")
                db.execSQL(
                    "UPDATE alarms SET linkPendingBothCreatedAt = ? WHERE linkPendingBothCommandId IS NOT NULL",
                    arrayOf<Any>(System.currentTimeMillis()),
                )
            }
        }

        /**
         * Adds the owner's late-ring answer. Kept out of [LINK_COLUMNS] so a v1 or v2 install does not
         * add the columns in 1 -> 2 and then fail on a duplicate here.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN `lateRingOccurrenceAt` INTEGER")
                db.execSQL("ALTER TABLE alarms ADD COLUMN `lateRingRing` INTEGER")
            }
        }

        /**
         * Adds the ring's vibration pattern and sound/vibration switches. The alarm columns are NOT NULL
         * with a default so existing rows keep today's behavior (sound plus the pulse); the defaults must
         * match the entity's `defaultValue`. The link overrides are nullable, like the other link columns.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN `vibration` TEXT NOT NULL DEFAULT 'PULSE'")
                db.execSQL("ALTER TABLE alarms ADD COLUMN `vibrationOn` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE alarms ADD COLUMN `soundOn` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE alarms ADD COLUMN `linkOverrideVibration` TEXT")
                db.execSQL("ALTER TABLE alarms ADD COLUMN `linkOverrideVibrationOn` INTEGER")
                db.execSQL("ALTER TABLE alarms ADD COLUMN `linkOverrideSoundOn` INTEGER")
            }
        }
    }
}
