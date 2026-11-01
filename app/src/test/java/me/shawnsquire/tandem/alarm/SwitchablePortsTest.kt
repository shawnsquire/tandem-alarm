package me.shawnsquire.tandem.alarm

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakePreFireCheck
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LinkMode
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

/**
 * Records every write; [gate] holds each one until it completes, setSkip throws its first
 * [skipFailures] calls, and setEnabled call number [holdFrom] onwards never returns.
 */
private class RecordingRepository(
    initial: List<Alarm>,
    private val gate: CompletableDeferred<Unit>? = null,
    private var skipFailures: Int = 0,
    private val holdFrom: Int = Int.MAX_VALUE,
    private val delegate: FakeAlarmRepository = FakeAlarmRepository(initial),
) : AlarmRepository by delegate {
    val ops = mutableListOf<DeferredWrite>()
    var skipCalls = 0
    private var enabledCalls = 0

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        gate?.await()
        if (++enabledCalls >= holdFrom) awaitCancellation()
        ops += DeferredWrite.SetEnabled(id, enabled)
        delegate.setEnabled(id, enabled)
    }

    override suspend fun setSkip(id: String, occurrence: Instant?) {
        gate?.await()
        skipCalls++
        if (skipFailures > 0) {
            skipFailures--
            throw IllegalStateException("disk full")
        }
        ops += DeferredWrite.SetSkip(id, occurrence)
        delegate.setSkip(id, occurrence)
    }
}

private class FailingAppendQueue : DeferredAlarmWrites by InMemoryDeferredAlarmWrites() {
    override fun append(w: DeferredWrite) = throw IllegalStateException("commit failed")
}

/** Its first [failures] trims throw without removing anything. */
private class FailingTrimQueue(
    private var failures: Int,
    private val delegate: DeferredAlarmWrites = InMemoryDeferredAlarmWrites(),
) : DeferredAlarmWrites by delegate {
    var trims = 0

    override fun removeThrough(seq: Long) {
        trims++
        if (failures-- > 0) throw IllegalStateException("commit failed")
        delegate.removeThrough(seq)
    }
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
        assertEquals(listOf(alarm("a"), alarm("b", enabled = false)), repo.alarms.first())
        assertFalse(repo.isAttached)
    }

    // I1: locked, delete throws, so an id missing from the mirror is a read problem, never a local
    // delete. A throw makes the session ring with defaults and a reschedule cancel nothing.

    @Test
    fun lockedGetThrowsForAnIdMissingFromTheMirror() = runTest {
        val repo = repo(mirrorOf(alarm("a")))
        assertIllegalState { repo.get("missing") }
    }

    @Test
    fun lockedReadsThrowWhenTheMirrorIsUnreadable() = runTest {
        val repo = repo(InMemoryAlarmMirror(initial = null))
        assertIllegalState { repo.get("a") }
        assertIllegalState { repo.alarms.first() }
    }

    @Test
    fun lockedWriteWithAnUnreadableMirrorChangesNothing() = runTest {
        val mirror = InMemoryAlarmMirror(initial = null)
        val repo = repo(mirror)
        assertIllegalState { repo.setEnabled("a", false) }
        assertIllegalState { repo.setSkip("a", now) }
        assertNull(mirror.readOrNull())
        assertTrue(queue.all().isEmpty())
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
    fun lockedSoloSkipIsQueuedAndReplayedOnAttach() = runTest {
        val repo = repo(mirrorOf(alarm("a")))
        val at = now.plusSeconds(3600)
        repo.setSoloSkip("a", at)
        assertEquals(listOf(DeferredWrite.SetSoloSkip("a", at)), queue.all())
        val real = FakeAlarmRepository(listOf(alarm("a")))
        repo.attach(real)
        runCurrent()
        assertEquals(at, real.get("a")!!.soloSkippedOccurrenceAt)
        assertTrue(queue.all().isEmpty())
    }

    @Test
    fun lockedJoinerEnabledIsQueuedAndReplayedOnAttach() = runTest {
        val repo = repo(mirrorOf(alarm("a")))
        repo.setJoinerEnabled("a", false)
        assertEquals(listOf(DeferredWrite.SetJoinerEnabled("a", false)), queue.all())
        val link = AlarmLink("o", "oa", LinkMode.RING, null, true, null, null, null, null)
        val real = FakeAlarmRepository(listOf(alarm("a").copy(link = link)))
        repo.attach(real)
        runCurrent()
        assertFalse(real.get("a")!!.link!!.joinerEnabled)
        assertTrue(queue.all().isEmpty())
    }

    @Test
    fun lockedUpsertLinkedAndUpdateLinkThrow() = runTest {
        val repo = repo(mirrorOf(alarm("a")))
        val link = AlarmLink("o", "oa", LinkMode.RING, null, true, null, null, null, null)
        assertIllegalState { repo.upsertLinked(alarm("b").copy(link = link)) }
        assertIllegalState { repo.updateLink("a") { it } }
    }

    @Test
    fun lockedOwnAndLinkedViewsFilterTheMirrorByLink() = runTest {
        val link = AlarmLink("o", "oa", LinkMode.RING, null, true, null, null, null, null)
        val linked = alarm("l").copy(link = link)
        val repo = repo(mirrorOf(alarm("a"), linked))
        assertEquals(listOf("a"), repo.ownAlarms.first().map { it.id })
        assertEquals(listOf("l"), repo.linkedAlarms.first().map { it.id })
        assertEquals(listOf("a", "l"), repo.alarms.first().map { it.id })
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
    fun drainOpFailingTwiceIsDroppedAndTheRestStillApply() = runTest {
        val repo = repo(mirrorOf(alarm("a"), alarm("b")))
        repo.setEnabled("a", false)
        repo.setSkip("b", now.plusSeconds(60))
        repo.setEnabled("b", false)
        val real = RecordingRepository(listOf(alarm("a"), alarm("b"), alarm("c")), skipFailures = Int.MAX_VALUE)

        repo.attach(real)
        runCurrent()

        // Retried once, then dropped: kept, it would replay over newer writes at the next attach.
        assertEquals(2, real.skipCalls)
        assertEquals(listOf(DeferredWrite.SetEnabled("a", false), DeferredWrite.SetEnabled("b", false)), real.ops)
        assertTrue(queue.all().isEmpty())
        assertTrue(repo.isAttached)
        assertEquals(alarm("c"), repo.get("c"))
    }

    @Test
    fun drainOpFailingOnceIsRetriedInOrder() = runTest {
        val repo = repo(mirrorOf(alarm("a"), alarm("b")))
        val skip = now.plusSeconds(60)
        repo.setEnabled("a", false)
        repo.setSkip("b", skip)
        repo.setEnabled("b", false)
        val real = RecordingRepository(listOf(alarm("a"), alarm("b")), skipFailures = 1)

        repo.attach(real)
        runCurrent()

        assertEquals(2, real.skipCalls)
        assertEquals(
            listOf(DeferredWrite.SetEnabled("a", false), DeferredWrite.SetSkip("b", skip), DeferredWrite.SetEnabled("b", false)),
            real.ops,
        )
        assertTrue(queue.all().isEmpty())
    }

    @Test
    fun drainCutShortNeverReappliesOpsItAlreadyApplied() = runTest {
        val dying = CoroutineScope(backgroundScope.coroutineContext + Job())
        val repo = repo(mirrorOf(alarm("a"), alarm("b")), scope = dying)
        repo.setEnabled("a", false)
        repo.setEnabled("b", false)
        repo.setEnabled("a", true)
        // The process dies while the drain applies the third op: its drain never resumes, and
        // no finally runs (a cancellation would run one).
        val first = RecordingRepository(listOf(alarm("a"), alarm("b")), holdFrom = 3)
        repo.attach(first)
        runCurrent()
        assertEquals(listOf(DeferredWrite.SetEnabled("a", false), DeferredWrite.SetEnabled("b", false)), first.ops)

        // The next process: Room has moved on since; ops 1 and 2 must not be replayed over it.
        val next = RecordingRepository(listOf(alarm("a"), alarm("b")))
        repo(mirrorOf(alarm("a"), alarm("b"))).attach(next)
        runCurrent()

        assertEquals(listOf(DeferredWrite.SetEnabled("a", true)), next.ops)
        assertTrue(queue.all().isEmpty())
        dying.cancel()
    }

    @Test
    fun failedTrimIsCoveredByTheNextOne() = runTest {
        val failOnce = FailingTrimQueue(failures = 1)
        val repo = repo(mirrorOf(alarm("a"), alarm("b")), deferred = failOnce)
        repo.setEnabled("a", false)
        repo.setEnabled("b", false)
        val real = RecordingRepository(listOf(alarm("a"), alarm("b")))

        repo.attach(real)
        runCurrent()

        assertEquals(listOf(DeferredWrite.SetEnabled("a", false), DeferredWrite.SetEnabled("b", false)), real.ops)
        assertEquals(2, failOnce.trims)
        assertTrue(failOnce.all().isEmpty())
        assertTrue(repo.isAttached)
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

    @Test
    fun attachedSnoozeAndRingtoneEditsReportAChange() = runTest {
        var changes = 0
        val settings = SwitchableSettings(mirrorOf(snooze = 7)) { changes++ }
        assertIllegalState { settings.setSnoozeMinutes(5) }
        assertEquals(0, changes)

        val real = FakeSettingsRepository()
        settings.attach(real)
        settings.setSnoozeMinutes(5)
        assertEquals(5, real.snoozeMinutes.first())
        assertEquals(1, changes)
        settings.setDefaultRingtoneUri("content://r")
        assertEquals("content://r", real.defaultRingtoneUri.first())
        assertEquals(2, changes)
        // Wallpapers aren't mirrored.
        settings.setDefaultWallpaperPath("/w.jpg")
        assertEquals(2, changes)
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

    // --- SwitchableRingMirrorPort ---

    @Test
    fun ringMirrorPortDefaultsToNoOpAndForwardsToTheDelegate() {
        val port = SwitchableRingMirrorPort()
        val members = listOf(me.shawnsquire.tandem.schedule.RingMember("a", Instant.parse("2026-03-02T06:30:00Z")))
        port.onLocal(MirrorKind.DISMISS, members, null) // queued, must not throw
        assertEquals(NoOpRingMirrorPort, port.delegate)

        val calls = mutableListOf<Triple<MirrorKind, List<me.shawnsquire.tandem.schedule.RingMember>, Instant?>>()
        port.delegate = object : RingMirrorPort {
            override fun onLocal(kind: MirrorKind, members: List<me.shawnsquire.tandem.schedule.RingMember>, snoozedUntil: Instant?) {
                calls += Triple(kind, members, snoozedUntil)
            }
        }
        val until = Instant.parse("2026-03-02T06:40:00Z")
        port.onLocal(MirrorKind.SNOOZE, members, until)
        // The call made while detached is replayed first, then the forwarded one.
        assertEquals(listOf(Triple(MirrorKind.DISMISS, members, null as Instant?), Triple(MirrorKind.SNOOZE, members, until as Instant?)), calls)
    }
}
