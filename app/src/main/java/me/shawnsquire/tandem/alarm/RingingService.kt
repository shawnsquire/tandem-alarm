package me.shawnsquire.tandem.alarm

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.shawnsquire.tandem.TandemApp
import java.time.Instant

/** Thin Android glue: foreground promotion, wake lock, and one [RingingSession] per start. */
class RingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = mutableSetOf<RingingSession>()
    private val adapters = mutableListOf<Pair<AlarmSound, AlarmVibration>>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val vibrationArbiter by lazy { VibrationArbiter.forContext(this) }
    private lateinit var notifier: AndroidRingingNotifier

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = AndroidRingingNotifier(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must come first: a startForegroundService() start has a short deadline.
        startForeground(NOTIFICATION_ID, notifier.foregroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        acquireWakeLock()

        val alarmId = intent?.getStringExtra(AlarmIntents.EXTRA_ALARM_ID)
        if (alarmId == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        val occurrence = Instant.ofEpochMilli(intent.getLongExtra(AlarmIntents.EXTRA_OCCURRENCE, System.currentTimeMillis()))
        val isSnooze = intent.getBooleanExtra(AlarmIntents.EXTRA_IS_SNOOZE, false)
        val ringId = intent.getStringExtra(AlarmIntents.EXTRA_RING_ID)

        val container = (application as TandemApp).container
        val sound = MediaPlayerAlarmSound(this)
        val vibration = SystemAlarmVibration(vibrationArbiter)
        adapters += sound to vibration
        lateinit var session: RingingSession
        session = RingingSession(
            alarmRepository = container.alarmRepository,
            ringStateStore = container.ringStateStore,
            settingsRepository = container.settingsRepository,
            preFireCheck = container.preFireCheck,
            alarmScheduler = container.alarmScheduler,
            controller = container.ringingController,
            sound = sound,
            vibration = vibration,
            notifier = notifier,
            scope = scope,
            ioDispatcher = Dispatchers.IO,
            now = Instant::now,
            log = { msg, t -> Log.w(TAG, msg, t) },
            onFinished = {
                sessions.remove(session)
                stopIfIdle()
            },
        )
        sessions += session
        session.start(alarmId, occurrence, isSnooze, ringId)
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (sessions.isNotEmpty()) return
        notifier.clearRinging()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tandem:ringing")
            .also { it.setReferenceCounted(false); wakeLock = it }
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    override fun onDestroy() {
        scope.cancel()
        notifier.clearRinging()
        adapters.forEach { (sound, vibration) ->
            runCatching { sound.stop() }
            runCatching { vibration.stop() }
        }
        super.onDestroy()
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    companion object {
        const val NOTIFICATION_ID = 0x524E47 // "RNG"
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 1000L
        private const val TAG = "RingingService"
    }
}
