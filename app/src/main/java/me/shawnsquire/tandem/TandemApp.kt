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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.alarm.AlarmEngine
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
    val container: AppContainer
        get() = checkNotNull(_container) { "AppContainer is only available after the user unlocks; alarm code must use AlarmEngine" }

    private val startup = TandemStartup(
        isUnlocked = { getSystemService(UserManager::class.java).isUserUnlocked },
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

    /** Runs full startup if the user is unlocked and it hasn't run yet. Main thread. */
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

    private fun fullStartup() {
        fullStartupRuns++
        // Must run before anything touches a Firebase instance.
        if (BuildConfig.USE_EMULATOR) connectToEmulators()
        // Building it attaches the engine to Room and the real settings.
        val container = AppContainer(this).also { _container = it }
        container.authManager.start(container.syncScope)
        container.statePublisher.start()
        container.syncScope.launch { container.statePublisherImpl.publishNow() }
        container.ringingCommandListener.start()
        // WorkManager's provider is only installed at unlock; schedule() catches its absence too.
        try {
            SyncWorker.schedule(this)
        } catch (e: Throwable) {
            Log.w(TAG, "could not schedule periodic sync", e)
        }
        refreshFcmToken(container.syncScope, container.fcmTokenStore)
        // T4: a RingState no live session owns is left from a killed process.
        val clearStale = container.syncScope.launch { container.ringSlot.clearStale(me.shawnsquire.tandem.alarm.LiveRings::isLive) }
        // Applies writes deferred while locked (repository calls wait for the attach drain) and
        // refreshes the device-protected mirror on every start.
        val reschedule = container.syncScope.launch { container.alarmScheduler.rescheduleAll() }
        startupAlarmWork = listOf(clearStale, reschedule)
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
