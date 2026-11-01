package me.shawnsquire.tandem.alarm

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.os.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.nextOccurrence
import me.shawnsquire.tandem.sync.PreFireCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLooper
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * The real direct-boot components against the process engine before the first unlock. A plain
 * Application (as in TandemAppLockedTest) means no TandemApp startup attaches the engine, so the
 * first [AlarmEngine.get], made after the user is locked, builds a locked engine over the mirror.
 * The test thread is Robolectric's main thread, so waits idle the main looper instead of suspending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LockedComponentsTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()

    @Before
    fun lock() {
        FireWakeLock.resetForTest()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
    }

    private fun alarm(id: String, hour: Int = 6, minute: Int = 30, days: Set<DayOfWeek> = emptySet()) =
        Alarm(id, id, hour, minute, days, true, null, null, null)

    /** Seeds the mirror, then builds the process engine, which reads it. */
    private fun lockedProcessEngine(vararg alarms: Alarm): AlarmEngine {
        SharedPrefsAlarmMirror(app).write(MirrorSnapshot(alarms.map { MirroredAlarm(it, null) }, null, null))
        val engine = AlarmEngine.get(app)
        assertFalse("engine locked", (engine.alarmRepository as SwitchableAlarmRepository).isAttached)
        // The ring-state DataStore is a static singleton: a RingState left by an earlier test survives.
        runBlocking { engine.ringStateStore.set(null) }
        return engine
    }

    private fun <T : Any> awaitMain(what: String, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            ShadowLooper.idleMainLooper()
            read()?.let { return it }
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun lockedBootCompletedRearmsFromTheMirror() {
        // Hours away, so the expected occurrence can't move between the reschedule and the check.
        val time = Instant.now().atZone(zone).plusHours(3)
        val repeating = alarm("rep", time.hour, time.minute, days = DayOfWeek.entries.toSet())
        lockedProcessEngine(repeating)

        BootReceiver().onReceive(app, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        val alarmManager = shadowOf(app.getSystemService(AlarmManager::class.java))
        val armed = awaitMain("alarm clock armed") {
            alarmManager.scheduledAlarms.singleOrNull {
                shadowOf(it.operation!!).savedIntent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID) == "rep"
            }
        }
        assertEquals(nextOccurrence(repeating, Instant.now(), zone)!!.toEpochMilli(), armed.triggerAtMs)
        assertTrue("an alarm clock, with a show intent", armed.showIntent != null)
    }

    /** A record whose occurrence passed 2 minutes ago: a boot replays it, a plain reschedule must not. */
    private fun seedRecentlyMissedRecord(id: String) {
        val missedAt = Instant.now().minusSeconds(120).toEpochMilli()
        deviceProtectedPrefs(app, "alarm_scheduler").edit()
            .putStringSet("scheduled_records", setOf("$missedAt|$id")).commit()
    }

    @Test
    fun bootCompletedReplaysARecentlyMissedAlarm() {
        // Control for the test below: the same setup does show a replay on a boot.
        val time = Instant.now().atZone(zone).plusHours(3)
        val engine = lockedProcessEngine(alarm("a", time.hour, time.minute))
        seedRecentlyMissedRecord("a")

        BootReceiver().onReceive(app, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        awaitMain("replay armed") { AlarmIntents.existingReplayPendingIntent(app, "a") }
        assertEquals(1, (engine.scheduler as AlarmSchedulerImpl).runs.get())
    }

    @Test
    fun myPackageReplacedRunsAPlainRescheduleWithoutReplays() {
        val time = Instant.now().atZone(zone).plusHours(3)
        val engine = lockedProcessEngine(alarm("a", time.hour, time.minute))
        seedRecentlyMissedRecord("a")

        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val alarmManager = shadowOf(app.getSystemService(AlarmManager::class.java))
        awaitMain("alarm armed by the reschedule") {
            alarmManager.scheduledAlarms.firstOrNull {
                shadowOf(it.operation!!).savedIntent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID) == "a"
            }
        }
        assertEquals("scheduler ran", 1, (engine.scheduler as AlarmSchedulerImpl).runs.get())
        assertNull("no replay on an update", AlarmIntents.existingReplayPendingIntent(app, "a"))
    }

    private class CountingPreFire : PreFireCheck {
        val calls = AtomicInteger()
        override suspend fun pullAndApply(timeout: Duration, linkedOwnerAlarmId: String?): Boolean { calls.incrementAndGet(); return false }
    }

    @Test
    fun ringingServiceRingsThroughTheProcessEngine() {
        // Only the mirror knows "a": a session reading Room (or any other repository) would find it
        // deleted and stay quiet.
        val engine = lockedProcessEngine(alarm("a"))
        // Only the pre-fire port: alarm and settings reads stay on the mirror.
        val preFire = CountingPreFire()
        (engine.preFireCheck as SwitchablePreFireCheck).attach { preFire }
        val occurrence = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val intent = Intent(app, RingingService::class.java)
            .setAction(AlarmIntents.ACTION_FIRE)
            .putExtra(AlarmIntents.EXTRA_ALARM_ID, "a")
            .putExtra(AlarmIntents.EXTRA_OCCURRENCE, occurrence.toEpochMilli())
        val controller = Robolectric.buildService(RingingService::class.java, intent).create()
        try {
            controller.startCommand(0, 1)

            val ring = awaitMain("ring state in the engine's store") {
                runBlocking { engine.ringStateStore.state.first() }?.takeIf { it.alarmId == "a" }
            }
            awaitMain("occurrence marked in the engine's fired ledger") {
                engine.firedLedger.lastRang("a")?.takeIf { it == occurrence }
            }
            assertEquals(1, preFire.calls.get())
            assertTrue("ring registered as live", LiveRings.isLive(ring.ringId))

            // The engine's controller clears the engine's store; the service's session sees it and ends.
            // Off the main thread: the session it ends runs on the main looper, which only we idle.
            val dismiss = CoroutineScope(Dispatchers.Default).launch { engine.controller.dismiss(ring.ringId) }
            awaitMain("dismiss done") { true.takeIf { dismiss.isCompleted } }
            awaitMain("service stopped after the engine's dismiss") {
                true.takeIf { shadowOf(controller.get()).isStoppedBySelf }
            }
            assertFalse(LiveRings.isLive(ring.ringId))
        } finally {
            controller.destroy()
        }
    }
}
