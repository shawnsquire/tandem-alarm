package me.shawnsquire.tandem.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import me.shawnsquire.tandem.data.emptyOnCorruption

/** Sync-owned storage: display name, FCM token, last FCM time, processed command ids. */
val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(
    "sync",
    corruptionHandler = emptyOnCorruption,
)
