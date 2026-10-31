package me.shawnsquire.tandem.sync

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Plain [Application], so TandemApp startup does not open (and cache) the store before the file is
 * corrupted. The store is cached per process, so this is only meaningful when it runs first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncDataStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun garbageFileReadsAsEmptyPreferences() = runBlocking {
        val file = File(context.applicationContext.filesDir, "datastore/sync.preferences_pb")
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(64) { 0xFF.toByte() })
        assertTrue(context.syncDataStore.data.first().asMap().isEmpty())
    }
}
