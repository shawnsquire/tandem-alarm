package me.shawnsquire.tandem.ui.alarms

import me.shawnsquire.tandem.alarm.AlarmScheduler
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.schedule.Alarm
import java.time.Instant

/** The one place ui writes alarms: every write is followed by exactly one [AlarmScheduler.rescheduleAll]. */
class AlarmEdits(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
) {
    suspend fun upsert(alarm: Alarm) {
        repository.upsert(alarm)
        scheduler.rescheduleAll()
    }

    suspend fun delete(id: String) {
        repository.delete(id)
        scheduler.rescheduleAll()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        repository.setEnabled(id, enabled)
        scheduler.rescheduleAll()
    }

    suspend fun setSkip(id: String, occurrence: Instant?) {
        repository.setSkip(id, occurrence)
        scheduler.rescheduleAll()
    }
}
