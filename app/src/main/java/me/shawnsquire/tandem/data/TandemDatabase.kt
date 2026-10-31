package me.shawnsquire.tandem.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AlarmEntity::class], version = 1, exportSchema = false)
abstract class TandemDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao

    companion object {
        /** Room opens the file lazily, so this does no I/O. */
        fun build(context: Context): TandemDatabase =
            Room.databaseBuilder(context.applicationContext, TandemDatabase::class.java, "tandem.db").build()
    }
}
