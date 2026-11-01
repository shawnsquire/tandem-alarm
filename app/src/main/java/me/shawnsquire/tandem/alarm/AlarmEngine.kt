package me.shawnsquire.tandem.alarm

import android.content.Context
import android.os.UserManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.RingStateStoreImpl
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.data.ringStateDataStore
import me.shawnsquire.tandem.media.SwitchableLinkedMediaCache
import me.shawnsquire.tandem.sync.PreFireCheck
import java.time.Instant

/**
 * Owns every alarm object, so a process has exactly one scheduler, one controller (one ring-slot
 * lock), one set of ledgers and one ring-state store, whether it started before or after the
 * first unlock. Built only from device-protected storage: until [attach], alarm and settings reads
 * come from the DE mirror and the pre-fire check is skipped.
 *
 * [unlocked] only steers the one-time prefs migration; null means the unlock check failed.
 */
class AlarmEngine(context: Context, unlocked: Boolean?, private val scope: CoroutineScope) {
    init {
        // Before anything below opens the moved prefs.
        migrateAlarmPrefsToDeviceStorage(context, unlocked)
    }

    private val mirror = SharedPrefsAlarmMirror(context)
    private val deferred = SharedPrefsDeferredAlarmWrites(context)

    private val switchableAlarms = SwitchableAlarmRepository(mirror, deferred, scope, Instant::now)
    private val switchableSettings = SwitchableSettings(mirror, ::refreshMirror)
    private val switchablePreFireCheck = SwitchablePreFireCheck()
    val alarmRepository: AlarmRepository = switchableAlarms
    val settings: SettingsRepository = switchableSettings
    val preFireCheck: PreFireCheck = switchablePreFireCheck

    val ringStateStore: RingStateStore = RingStateStoreImpl(context.ringStateDataStore)
    private val snoozeLedger: SnoozeLedger = SharedPrefsSnoozeLedger(context)
    val firedLedger: FiredLedger = SharedPrefsFiredLedger(context)

    val scheduler = AlarmSchedulerImpl(
        context, alarmRepository, ringStateStore, snoozeLedger,
        onScheduled = MirrorUpdater(mirror, { switchableAlarms.isAttached }, settings)::onScheduled,
    )
    private val notifier = AndroidRingingNotifier(context)
    /** The real mirror is attached later (after unlock) by assigning [SwitchableRingMirrorPort.delegate]; calls made before are queued on disk. */
    val ringMirrorPort = SwitchableRingMirrorPort(SharedPrefsMirrorOutbox(context))
    /** RingingSession resolves a linked ring's sound and label through it; AppContainer swaps in the real cache later. */
    val linkedMediaCache = SwitchableLinkedMediaCache()
    private val controllerImpl =
        RingingControllerImpl(
            alarmRepository, ringStateStore, settings, scheduler, scheduler, notifier, snoozeLedger, ringMirrorPort, firedLedger,
        )
    val controller: RingingController = controllerImpl
    val ringSlot: RingSlot = controllerImpl

    /** A settings edit: the reschedule's MirrorUpdater writes the new value to the mirror. Never throws. */
    private fun refreshMirror() {
        scope.launch {
            try {
                scheduler.rescheduleAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "reschedule after a settings edit failed", e)
            }
        }
    }

    /** Switches the ports to the real CE-backed implementations once readable. Idempotent: the first call wins. */
    fun attach(alarms: AlarmRepository, settings: SettingsRepository, preFireCheck: () -> PreFireCheck) {
        switchableAlarms.attach(alarms)
        switchableSettings.attach(settings)
        switchablePreFireCheck.attach(preFireCheck)
    }

    companion object {
        private const val TAG = "AlarmEngine"

        private class Cached(val app: Context, val engine: AlarmEngine)

        @Volatile private var cached: Cached? = null

        /**
         * The process engine. Cached per Application: Robolectric gives each test a new one while
         * statics survive; in production there is only one, so this is a plain singleton.
         */
        fun get(context: Context): AlarmEngine {
            val app = context.applicationContext
            cached?.takeIf { it.app === app }?.let { return it.engine }
            return synchronized(this) {
                cached?.takeIf { it.app === app }?.engine ?: build(app).also { cached = Cached(app, it) }
            }
        }

        private fun build(app: Context): AlarmEngine {
            // Null when the check throws: the engine runs as locked either way (it reads only DE
            // storage until attach), but only a definite answer may migrate or abandon the CE prefs.
            val unlocked: Boolean? = try {
                app.getSystemService(UserManager::class.java).isUserUnlocked
            } catch (e: Throwable) {
                Log.w(TAG, "unlock check failed; running as locked, prefs migration deferred", e)
                null
            }
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught", e) },
            )
            return AlarmEngine(app, unlocked, scope)
        }
    }
}
