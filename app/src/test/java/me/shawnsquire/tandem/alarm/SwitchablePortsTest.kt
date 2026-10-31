package me.shawnsquire.tandem.alarm

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakePreFireCheck
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.FIRE_GRACE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Records every write; [gate] holds each one until it completes, [failSkip] makes setSkip throw. */
private class RecordingRepository(
    initial: List<Alarm>,
    private val gate: CompletableDeferred<Unit>? = null,
    private val failSkip: Boolean = false,
    private val delegate: FakeAlarmRepository = FakeAlarmRepository(initial),
) : AlarmRepository by delegate {
    val ops = mutableListOf<DeferredWrite>()

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        gate?.await()
        ops += DeferredWrite.SetEnabled(id, enabled)
        delegate.setEnabled(id, enabled)
    }

    override suspend fun setSkip(id: String, occurrence: Instant?) {
        gate?.await()
        if (failSkip) throw IllegalStateException("disk full")
        ops += DeferredWrite.SetSkip(id, occurrence)
        delegate.setSkip(id, occurrence)
    }
}

private class FailingAppendQueue : DeferredAlarmWrites by InMemoryDeferredAlarmWrites() {
    override fun append(w: DeferredWrite) = throw IllegalStateException("commit failed")
}

class SwitchablePortsTest {
    private val now = Instant.parse("2026-03-02T12:00:00Z")
    private val grace = FIRE_GRACE.toJavaDuration()

    private fun alarm(id: String, enabled: Boolean = true, skip: Instant? = null) =
        Alarm(id, id, 6, 30, emptySet(), enabled, skip, null, null)

    private fun mirrorOf(vararg alarms: Alarm, snooze: Int? = null, ringtone: String? = null) =
        InMemoryAlarmMirror(MirrorSnapshot(alarms.map { MirroredAlarm(it, null) }, snooze, ringtone))

    private val queue = InMemoryDeferredAlarmWrites()

    private suspend fun assertIllegalState(block: suspend () -> Unit) {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
    }

    private fun TestScope.repo(mirror: AlarmMirror, deferred: DeferredAlarmWrites = queue, scope: CoroutineScope = backgroundScope) =
        SwitchableAlarmRepository(mirror, deferred, scope) { now }

    // --- SwitchableAlarmRepository, locked ---

    @Test
    fun lockedReadsComeFromTheMirror() = runTest {
        val repo = repo(mirrorOf(alarm("a"), alarm("b", enabled = false)))
        assertEquals(alarm("a"), repo.get("a"))
        assertNull(repo.get("missing"))
        assertEquals(listOf(alarm("a"), alarm("b", enabled = false)), repo.alarms.first())
        assertFalse(repo.isAttached)
    }

    @Test
    fun lockedWritesLandInTheMirrorAndTheQueue() = runTest {
        val mirror = mirrorOf(alarm("a"), alarm("b"))
        val repo = repo(mirror)
        val skip = now.plusSeconds(3600)
        repo.alarms.test {
            assertEquals(listOf(alarm("a"), alarm("b")), awaitItem())
            repo.setEnabled("a", false)
            assertEquals(listOf(alarm("a", enabled = false), alarm("b")), awaitItem())
            repo.setSkip("b", skip)
            assertEquals(listOf(alarm("a", enabled = false), alarm("b", skip = skip)), awaitItem())
        }
        assertFalse(repo.get("a")!!.enabled)
        assertEquals(listOf(alarm("a", enabled = false), alarm("b", skip = skip)), mirror.read().alarms.map { it.alarm })
        assertEquals(listOf(DeferredWrite.SetEnabled("a", false), DeferredWrite.SetSkip("b", skip)), queue.all())
    }

    @Test
    fun failedQueueAppendLeavesTheMirrorUnchanged() = runTest {
        val mirror = mirrorOf(alarm("a"))
        val repo = repo(mirror, deferred = FailingAppendQueue())
        assertIllegalState { repo.setEnabled("a", false) }
        assertTrue(mirror.read().alarms.single().alarm.enabled)
        assertTrue(repo.get("a")!!.enabled)
    }

    @Test
    fun editsNeedTheUserToUnlock() = runTest {
        val repo = repo(mirrorOf(alarm("a")))
        assertIllegalState { repo.upsert(alarm("b")) }
        assertIllegalState { repo.delete("a") }
    }

    @Test
    fun expiredSkipsReadAsNullWhileLocked() = runTest {
        val expired = now.minus(grace)
        val live = now.minus(grace).plusMillis(1)
        val repo = repo(mirrorOf(alarm("old", skip = expired), alarm("new", skip = live)))
        assertNull(repo.get("old")!!.skippedOccurrenceAt)
        assertEquals(live, repo.get("new")!!.skippedOccurrenceAt)
        assertEquals(listOf(null, live), repo.alarms.first().map { it.skippedOccurrenceAt })
    }

    // --- SwitchableAlarmRepository, attach ---

    @Test
    fun attachDrainsTheQueueInOrderThenForwards() = runTest {
        val repo = repo(mirrorOf(alarm("a"), alarm("b")))
        val skip = now.plusSeconds(3600)
        repo.setEnabled("a", false)
        repo.setSkip("b", skip)
        repo.setEnabled("a", true)
        val real = RecordingRepository(listOf(alarm("a"), alarm("b"), alarm("c")))

        repo.alarms.test {
            assertEquals(listOf(alarm("a"), alarm("b", skip = skip)), awaitItem())
            repo.attach(real)
            runCurrent()
            assertEquals(listOf(alarm("a"), alarm("b", skip = skip), alarm("c")), awaitItem())
        }
        assertEquals(
            listOf(DeferredWrite.SetEnabled("a", false), DeferredWrite.SetSkip("b", skip), DeferredWrite.SetEnabled("a", true)),
            real.ops,
        )
        assertTrue(queue.all().isEmpty())
        assertTrue(repo.isAttached)
        assertEquals(alarm("c"), repo.get("c"))
        repo.setEnabled("c", false)
        assertFalse(real.get("c")!!.enabled)
        repo.delete("c")
        assertNull(real.get("c"))
    }

    @Test
    fun callsMadeDuringTheDrainWaitForIt() = runTest {
        val repo = repo(mirrorOf(alarm("a")))
        repo.setEnabled("a", false)
        val gate = CompletableDeferred<Unit>()
        val real = RecordingRepository(listOf(alarm("a")), gate = gate)

        repo.attach(real)
        runCurrent()
        val read = async { repo.get("a") }
        val list = async { repo.alarms.first() }
        runCurrent()
        assertFalse(read.isCompleted)
        assertFalse(list.isCompleted)
        assertFalse(repo.isAttached)

        gate.complete(Unit)
        runCurrent()
        assertEquals(alarm("a", enabled = false), read.await())
        assertEquals(listOf(alarm("a", enabled = false)), list.await())
        assertTrue(repo.isAttached)
    }

    @Test
    fun failingDrainOpKeepsTheRestQueuedAndForwardingStarts() = runTest {
        val repo = repo(mirrorOf(alarm("a"), alarm("b")))
        repo.setEnabled("a", false)
        repo.setSkip("b", now.plusSeconds(60))
        repo.setEnabled("b", false)
        val real = RecordingRepository(listOf(alarm("a"), alarm("b"), alarm("c")), failSkip = true)

        repo.attach(real)
        runCurrent()

        assertEquals(listOf(DeferredWrite.SetEnabled("a", false)), real.ops)
        assertEquals(listOf(DeferredWrite.SetSkip("b", now.plusSeconds(60)), DeferredWrite.SetEnabled("b", false)), queue.all())
        assertTrue(repo.isAttached)
        assertEquals(alarm("c"), repo.get("c"))
    }

    @Test
    fun secondAttachIsIgnored() = runTest {
        val repo = repo(mirrorOf(alarm("a")))
        val first = RecordingRepository(listOf(alarm("first")))
        val second = RecordingRepository(listOf(alarm("second")))
        repo.attach(first)
        repo.attach(second)
        runCurrent()
        assertEquals(alarm("first"), repo.get("first"))
        assertNull(repo.get("second"))
        assertEquals(listOf(alarm("first")), repo.alarms.first())
    }

    @Test
    fun deadScopeStillSwitchesToForwarding() = runTest {
        val repo = repo(mirrorOf(alarm("a")), scope = CoroutineScope(Job().apply { cancel() }))
        repo.attach(RecordingRepository(listOf(alarm("real"))))
        assertEquals(alarm("real"), repo.get("real"))
        assertTrue(repo.isAttached)
    }

    // --- SwitchableSettings ---

    @Test
    fun lockedSettingsComeFromTheMirrorWithDefaults() = runTest {
        val empty = SwitchableSettings(InMemoryAlarmMirror())
        assertEquals(10, empty.snoozeMinutes.first())
        assertNull(empty.defaultRingtoneUri.first())
        assertNull(empty.defaultWallpaperPath.first())

        val mirrored = SwitchableSettings(mirrorOf(snooze = 7, ringtone = "content://r"))
        assertEquals(7, mirrored.snoozeMinutes.first())
        assertEquals("content://r", mirrored.defaultRingtoneUri.first())
    }

    @Test
    fun lockedSnoozeIsCoerced() = runTest {
        assertEquals(1, SwitchableSettings(mirrorOf(snooze = 0)).snoozeMinutes.first())
        assertEquals(60, SwitchableSettings(mirrorOf(snooze = 99)).snoozeMinutes.first())
    }

    @Test
    fun lockedSettingsRejectEdits() = runTest {
        val settings = SwitchableSettings(InMemoryAlarmMirror())
        assertIllegalState { settings.setSnoozeMinutes(5) }
        assertIllegalState { settings.setDefaultRingtoneUri(null) }
        assertIllegalState { settings.setDefaultWallpaperPath(null) }
    }

    @Test
    fun attachedSettingsForwardIncludingFlows() = runTest {
        val settings = SwitchableSettings(mirrorOf(snooze = 7))
        val real = FakeSettingsRepository().apply {
            setSnoozeMinutes(15)
            setDefaultWallpaperPath("/w.jpg")
        }
        settings.snoozeMinutes.test {
            assertEquals(7, awaitItem())
            settings.attach(real)
            assertEquals(15, awaitItem())
            settings.setSnoozeMinutes(20)
            assertEquals(20, awaitItem())
        }
        assertEquals("/w.jpg", settings.defaultWallpaperPath.first())
        settings.attach(FakeSettingsRepository()) // ignored
        assertEquals(20, settings.snoozeMinutes.first())
    }

    // --- SwitchablePreFireCheck ---

    @Test
    fun lockedPreFireCheckSkipsThePull() = runTest {
        var built = 0
        val check = SwitchablePreFireCheck()
        assertFalse(check.pullAndApply(3.seconds))

        check.attach { built++; FakePreFireCheck(result = true) }
        assertEquals(0, built)
        assertTrue(check.pullAndApply(3.seconds))
        assertTrue(check.pullAndApply(500.milliseconds))
        assertEquals(2, built)
    }
}
