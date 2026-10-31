package me.shawnsquire.tandem.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncDataStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun garbageFileReadsAsEmptyPreferences() = runTest {
        val file = File(tmp.root, "sync.preferences_pb").also { it.writeBytes(ByteArray(64) { 0xFF.toByte() }) }
        assertTrue(createSyncDataStore(file, backgroundScope).data.first().asMap().isEmpty())
    }
}
