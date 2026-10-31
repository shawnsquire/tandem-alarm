package me.shawnsquire.tandem.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** This device's FCM registration token. The messaging service writes it; the state publisher reads it into the device doc. */
class FcmTokenStore(private val store: DataStore<Preferences>) {
    val token: Flow<String?> = store.data.map { it[KEY] }

    suspend fun set(token: String?) {
        store.edit { if (token == null) it.remove(KEY) else it[KEY] = token }
    }

    private companion object {
        val KEY = stringPreferencesKey("fcmToken")
    }
}
