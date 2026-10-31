package me.shawnsquire.tandem.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Push-delivery diagnostics: when the last FCM message arrived and whether a token is stored. */
class HealthSignalsImpl(
    private val store: DataStore<Preferences>,
    tokens: FcmTokenStore,
) : HealthSignals {
    override val lastFcmAt: Flow<Instant?> = store.data.map { p -> p[KEY]?.let(Instant::ofEpochMilli) }
    override val fcmTokenPresent: Flow<Boolean> = tokens.token.map { !it.isNullOrBlank() }

    suspend fun recordFcm(at: Instant) {
        store.edit { it[KEY] = at.toEpochMilli() }
    }

    private companion object {
        val KEY = longPreferencesKey("lastFcmAt")
    }
}
