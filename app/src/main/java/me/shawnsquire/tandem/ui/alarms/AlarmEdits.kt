package me.shawnsquire.tandem.ui.alarms

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.alarm.AlarmScheduler
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.schedule.Alarm
import java.time.Instant

/** The one place ui writes alarms: every write is followed by exactly one [AlarmScheduler.rescheduleAll]. */
class AlarmEdits(
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
) {
    /**
     * The write and its reschedule are one unit: a caller cancelled in between must not leave an alarm
     * stored but never armed (I1), so both run NonCancellable.
     */
    private suspend fun write(block: suspend () -> Unit) = withContext(NonCancellable) {
        block()
        scheduler.rescheduleAll()
    }

    suspend fun upsert(alarm: Alarm) {
        write { repository.upsert(alarm) }
    }

    suspend fun delete(id: String) {
        write { repository.delete(id) }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        write { repository.setEnabled(id, enabled) }
    }

    suspend fun setSkip(id: String, occurrence: Instant?) {
        write { repository.setSkip(id, occurrence) }
    }
}
