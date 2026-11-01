package me.shawnsquire.tandem.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Push and media diagnostics: last FCM message, token presence, last Storage outcome, linked-media cache state. */
class HealthSignalsImpl(
    private val store: DataStore<Preferences>,
    tokens: FcmTokenStore,
) : HealthSignals {
    override val lastFcmAt: Flow<Instant?> = store.data.map { p -> p[KEY]?.let(Instant::ofEpochMilli) }
    override val fcmTokenPresent: Flow<Boolean> = tokens.token.map { !it.isNullOrBlank() }

    override val storageStatus: Flow<StorageStatus?> = store.data.map { p ->
        val ok = p[STORAGE_OK]
        val at = p[STORAGE_AT]
        if (ok == null || at == null) null else StorageStatus(ok, Instant.ofEpochMilli(at))
    }
    override val linkedMedia: Flow<LinkedMediaStatus?> = store.data.map { p ->
        val referenced = p[LINKED_REFERENCED]
        val missing = p[LINKED_MISSING]
        if (referenced == null || missing == null) null else LinkedMediaStatus(referenced, missing)
    }

    suspend fun recordFcm(at: Instant) {
        store.edit { it[KEY] = at.toEpochMilli() }
    }

    suspend fun recordStorage(ok: Boolean, at: Instant) {
        store.edit {
            it[STORAGE_OK] = ok
            it[STORAGE_AT] = at.toEpochMilli()
        }
    }

    suspend fun recordLinkedMedia(referenced: Int, missing: Int) {
        store.edit {
            it[LINKED_REFERENCED] = referenced
            it[LINKED_MISSING] = missing
        }
    }

    private companion object {
        val KEY = longPreferencesKey("lastFcmAt")
        val STORAGE_OK = booleanPreferencesKey("lastStorageOk")
        val STORAGE_AT = longPreferencesKey("lastStorageAt")
        val LINKED_REFERENCED = intPreferencesKey("linkedMediaReferenced")
        val LINKED_MISSING = intPreferencesKey("linkedMediaMissing")
    }
}
