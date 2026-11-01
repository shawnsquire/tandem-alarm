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
import me.shawnsquire.tandem.awaitTandemStartup
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
        // TandemApp's startup reschedule rewrites the mirror; its stale-ring cleanup clears ring state.
        awaitTandemStartup()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        // TandemApp attaches the process engine at startup; its drain reads the same DE queue, so
        // it must be done before a test queues anything. The startup rescheduleAll awaited above
        // reads the alarms, which waits for that drain.
        val process = AlarmEngine.get(app).alarmRepository as SwitchableAlarmRepository
        assertTrue("process engine not attached after TandemApp's startup work", process.isAttached)
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
        override suspend fun pullAndApply(timeout: Duration, linkedOwnerAlarmId: String?): Boolean = inner.pullAndApply(timeout, linkedOwnerAlarmId).also { results += it }
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

    // ---- unreadable mirror (I1: a read problem rings, never suppresses) ----

    private val mirrorPrefs get() = deviceProtectedPrefs(app, "alarm_mirror")
    private fun putRawMirror(raw: String) = check(mirrorPrefs.edit().putString("snapshot", raw).commit())

    /** A mirror whose second alarm fails to decode: readable JSON, but not every alarm in it. */
    private fun partlyDecodableMirror(good: Alarm): String {
        seedMirror(good)
        val root = org.json.JSONObject(mirrorPrefs.getString("snapshot", null)!!)
        root.getJSONArray("alarms").put(org.json.JSONObject().put("id", "broken"))
        return root.toString()
    }

    @Test
    fun lockedFireWithAnUnreadableMirrorRingsWithDefaults() = runBlocking {
        putRawMirror("not json")
        val ring = LockedRing(lockedEngine(), scope)

        ring.session.start("a", dueNow(), false, null)

        awaitTrue("sound") { ring.sound.starts.isNotEmpty() }
        assertEquals(listOf(emptyList<String>()), ring.sound.starts)
        // The in-claim re-check throws too, which counts as due, so the ring holds the slot.
        awaitTrue("ring state") { ring.engine.ringStateStore.state.first()?.alarmId == "a" }
        assertFalse(ring.finished.isCompleted)
    }

    @Test
    fun lockedFireForAnIdMissingFromTheMirrorRings() = runBlocking {
        // Locked, delete throws, so a missing id is a read problem, never a local delete.
        seedMirror(alarm("other", ringtone = "content://tone/other"), defaultRingtone = "content://tone/default")
        val ring = LockedRing(lockedEngine(), scope)

        ring.session.start("a", dueNow(), false, null)

        awaitTrue("sound") { ring.sound.starts.isNotEmpty() }
        assertEquals(listOf(listOf("content://tone/default")), ring.sound.starts)
        awaitTrue("ring state") { ring.engine.ringStateStore.state.first()?.alarmId == "a" }
        assertFalse(ring.finished.isCompleted)
    }

    private val alarmManager get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    /** A reboot: AlarmManager and every PendingIntent it held are gone. */
    private fun reboot() {
        for (alarm in alarmManager.scheduledAlarms) {
            alarm.operation?.let { app.getSystemService(AlarmManager::class.java).cancel(it); it.cancel() }
        }
        assertTrue(alarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun lockedBootWithAnUnreadableMirrorRearmsFromTheRecords() = runBlocking {
        val time = Instant.now().atZone(zone).plusHours(3)
        val a = alarm("a", time.hour, time.minute, days = DayOfWeek.entries.toSet())
        val snoozeUntil = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS)
        for (raw in listOf("not json", partlyDecodableMirror(a))) {
            seedMirror(a)
            val armed = lockedEngine()
            armed.scheduler.rescheduleAll()
            val records = deviceProtectedPrefs(app, "alarm_scheduler").getStringSet("scheduled_records", null)
            val recordedAt = records!!.single().substringBefore('|').toLong()
            SharedPrefsSnoozeLedger(app).put("ring-1", "a", snoozeUntil)
            reboot()

            putRawMirror(raw)
            val engine = lockedEngine()
            rescheduleAsync(null, engine.scheduler, scope, rearmOverdueSnooze = true, ringSlot = engine.ringSlot).join()

            val byOperation = alarmManager.scheduledAlarms.groupBy { shadowOf(it.operation!!).savedIntent.data.toString() }
            val fire = byOperation.getValue("tandem://alarm/a").single()
            assertEquals(raw, recordedAt, fire.triggerAtMs)
            assertEquals(raw, recordedAt, shadowOf(fire.operation!!).savedIntent.getLongExtra(AlarmIntents.EXTRA_OCCURRENCE, -1))
            assertNotNull(raw, fire.showIntent)
            assertEquals(raw, records, deviceProtectedPrefs(app, "alarm_scheduler").getStringSet("scheduled_records", null))
            // Snoozes come from the ledger, not the mirror: still re-armed.
            assertEquals(raw, snoozeUntil.toEpochMilli(), byOperation.getValue("tandem://snooze/ring-1").single().triggerAtMs)
            assertEquals(raw, raw, mirrorPrefs.getString("snapshot", null))
            SharedPrefsSnoozeLedger(app).remove("ring-1")
        }
    }

    @Test
    fun lockedSetEnabledWithAnUnreadableMirrorDoesNotWipeIt() = runBlocking {
        for (raw in listOf("not json", partlyDecodableMirror(alarm("a")))) {
            putRawMirror(raw)
            val engine = lockedEngine()

            val e = runCatching { engine.alarmRepository.setEnabled("a", false) }.exceptionOrNull()
            assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
            val skip = runCatching { engine.alarmRepository.setSkip("a", dueNow()) }.exceptionOrNull()
            assertTrue("expected IllegalStateException, got $skip", skip is IllegalStateException)

            assertEquals(raw, mirrorPrefs.getString("snapshot", null))
            assertTrue(queued.isEmpty())
        }
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
            override suspend fun pullAndApply(timeout: Duration, linkedOwnerAlarmId: String?): Boolean { checks.incrementAndGet(); return true }
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
    fun settingsEditsAfterAttachRefreshTheMirror() = runBlocking {
        seedMirror(alarm("a"), snoozeMinutes = 5, defaultRingtone = "content://tone/old")
        val engine = lockedEngine()
        engine.attach(FakeAlarmRepository(listOf(alarm("a"))), FakeSettingsRepository()) { error("unused") }

        engine.settings.setSnoozeMinutes(12)
        awaitTrue("snooze mirrored") { SharedPrefsAlarmMirror(app).read().snoozeMinutes == 12 }
        engine.settings.setDefaultRingtoneUri("content://tone/new")
        awaitTrue("ringtone mirrored") { SharedPrefsAlarmMirror(app).read().defaultRingtoneUri == "content://tone/new" }
        assertEquals(12, SharedPrefsAlarmMirror(app).read().snoozeMinutes)
    }

    @Test
    fun oneShotRingStartedLockedAndStoppedAfterAttachIsDisabledInRoom() = runBlocking {
        seedMirror(alarm("once"))
        val ring = LockedRing(lockedEngine(), scope)
        ring.session.start("once", dueNow(), false, null)
        awaitTrue("ring state") { ring.engine.ringStateStore.state.first()?.alarmId == "once" }
        val ringId = ring.engine.ringStateStore.state.first()!!.ringId

        // The user unlocks mid-ring.
        val room = FakeAlarmRepository(listOf(alarm("once")))
        ring.engine.attach(room, FakeSettingsRepository()) { error("unused") }
        awaitTrue("drain") { (ring.engine.alarmRepository as SwitchableAlarmRepository).isAttached }
        ring.engine.controller.dismiss(ringId)

        withTimeout(5.seconds) { ring.finished.await() }
        assertFalse(room.get("once")!!.enabled)
        assertTrue(queued.isEmpty())
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
        // Every container consumer goes through the attach drain, and there is one ring-state store.
        assertSame(engine.alarmRepository, container.alarmRepository)
        assertSame(engine.ringStateStore, container.ringStateStore)
        // Settings edits go through the engine, which refreshes the mirror after them.
        assertSame(engine.settings, container.settingsRepository)
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
        val updated = pm.queryBroadcastReceivers(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(app.packageName), 0)
        assertEquals(listOf(BootReceiver::class.java.name), updated.map { it.activityInfo.name })
    }
}
