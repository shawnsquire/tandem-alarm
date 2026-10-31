package me.shawnsquire.tandem.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreCorruptionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.corruptStore() = PreferenceDataStoreFactory.create(
        corruptionHandler = emptyOnCorruption,
        scope = backgroundScope,
        produceFile = {
            File(tmp.root, "corrupt.preferences_pb").also { it.writeBytes(ByteArray(64) { 0xFF.toByte() }) }
        },
    )

    @Test
    fun garbageFileReadsAsEmptyPreferences() = runTest {
        assertTrue(corruptStore().data.first().asMap().isEmpty())
    }

    @Test
    fun snoozeFallsBackToDefaultOnCorruptFile() = runTest {
        assertEquals(10, SettingsRepositoryImpl(corruptStore()).snoozeMinutes.first())
    }
}
