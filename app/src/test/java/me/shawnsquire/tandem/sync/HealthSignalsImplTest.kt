package me.shawnsquire.tandem.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HealthSignalsImplTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.ds(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { File(tmp.root, "test.preferences_pb") },
    )

    @Test
    fun lastFcmAtNullThenRecorded() = runTest {
        val h = HealthSignalsImpl(ds(), FcmTokenStore(ds()))
        assertNull(h.lastFcmAt.first())
        val at = Instant.ofEpochMilli(1_700_000_000_000)
        h.recordFcm(at)
        assertEquals(at, h.lastFcmAt.first())
    }

    @Test
    fun tokenPresence() = runTest {
        val store = ds()
        val tokens = FcmTokenStore(store)
        val h = HealthSignalsImpl(store, tokens)
        assertFalse(h.fcmTokenPresent.first())
        tokens.set("  ")
        assertFalse(h.fcmTokenPresent.first())
        tokens.set("tok")
        assertTrue(h.fcmTokenPresent.first())
        tokens.set(null)
        assertFalse(h.fcmTokenPresent.first())
    }
}
