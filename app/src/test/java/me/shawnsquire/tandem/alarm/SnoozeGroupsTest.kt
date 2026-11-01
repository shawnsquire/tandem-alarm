package me.shawnsquire.tandem.alarm

import android.content.Context
import me.shawnsquire.tandem.awaitTandemStartup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SnoozeGroupsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun awaitStartup() = awaitTandemStartup()

    private fun exercise(groups: SnoozeGroups, fresh: () -> SnoozeGroups = { groups }) {
        assertTrue(groups.all().isEmpty())
        groups.put("ring-1", listOf("ring-1", "split-1", "split-2"))
        groups.put("ring-9", listOf("ring-9", "split-3"))
        assertEquals(listOf("ring-1", "split-1", "split-2"), fresh().all()["ring-1"]) // primary first
        groups.put("ring-1", listOf("split-2")) // a member left
        assertEquals(mapOf("ring-1" to listOf("split-2"), "ring-9" to listOf("ring-9", "split-3")), fresh().all())
        groups.remove("ring-9")
        assertEquals(setOf("ring-1"), fresh().all().keys)
        groups.remove("missing") // no-op
    }

    @Test fun sharedPrefsGroupsRoundTripAndSurviveANewInstance() =
        exercise(SharedPrefsSnoozeGroups(context)) { SharedPrefsSnoozeGroups(context) }

    @Test fun inMemoryGroupsBehaveTheSame() = exercise(InMemorySnoozeGroups())
}
