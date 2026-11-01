package me.shawnsquire.tandem.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.shawnsquire.tandem.data.emptyOnCorruption
import java.io.File

/** Sync-owned storage: display name, FCM token, last FCM time, processed command ids. One instance per process. */
val Context.syncDataStore: DataStore<Preferences>
    get() = SyncDataStore.get(this)

/** A corrupt file reads as empty preferences, like the other DataStores. Split out so a test can build one on its own file. */
internal fun createSyncDataStore(
    file: File,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = emptyOnCorruption,
    scope = scope,
    produceFile = { file },
)

private object SyncDataStore {
    @Volatile private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> = instance ?: synchronized(this) {
        instance ?: createSyncDataStore(context.applicationContext.preferencesDataStoreFile("sync"))
            .also { instance = it }
    }
}
