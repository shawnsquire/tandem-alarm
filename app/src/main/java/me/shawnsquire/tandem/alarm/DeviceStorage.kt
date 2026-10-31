package me.shawnsquire.tandem.alarm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Device-protected prefs: readable before the first unlock after a reboot, unlike the
 * credential-encrypted default, so alarms can ring and be stopped while the phone is locked.
 */
internal fun deviceProtectedPrefs(context: Context, name: String): SharedPreferences =
    context.createDeviceProtectedStorageContext().getSharedPreferences(name, Context.MODE_PRIVATE)

/** Prefs the alarm path reads before unlock; older builds kept them in CE storage. */
private val MOVED_PREFS = listOf("alarm_snoozes", "alarm_fired", "alarm_scheduler")
private const val STORAGE_PREFS = "alarm_storage"
private const val KEY_MIGRATED = "prefs_migrated"

/**
 * One-time move of [MOVED_PREFS] from CE to DE storage; must run before anything reads them.
 * While locked the CE copies can't be read, so they are abandoned: the marker is set anyway, and a
 * later unlocked move can never overwrite DE data written in the meantime. The marker is also set
 * when a move fails, for the same reason. Never throws.
 */
fun migrateAlarmPrefsToDeviceStorage(context: Context, unlocked: Boolean) {
    try {
        val de = context.createDeviceProtectedStorageContext()
        val storage = de.getSharedPreferences(STORAGE_PREFS, Context.MODE_PRIVATE)
        if (storage.getBoolean(KEY_MIGRATED, false)) return
        if (unlocked) {
            for (name in MOVED_PREFS) {
                try {
                    if (!de.moveSharedPreferencesFrom(context, name)) Log.w(TAG, "moving $name to device storage failed")
                } catch (e: Exception) {
                    Log.w(TAG, "moving $name to device storage failed", e)
                }
            }
        } else {
            Log.w(TAG, "first run while locked; abandoning credential-protected alarm prefs")
        }
        if (!storage.edit().putBoolean(KEY_MIGRATED, true).commit()) Log.w(TAG, "writing migration marker failed")
    } catch (e: Exception) {
        Log.e(TAG, "alarm prefs migration failed", e)
    }
}

private const val TAG = "DeviceStorage"
