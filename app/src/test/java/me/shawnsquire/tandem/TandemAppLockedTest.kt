package me.shawnsquire.tandem

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.os.UserManager
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.alarm.AlarmEngine
import me.shawnsquire.tandem.alarm.MirrorSnapshot
import me.shawnsquire.tandem.alarm.MirroredAlarm
import me.shawnsquire.tandem.alarm.SharedPrefsDeferredAlarmWrites
import me.shawnsquire.tandem.alarm.SharedPrefsAlarmMirror
import me.shawnsquire.tandem.alarm.SwitchableAlarmRepository
import me.shawnsquire.tandem.data.AlarmRepositoryImpl
import me.shawnsquire.tandem.data.RingStateStoreImpl
import me.shawnsquire.tandem.data.TandemDatabase
import me.shawnsquire.tandem.data.ringStateDataStore
import me.shawnsquire.tandem.schedule.Alarm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * TandemApp started by a direct-boot-aware component before the first unlock. Robolectric builds
 * and starts the configured Application before the test body, so this test configures a plain
 * Application and starts its own TandemApp once the user is locked: attachBaseContext (protected,
 * hence reflection) with a [CeTripwire] over Robolectric's base context, then onCreate().
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TandemAppLockedTest {
    private val robolectricApp: Application = RuntimeEnvironment.getApplication()
    private val userManager get() = shadowOf(robolectricApp.getSystemService(UserManager::class.java))

    /**
     * Robolectric never locks credential-encrypted storage, so every CE accessor throws (and is
     * recorded) while [locked]. Also answers getApplicationContext with the TandemApp, as a real
     * process does, so the engine, Room and the DataStores all go through this tripwire.
     */
    private class CeTripwire(base: Context) : ContextWrapper(base) {
        @Volatile var locked = true
        lateinit var app: Context
        val touches: MutableList<String> = CopyOnWriteArrayList()

        private fun <T> ce(what: String, real: () -> T): T {
            if (locked) {
                touches += what
                throw IllegalStateException("CE storage touched while locked: $what")
            }
            return real()
        }

        override fun getApplicationContext(): Context = app
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            ce("prefs $name") { super.getSharedPreferences(name, mode) }
        override fun getFilesDir(): File = ce("filesDir") { super.getFilesDir() }
        override fun getNoBackupFilesDir(): File = ce("noBackupFilesDir") { super.getNoBackupFilesDir() }
        override fun getCacheDir(): File = ce("cacheDir") { super.getCacheDir() }
        override fun getDataDir(): File = ce("dataDir") { super.getDataDir() }
        override fun getDatabasePath(name: String?): File = ce("database $name") { super.getDatabasePath(name) }

        /** Lets a test hold the attach drain at its read of the deferred-write queue. */
        val drainGate = QueueGate()
        override fun createDeviceProtectedStorageContext(): Context =
            object : ContextWrapper(super.createDeviceProtectedStorageContext()) {
                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                    val prefs = super.getSharedPreferences(name, mode)
                    return if (name == DEFERRED_WRITES_PREFS) drainGate.wrap(prefs) else prefs
                }
            }
    }

    /**
     * Once [arm]ed, the next read of the deferred-write queue (the drain's) blocks until [release].
     * The queue's prefs are opened once, by the first locked write, so arm only after that write.
     */
    private class QueueGate {
        @Volatile private var armed = false
        private val reached = CountDownLatch(1)
        private val open = CountDownLatch(1)
        val isHeld get() = reached.count == 0L && open.count > 0L

        fun arm() { armed = true }
        fun release() = open.countDown()

        fun wrap(inner: SharedPreferences): SharedPreferences = object : SharedPreferences by inner {
            override fun getString(key: String?, defValue: String?): String? {
                if (armed) {
                    armed = false
                    reached.countDown()
                    // Bounded, so a broken test can't hang the suite.
                    open.await(10, TimeUnit.SECONDS)
                }
                return inner.getString(key, defValue)
            }
        }
    }

    private val tripwires = mutableListOf<CeTripwire>()

    /**
     * FirebaseApp's default instance is static and keeps the first context it was given, possibly
     * this test's tripwire; a later test (same sandbox) must not find it still armed.
     */
    @After
    fun disarm() = tripwires.forEach { it.locked = false }

    private fun startLockedApp(): Pair<TandemApp, CeTripwire> {
        userManager.setUserUnlocked(false)
        val app = TandemApp()
        val base = CeTripwire(robolectricApp.baseContext).also { it.app = app; tripwires += it }
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(app, base)
        app.onCreate()
        return app to base
    }

    private fun unlock(app: TandemApp) {
        userManager.setUserUnlocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun awaitTrue(what: String, condition: () -> Boolean) {
        withTimeoutOrNull(5.seconds) { while (!condition()) delay(10) } ?: throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun lockedStartBuildsOnlyTheEngineAndUnlockRunsFullStartupOnce() = runBlocking {
        // A stale snooze value the post-unlock reschedule replaces from the real settings; no stale
        // ring state, so clearStale has nothing to clear and only TandemApp's rescheduleAll refreshes it.
        SharedPrefsAlarmMirror(robolectricApp).write(MirrorSnapshot(emptyList(), STALE_SNOOZE, null))
        RingStateStoreImpl(robolectricApp.ringStateDataStore).set(null)

        val (app, base) = startLockedApp()

        val error = assertThrows(IllegalStateException::class.java) { app.container }
        assertEquals(
            "AppContainer is only available after the user unlocks; alarm code must use AlarmEngine",
            error.message,
        )
        val engine = AlarmEngine.get(app)
        val alarms = engine.alarmRepository as SwitchableAlarmRepository
        assertFalse(alarms.isAttached)
        assertEquals(0, app.fullStartupRuns)
        assertEquals(0, engine.scheduler.runs.get())
        // Weak on its own: Robolectric never runs WorkManager's InitializationProvider either way.
        assertFalse(WorkManager.isInitialized())
        assertEquals("CE storage touched while locked", emptyList<String>(), base.touches)

        base.locked = false
        unlock(app)

        val container = app.container
        assertEquals(1, app.fullStartupRuns)
        assertSame(engine.scheduler, container.alarmScheduler)
        // Exactly the stale-ring cleanup and one reschedule, both run to completion.
        val work = app.startupAlarmWork
        assertEquals(2, work.size)
        withTimeout(10.seconds) { work.joinAll() }
        assertTrue("startup alarm work completed normally", work.all { it.isCompleted && !it.isCancelled })
        assertEquals("rescheduleAll runs at full startup", 1, engine.scheduler.runs.get())
        assertTrue("engine attached", alarms.isAttached)
        assertTrue(
            "mirror refreshed by rescheduleAll",
            SharedPrefsAlarmMirror(robolectricApp).read().snoozeMinutes != STALE_SNOOZE,
        )

        // The receiver unregistered itself; a later broadcast or BOOT_COMPLETED changes nothing.
        unlock(app)
        app.ensureStarted()
        assertEquals(1, app.fullStartupRuns)
        assertEquals(1, engine.scheduler.runs.get())
        assertSame(container, app.container)
    }

    @Test
    fun containerWritesWaitForTheDrainOfLockedWrites() = runBlocking {
        val alarm = Alarm("a", "a", 6, 30, emptySet(), true, null, null, null)
        val db = TandemDatabase.build(robolectricApp)
        AlarmRepositoryImpl(db.alarmDao(), Clock.systemDefaultZone()).upsert(alarm)
        SharedPrefsAlarmMirror(robolectricApp).write(MirrorSnapshot(listOf(MirroredAlarm(alarm, null)), null, null))
        val (app, base) = startLockedApp()
        val engine = AlarmEngine.get(app)
        // A Stop on a locked ring disabling it: queued for Room.
        engine.alarmRepository.setEnabled("a", false)
        base.drainGate.arm()

        base.locked = false
        unlock(app)
        // Hold the drain before it replays, so the order below doesn't depend on who wins a race.
        awaitTrue("drain held at the queue read") { base.drainGate.isHeld }
        // A partner command applied at unlock, before the drain is done: it must not be undone by
        // the drain replaying the older locked write.
        val write = CoroutineScope(Dispatchers.Default).async { app.container.alarmRepository.setEnabled("a", true) }
        try {
            // Written straight to Room, it would finish now and the drain would then replay "disabled".
            assertNull("container write finished before the drain", withTimeoutOrNull(1.seconds) { write.await() })
        } finally {
            base.drainGate.release()
        }
        withTimeout(10.seconds) { write.await() }

        assertTrue(app.container.alarmRepository.get("a")!!.enabled)
        assertTrue(AlarmRepositoryImpl(db.alarmDao(), Clock.systemDefaultZone()).get("a")!!.enabled)
        assertTrue(SharedPrefsDeferredAlarmWrites(robolectricApp).all().isEmpty())
        db.close()
    }

    @Test
    fun fullStartupSweepsOrphanedMedia() = runBlocking {
        val now = System.currentTimeMillis()
        fun plant(dir: String, name: String, modified: Long) = File(File(robolectricApp.filesDir, dir).apply { mkdirs() }, name)
            .apply { writeText("x"); check(setLastModified(modified)) }
        val orphanWallpaper = plant("wallpapers", "orphan.jpg", now - 2 * HOUR)
        val orphanTone = plant("ringtones", "orphan.mp3", now - 2 * HOUR)
        val young = plant("ringtones", "editing.mp3", now - 60_000)

        val (app, base) = startLockedApp()
        assertTrue(orphanWallpaper.exists() && orphanTone.exists())

        base.locked = false
        unlock(app)

        awaitTrue("orphans swept") { !orphanWallpaper.exists() && !orphanTone.exists() }
        assertTrue(young.exists())
    }

    @Test
    fun ensureStartedWhileLockedDoesNothing() {
        val (app, base) = startLockedApp()

        app.ensureStarted()

        assertEquals(0, app.fullStartupRuns)
        assertThrows(IllegalStateException::class.java) { app.container }
        assertTrue(base.touches.isEmpty())
    }

    private companion object {
        const val STALE_SNOOZE = 99
        const val HOUR = 60 * 60 * 1000L
        const val DEFERRED_WRITES_PREFS = "alarm_deferred_writes"
    }
}
