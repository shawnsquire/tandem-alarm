package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.SettingsRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * App-scoped; works purely on the persisted [RingStateStore], so it needs no running service
 * (dismiss while snoozed, remote stop). A live RingingSession observes the store and stops
 * sound and the service when the ring ends or is snoozed.
 */
class RingingControllerImpl(
    private val alarmRepository: AlarmRepository,
    private val ringStateStore: RingStateStore,
    private val settingsRepository: SettingsRepository,
    private val alarmScheduler: AlarmScheduler,
    private val notifier: RingingNotifier,
    private val now: () -> Instant = Instant::now,
) : RingingController {
    private val mutex = Mutex()

    override suspend fun dismiss(ringId: String) = mutex.withLock {
        val state = ringStateStore.state.first()
        // I3: a stop pinned to another (or no) ring is rejected.
        if (state == null || state.ringId != ringId) return@withLock
        ringStateStore.set(null)
        val alarm = alarmRepository.get(state.alarmId)
        if (alarm != null && alarm.days.isEmpty()) alarmRepository.setEnabled(alarm.id, false)
        runCatching { notifier.cancelSnoozed() }
        // State is now null, so this also cancels any pending snooze alarm.
        alarmScheduler.rescheduleAll()
    }

    override suspend fun snooze() = mutex.withLock {
        val state = ringStateStore.state.first()
        if (state == null || state.snoozedUntil != null) return@withLock
        val minutes = try {
            settingsRepository.snoozeMinutes.first()
        } catch (e: Exception) {
            DEFAULT_SNOOZE_MINUTES
        }
        val until = now().plus(minutes.toLong(), ChronoUnit.MINUTES)
        ringStateStore.set(state.copy(snoozedUntil = until))
        alarmScheduler.rescheduleAll()
        runCatching { notifier.showSnoozed(state.ringId, until) }
        Unit
    }

    private companion object {
        const val DEFAULT_SNOOZE_MINUTES = 10
    }
}
