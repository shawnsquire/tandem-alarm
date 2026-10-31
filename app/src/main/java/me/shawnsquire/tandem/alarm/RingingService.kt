package me.shawnsquire.tandem.alarm

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.TandemApp
import java.time.Instant

/** Thin Android glue: foreground promotion, wake lock, and one [RingingSession] per start. */
open class RingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = mutableSetOf<RingingSession>()
    private val adapters = mutableMapOf<RingingSession, Pair<AlarmSound, AlarmVibration>>()
    private val snoozeRingIds = mutableMapOf<RingingSession, String>()
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private val vibrationArbiter by lazy { VibrationArbiter.forContext(this) }
    private lateinit var notifier: AndroidRingingNotifier

    /** ringId to (notification id, notification) for sessions that have started ringing. */
    private val ringingNotifications = LinkedHashMap<String, Pair<Int, Notification>>()
    private var foregroundId = 0

    /** Number of sessions this service has created; lets tests see that a session started. */
    internal var sessionsStarted = 0
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = AndroidRingingNotifier(this, onRinging = ::onRingingPosted)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Must come first: a startForegroundService() start has a short deadline.
        // The quiet prep notification has no full-screen intent: a suppressed fire never lights the screen.
        promoteForStart()
        try { acquireWakeLock() } catch (e: Throwable) { Log.e(TAG, "acquiring ringing wake lock failed", e) }
        FireWakeLock.release() // our own lock is held now

        val alarmId = intent?.getStringExtra(AlarmIntents.EXTRA_ALARM_ID)
        if (alarmId == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        val occurrence = Instant.ofEpochMilli(intent.getLongExtra(AlarmIntents.EXTRA_OCCURRENCE, System.currentTimeMillis()))
        val isSnooze = intent.getBooleanExtra(AlarmIntents.EXTRA_IS_SNOOZE, false)
        val ringId = intent.getStringExtra(AlarmIntents.EXTRA_RING_ID)

        // A repeated snooze-fire for a ring that already has a live session must not stack a second sound.
        if (shouldIgnoreSnoozeStart(isSnooze, ringId, snoozeRingIds.values)) {
            Log.w(TAG, "ignoring duplicate snooze start for ring $ringId")
            stopIfIdle()
            return START_NOT_STICKY
        }

        val container = (application as TandemApp).container
        val sound = MediaPlayerAlarmSound(this)
        val vibration = SystemAlarmVibration(vibrationArbiter)
        lateinit var session: RingingSession
        session = RingingSession(
            alarmRepository = container.alarmRepository,
            ringStateStore = container.ringStateStore,
            settingsRepository = container.settingsRepository,
            preFireCheck = container.preFireCheck,
            alarmScheduler = container.alarmScheduler,
            controller = container.ringingController,
            ringSlot = container.ringSlot,
            sound = sound,
            vibration = vibration,
            notifier = notifier,
            scope = scope,
            ioDispatcher = Dispatchers.IO,
            now = Instant::now,
            log = { msg, t -> Log.w(TAG, msg, t) },
            firedLedger = SharedPrefsFiredLedger(applicationContext),
            liveRings = LiveRings,
            onFinished = {
                sessions.remove(session)
                adapters.remove(session)
                snoozeRingIds.remove(session)
                onSessionFinished(session.ringId)
            },
        )
        sessions += session
        sessionsStarted++
        adapters[session] = sound to vibration
        if (isSnooze && ringId != null) snoozeRingIds[session] = ringId
        // A RingState left by a dead process would otherwise be "superseded" as if it were ringing.
        // Not awaited: the session must never wait on the ring-slot lock before vibrating. The
        // controller's Mutex is fair, so this clear still runs before the session's claim, which
        // is queued only after the pre-fire check.
        launchClearStale(scope, container.ringSlot, LiveRings::isLive) { msg, t -> Log.w(TAG, msg, t) }
        session.start(alarmId, occurrence, isSnooze, ringId)
        return START_NOT_STICKY
    }

    /** Typed foreground start; open so a test can make it throw. */
    internal open fun startForegroundTyped(id: Int, notification: Notification) =
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)

    internal open fun startForegroundPlain(id: Int, notification: Notification) =
        startForeground(id, notification)

    /** startForeground must never throw into the ring: fall back to the untyped call, then log and carry on. */
    private fun safeStartForeground(id: Int, notification: Notification) {
        try {
            startForegroundTyped(id, notification)
            foregroundId = id
            return
        } catch (e: Throwable) {
            Log.e(TAG, "typed startForeground failed; retrying untyped", e)
        }
        try {
            startForegroundPlain(id, notification)
            foregroundId = id
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed entirely; ringing without foreground promotion", e)
        }
    }

    /** Keep a ringing notification as the foreground one on re-entry; prep only when nothing is ringing. */
    private fun promoteForStart() {
        val ringing = ringingNotifications.values.lastOrNull()
        if (ringing != null) safeStartForeground(ringing.first, ringing.second) else promotePrep()
    }

    private fun promotePrep() {
        val prep = try {
            notifier.prepNotification()
        } catch (e: Throwable) {
            Log.e(TAG, "building prep notification failed", e)
            return
        }
        safeStartForeground(AndroidRingingNotifier.PREP_NOTIFICATION_ID, prep)
    }

    /** A session decided to ring: its notification becomes the foreground one and the prep one goes away. */
    internal fun onRingingPosted(ringId: String, id: Int, notification: Notification) {
        ringingNotifications[ringId] = id to notification
        safeStartForeground(id, notification)
        try { notifier.cancelPrep() } catch (e: Throwable) { Log.w(TAG, "cancel prep failed", e) }
    }

    private fun onSessionFinished(ringId: String?) {
        val finished = ringId?.let { ringingNotifications.remove(it) }
        if (sessions.isNotEmpty()) {
            // Foreground moves first: the system won't remove the current foreground notification.
            val next = ringingNotifications.values.lastOrNull()
            val finishedWasForeground = finished != null && finished.first == foregroundId
            if (next != null && (finishedWasForeground || foregroundId == AndroidRingingNotifier.PREP_NOTIFICATION_ID)) {
                safeStartForeground(next.first, next.second)
            } else if (finishedWasForeground) promotePrep()
        }
        stopIfIdle()
        if (ringId != null && finished != null) {
            try { notifier.cancelRinging(ringId) } catch (e: Throwable) { Log.w(TAG, "cancel ringing failed", e) }
        }
    }

    private fun stopIfIdle() {
        if (sessions.isNotEmpty()) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Only stops if no newer start is queued; otherwise that start's session must survive.
        stopSelfResult(lastStartId)
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tandem:ringing")
            .also { it.setReferenceCounted(false); wakeLock = it }
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    override fun onDestroy() {
        scope.cancel()
        // Sessions cut off here never reach finish(): release their rings so a cleaner can clear them.
        releaseRings(sessions.mapNotNull { it.ringId }, LiveRings) { runCatching { notifier.cancelRinging(it) } }
        ringingNotifications.keys.forEach { runCatching { notifier.cancelRinging(it) } }
        ringingNotifications.clear()
        adapters.values.forEach { (sound, vibration) ->
            runCatching { sound.stop() }
            runCatching { vibration.stop() }
        }
        super.onDestroy()
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    companion object {
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 1000L
        private const val TAG = "RingingService"
    }
}

/**
 * Clears a stale RingState without a reschedule: the scheduler never reads RingState, and a
 * reschedule would only hold the ring-slot lock longer (Room, binder).
 */
internal fun launchClearStale(
    scope: CoroutineScope,
    ringSlot: RingSlot,
    isLive: (String) -> Boolean,
    log: (String, Throwable?) -> Unit,
): Job = scope.launch {
    try {
        ringSlot.clearStale(isLive, reschedule = false)
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        log("clearing stale ring state failed", e)
    }
}

/** Unregisters [ringIds] from [registry] and cancels each one's ringing notification. */
internal fun releaseRings(ringIds: Collection<String>, registry: RingRegistry, cancelNotification: (String) -> Unit) {
    for (id in ringIds) {
        runCatching { registry.unregister(id) }
        runCatching { cancelNotification(id) }
    }
}

/** True when a snooze-fire start targets a ring that already has a live session. */
internal fun shouldIgnoreSnoozeStart(isSnooze: Boolean, ringId: String?, liveSnoozeRingIds: Collection<String>): Boolean =
    isSnooze && ringId != null && ringId in liveSnoozeRingIds
