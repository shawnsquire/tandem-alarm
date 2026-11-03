package me.shawnsquire.tandem.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.names() = DisplayNameStore(
        PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { File(tmp.root, "t.preferences_pb") }),
        "Pixel 9",
    )

    @Test
    fun defaultsWhenAbsent() = runTest { assertEquals("Pixel 9", names().displayName.first()) }

    @Test
    fun overrideAndClear() = runTest {
        val n = names()
        n.set("Shawn")
        assertEquals("Shawn", n.displayName.first())
        n.set("  ")
        assertEquals("Pixel 9", n.displayName.first())
        n.set("A")
        n.set(null)
        assertEquals("Pixel 9", n.displayName.first())
    }

    @Test
    fun storedNameIsNullByDefault() = runTest { assertNull(names().storedName.first()) }

    @Test
    fun storedNameIsTrimmedValueAndNullAfterClear() = runTest {
        val n = names()
        n.set("  Shawn ")
        assertEquals("Shawn", n.storedName.first())
        n.set(null)
        assertNull(n.storedName.first())
        n.set("A")
        n.set("  ")
        assertNull(n.storedName.first())
    }
}
