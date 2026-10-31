package me.shawnsquire.tandem

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.functions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.alarm.AlarmEngine
import me.shawnsquire.tandem.media.MediaOrphanSweeper
import me.shawnsquire.tandem.sync.SyncWorker
import me.shawnsquire.tandem.sync.refreshFcmToken

/**
 * Direct-boot-aware components (alarm receiver, boot receiver, ringing service and activity) can
 * start the process before the first unlock. Then only the [AlarmEngine] is built, from
 * device-protected storage; Firebase instances, WorkManager and the CE-backed [AppContainer] wait
 * for [TandemStartup] to see the user unlocked.
 */
class TandemApp : Application() {
    @Volatile private var _container: AppContainer? = null

    /**
     * Starts up on first access if the user is unlocked, which always holds for a
     * credential-encrypted component: an activity or the FCM service can run before USER_UNLOCKED
     * reaches the receiver (Android 14+ defers it to a cached process). Still throws while locked,
     * or if full startup failed again.
     */
    val container: AppContainer
        get() = _container ?: run {
            startup.ensureStarted()
            checkNotNull(_container) { "AppContainer is only available after the user unlocks; alarm code must use AlarmEngine" }
        }

    private val startup = TandemStartup(
        // A check that throws counts as locked: onCreate on a direct-boot alarm start must not
        // crash, and ensureStarted never throws.
        isUnlocked = { runCatching { getSystemService(UserManager::class.java).isUserUnlocked }.getOrDefault(false) },
        registerUnlockReceiver = ::registerUnlockReceiver,
        fullStartup = ::fullStartup,
    )

    @VisibleForTesting
    internal var fullStartupRuns = 0
        private set

    /** Full startup's background alarm work; tests sharing the mirror or ring state wait for it. */
    @VisibleForTesting
    internal var startupAlarmWork: List<Job> = emptyList()
        private set

    override fun onCreate() {
        super.onCreate()
        // No-op when FirebaseInitProvider already ran; needed under Robolectric, which skips providers.
        // Safe while locked (the provider is direct-boot aware); instances and useEmulator are not.
        FirebaseApp.initializeApp(this)
        AlarmEngine.get(this)
        startup.start()
    }

    /**
     * Runs full startup if the user is unlocked and it hasn't succeeded yet. Any thread; blocks
     * while another thread is running it. Never throws.
     */
    fun ensureStarted() = startup.ensureStarted()

    /**
     * A receiver flagged RECEIVER_NOT_EXPORTED still gets this broadcast: it comes from the system
     * itself, not a privileged app outside the system UID. developer.android.com/develop/background-work/background-tasks/broadcasts:
     * "If you flag your receiver with RECEIVER_NOT_EXPORTED, the receiver is able to receive some
     * system broadcasts and broadcasts from your app, but not broadcasts from the highly privileged apps."
     */
    private fun registerUnlockReceiver(onUnlocked: () -> Unit) {
        val receiver = object : BroadcastReceiver() {
            // Delivered on the main thread, like onCreate.
            override fun onReceive(context: Context, intent: Intent) {
                onUnlocked()
                try {
                    unregisterReceiver(this)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "unlock receiver already unregistered", e)
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // Guarded by TandemStartup's lock, like everything fullStartup touches.
    private var emulatorsConnected = false

    /** Runs under TandemStartup's lock, on whichever thread first saw the user unlocked. */
    private fun fullStartup() {
        fullStartupRuns++
        // Must run before anything touches a Firebase instance. One attempt per process, set before
        // it runs: useEmulator throws when repeated or after first use, so a retry would always fail.
        if (BuildConfig.USE_EMULATOR && !emulatorsConnected) {
            emulatorsConnected = true
            connectToEmulators()
        }
        // Building it attaches the engine to Room and the real settings. Assigned only once built,
        // so a throw here leaves no container and the next ensureStarted retries.
        val container = AppContainer(this).also { _container = it }
        // Alarm work first, so no sync start can skip it.
        // T4: a RingState no live session owns is left from a killed process.
        // No reschedule of its own: the startup reschedule right after covers it, and a second one
        // would hold the ring-slot mutex longer and could push a concurrent ring's claim into its timeout.
        val clearStale = container.syncScope.launch {
            container.ringSlot.clearStale(me.shawnsquire.tandem.alarm.LiveRings::isLive, reschedule = false)
        }
        // Applies writes deferred while locked (repository calls wait for the attach drain) and
        // refreshes the device-protected mirror on every start.
        val reschedule = container.syncScope.launch { container.alarmScheduler.rescheduleAll() }
        startupAlarmWork = listOf(clearStale, reschedule)
        // Background housekeeping, never on a fire path; its repository reads wait for the drain.
        container.syncScope.launch {
            try {
                MediaOrphanSweeper(filesDir, container.alarmRepository, container.settingsRepository).sweep()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "media orphan sweep failed", e)
            }
        }
        // Each sync start fails alone: nothing after the container is assigned may throw, so one
        // broken start can't take the others down or make a retry rebuild the container.
        syncStart("auth") { container.authManager.start(container.syncScope) }
        syncStart("state publisher") { container.statePublisher.start() }
        syncStart("initial publish") { container.syncScope.launch { container.statePublisherImpl.publishNow() } }
        syncStart("ringing command listener") { container.ringingCommandListener.start() }
        // WorkManager's provider is only installed at unlock; schedule() catches its absence too.
        syncStart("periodic sync") { SyncWorker.schedule(this) }
        syncStart("FCM token refresh") { refreshFcmToken(container.syncScope, container.fcmTokenStore) }
    }

    private inline fun syncStart(what: String, start: () -> Unit) {
        try {
            start()
        } catch (e: Throwable) {
            Log.w(TAG, "could not start $what", e)
        }
    }

    private fun connectToEmulators() {
        val host = BuildConfig.EMULATOR_HOST
        Firebase.auth.useEmulator(host, 9099)
        Firebase.firestore.useEmulator(host, 8080)
        Firebase.functions.useEmulator(host, 5001)
    }

    private companion object {
        const val TAG = "TandemApp"
    }
}
