package me.shawnsquire.tandem.alarm

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.UserManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.TandemApp
import me.shawnsquire.tandem.data.RingStateStoreImpl
import me.shawnsquire.tandem.data.ringStateDataStore
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.nextOccurrence
import me.shawnsquire.tandem.sync.PreFireCheck
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The engine before the first unlock. Engines are built from a [LockedContext], not through
 * [AlarmEngine.get]: get() uses applicationContext, which LockedContext can't intercept. LockedContext
 * still arms real PendingIntents (AlarmSchedulerImplTest does the same). Everything runs on real
 * threads: the ring-state DataStore and the scheduler do real IO, which virtual time would skip past.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmEngineTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val locked = LockedContext(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val zone = ZoneId.systemDefault()

    @Before
    fun setUp() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        // TandemApp attaches the process engine at startup; its drain reads the same DE queue, so
        // let it finish before a test queues anything.
        val process = AlarmEngine.get(app).alarmRepository as SwitchableAlarmRepository
        withTimeoutOrNull(5.seconds) { while (!process.isAttached) delay(10) }
        // The ring-state DataStore is a static singleton: a RingState left by an earlier test survives.
        RingStateStoreImpl(app.ringStateDataStore).set(null)
    }

    @After
    fun tearDown() {
        scope.cancel()
        assertEquals("CE storage touched while locked", emptyList<String>(), locked.touches)
    }

    private fun alarm(
        id: String,
        hour: Int = 6,
        minute: Int = 30,
        days: Set<DayOfWeek> = emptySet(),
        enabled: Boolean = true,
        skip: Instant? = null,
        ringtone: String? = null,
    ) = Alarm(id, id, hour, minute, days, enabled, skip, ringtone, null)

    private fun seedMirror(vararg alarms: Alarm, snoozeMinutes: Int? = null, defaultRingtone: String? = null) =
        SharedPrefsAlarmMirror(app).write(MirrorSnapshot(alarms.map { MirroredAlarm(it, null) }, snoozeMinutes, defaultRingtone))

    /** Seed the mirror first: the switchable repository reads it when built. */
    private fun lockedEngine() = AlarmEngine(locked, unlocked = false, scope)

    private val mirrored get() = SharedPrefsAlarmMirror(app).read().alarms.associate { it.alarm.id to it.alarm }
    private val queued get() = SharedPrefsDeferredAlarmWrites(app).all()

    private suspend fun awaitTrue(what: String, condition: suspend () -> Boolean) {
        withTimeoutOrNull(5.seconds) { while (!condition()) delay(10) } ?: throw AssertionError("timed out waiting for $what")
    }

    // ---- boot ----

    @Test
    fun lockedBootRearmsFromTheMirror() = runBlocking {
        // Hours away, so the expected occurrence can't move between the reschedule and the check.
        val time = Instant.now().atZone(zone).plusHours(3)
        val repeating = alarm("rep", time.hour, time.minute, days = DayOfWeek.entries.toSet())
        val off = alarm("off", time.hour, time.minute, enabled = false)
        seedMirror(repeating, off)
        val engine = lockedEngine()

        rescheduleAsync(null, engine.scheduler, scope, rearmOverdueSnooze = true, ringSlot = engine.ringSlot).join()

        val scheduled = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
        val byId = scheduled.groupBy { shadowOf(it.operation!!).savedIntent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID) }
        val armed = byId.getValue("rep").single()
        assertEquals(nextOccurrence(repeating, Instant.now(), zone)!!.toEpochMilli(), armed.triggerAtMs)
        assertNotNull(armed.showIntent)
        assertNull(byId["off"])
    }

    // ---- locked ring ----

    private class CountingPreFire(private val inner: PreFireCheck) : PreFireCheck {
        val results: MutableList<Boolean> = java.util.concurrent.CopyOnWriteArrayList()
        override suspend fun pullAndApply(timeout: Duration): Boolean = inner.pullAndApply(timeout).also { results += it }
    }

    private class LockedRing(val engine: AlarmEngine, scope: CoroutineScope, preFire: PreFireCheck = engine.preFireCheck) {
        val sound = RecordingSound { System.currentTimeMillis() }
        val vibration = RecordingVibration()
        val finished = CompletableDeferred<Unit>()
        val session = RingingSession(
            engine.alarmRepository, engine.ringStateStore, engine.settings, preFire, engine.scheduler,
            engine.controller, engine.ringSlot, sound, vibration, RecordingNotifier(), scope, Dispatchers.IO, Instant::now,
            firedLedger = engine.firedLedger, liveRings = SyncRingRegistry(), onFinished = { finished.complete(Unit) },
        )
    }

    /** A just-due occurrence, in millis like the mirror; a skip on it hasn't expired yet. */
    private fun dueNow() = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @Test
    fun lockedSessionRingsWithMirroredRingtone() = runBlocking {
        seedMirror(alarm("a", ringtone = "content://tone/mirrored"), defaultRingtone = "content://tone/default")
        val ring = LockedRing(lockedEngine(), scope)

        ring.session.start("a", dueNow(), false, null)

        awaitTrue("sound") { ring.sound.starts.isNotEmpty() }
        assertEquals(listOf("content://tone/mirrored", "content://tone/default"), ring.sound.starts.single())
        awaitTrue("ring state") { ring.engine.ringStateStore.state.first()?.alarmId == "a" }
        assertFalse(ring.finished.isCompleted)
    }

    @Test
    fun lockedSessionHonorsMirroredSkipWithoutTheNetwork() = runBlocking {
        val occurrence = dueNow()
        seedMirror(alarm("a", days = setOf(DayOfWeek.MONDAY), skip = occurrence, ringtone = "content://tone/mirrored"))
        val engine = lockedEngine()
        val preFire = CountingPreFire(engine.preFireCheck)
        val ring = LockedRing(engine, scope, preFire)

        ring.session.start("a", occurrence, false, null)

        withTimeout(5.seconds) { ring.finished.await() }
        assertTrue(ring.sound.starts.isEmpty())
        assertEquals(0, ring.vibration.starts)
        // The skip clear is queued for Room and applied to the mirror, which the locked repository reads.
        assertEquals(listOf<DeferredWrite>(DeferredWrite.SetSkip("a", null)), queued)
        assertNull(mirrored.getValue("a").skippedOccurrenceAt)
        assertEquals(occurrence, engine.firedLedger.lastRang("a"))
        // The engine's own check ran once and, with no real check attached, answered "no new info".
        assertEquals(listOf(false), preFire.results)
    }

    @Test
    fun stopOnLockedOneShotDisablesItInTheMirrorAndQueue() = runBlocking {
        seedMirror(alarm("once"))
        val ring = LockedRing(lockedEngine(), scope)
        ring.session.start("once", dueNow(), false, null)
        awaitTrue("ring state") { ring.engine.ringStateStore.state.first()?.alarmId == "once" }
        val ringId = ring.engine.ringStateStore.state.first()!!.ringId

        ring.engine.controller.dismiss(ringId)

        withTimeout(5.seconds) { ring.finished.await() }
        assertNull(ring.engine.ringStateStore.state.first())
        assertFalse(mirrored.getValue("once").enabled)
        assertEquals(listOf<DeferredWrite>(DeferredWrite.SetEnabled("once", false)), queued)
    }

    // ---- attach ----

    @Test
    fun attachDrainsQueuedWritesAndReschedulesRefreshTheMirror() = runBlocking {
        seedMirror(alarm("a"))
        val engine = lockedEngine()
        engine.alarmRepository.setEnabled("a", false)
        assertEquals(listOf<DeferredWrite>(DeferredWrite.SetEnabled("a", false)), queued)

        val repo = FakeAlarmRepository(listOf(alarm("a"), alarm("b", 7, 0)))
        val settings = FakeSettingsRepository().apply {
            setSnoozeMinutes(7)
            setDefaultRingtoneUri("content://tone/real")
        }
        val checks = AtomicInteger()
        val check = object : PreFireCheck {
            override suspend fun pullAndApply(timeout: Duration): Boolean { checks.incrementAndGet(); return true }
        }
        engine.attach(repo, settings) { check }
        // Its alarms flow waits for the drain, so the mirror update sees the attached repository.
        engine.scheduler.rescheduleAll()

        assertFalse(repo.get("a")!!.enabled)
        assertTrue(queued.isEmpty())
        val snapshot = SharedPrefsAlarmMirror(app).read()
        assertEquals(repo.alarms.first(), snapshot.alarms.map { it.alarm })
        assertEquals(7, snapshot.snoozeMinutes)
        assertEquals("content://tone/real", snapshot.defaultRingtoneUri)
        assertTrue(engine.preFireCheck.pullAndApply(3.seconds))
        assertEquals(1, checks.get())
    }

    @Test
    fun attachIsIdempotent() = runBlocking {
        val engine = lockedEngine()
        val first = FakeAlarmRepository(listOf(alarm("first")))
        engine.attach(first, FakeSettingsRepository()) { error("unused") }
        engine.attach(FakeAlarmRepository(listOf(alarm("second"))), FakeSettingsRepository()) { error("unused") }

        assertEquals(listOf("first"), engine.alarmRepository.alarms.first().map { it.id })
    }

    // ---- process wiring ----

    @Test
    fun getReturnsOneEnginePerApplication() {
        val engine = AlarmEngine.get(app)
        assertSame(engine, AlarmEngine.get(app))
        assertSame(engine, AlarmEngine.get(app.createDeviceProtectedStorageContext()))
        // The container's alarm objects are the engine's, so the app and the components share one lock.
        val container = (app as TandemApp).container
        assertSame(engine.scheduler, container.alarmScheduler)
        assertSame(engine.controller, container.ringingController)
        assertSame(engine.ringSlot, container.ringSlot)
    }

    @Test
    fun alarmComponentsAreDirectBootAware() {
        val pm = app.packageManager
        fun name(c: Class<*>) = ComponentName(app, c)
        assertTrue(pm.getReceiverInfo(name(AlarmReceiver::class.java), 0).directBootAware)
        assertTrue(pm.getReceiverInfo(name(BootReceiver::class.java), 0).directBootAware)
        assertTrue(pm.getServiceInfo(name(RingingService::class.java), 0).directBootAware)
        assertTrue(pm.getActivityInfo(name(RingingActivity::class.java), 0).directBootAware)
        val boot = pm.queryBroadcastReceivers(Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED).setPackage(app.packageName), 0)
        assertEquals(listOf(BootReceiver::class.java.name), boot.map { it.activityInfo.name })
    }
}
