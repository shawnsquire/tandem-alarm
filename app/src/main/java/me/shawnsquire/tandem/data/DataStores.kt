package me.shawnsquire.tandem.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.File

/** A corrupt preferences file is replaced with empty preferences, so reads fall back to defaults. */
internal val emptyOnCorruption = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

/**
 * Ring state lives in device-protected storage so a ring can start and be stopped before the
 * first unlock. One instance per process: two DataStores on one file throw.
 */
val Context.ringStateDataStore: DataStore<Preferences>
    get() = RingStateDataStore.get(this)

/**
 * Built by hand: `preferencesDataStoreFile` resolves against `applicationContext`, which is the
 * credential-protected context even when called on the device-protected one.
 */
internal fun ringStateFile(context: Context): File =
    File(context.createDeviceProtectedStorageContext().filesDir, "datastore/ring_state.preferences_pb")

private object RingStateDataStore {
    @Volatile private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> = instance ?: synchronized(this) {
        instance ?: context.applicationContext.let { app ->
            PreferenceDataStoreFactory.create(
                corruptionHandler = emptyOnCorruption,
                produceFile = { ringStateFile(app) },
            )
        }.also { instance = it }
    }
}

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    "settings",
    corruptionHandler = emptyOnCorruption,
)
