package me.shawnsquire.tandem.data

import android.content.Context
import me.shawnsquire.tandem.awaitTandemStartup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RingStateDataStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** TandemApp's startup clears a stale ring state, which could be one a test just wrote. */
    @Before
    fun awaitStartup() = awaitTandemStartup()

    @Test
    fun everyContextGetsTheSameProcessInstance() {
        val de = context.createDeviceProtectedStorageContext()
        assertSame(context.ringStateDataStore, context.ringStateDataStore)
        assertSame(context.ringStateDataStore, de.ringStateDataStore)
    }

    @Test
    fun fileLivesInDeviceProtectedStorage() {
        val deDataDir = context.createDeviceProtectedStorageContext().dataDir
        val file = ringStateFile(context)
        assertEquals(deDataDir.resolve("files/datastore/ring_state.preferences_pb"), file)
    }
}
