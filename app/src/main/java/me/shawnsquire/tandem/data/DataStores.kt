package me.shawnsquire.tandem.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore

/** A corrupt preferences file is replaced with empty preferences, so reads fall back to defaults. */
internal val emptyOnCorruption = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

val Context.ringStateDataStore: DataStore<Preferences> by preferencesDataStore(
    "ring_state",
    corruptionHandler = emptyOnCorruption,
)

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    "settings",
    corruptionHandler = emptyOnCorruption,
)
