package me.shawnsquire.tandem.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.ringStateDataStore: DataStore<Preferences> by preferencesDataStore("ring_state")

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")
