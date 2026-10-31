package me.shawnsquire.tandem.alarm

/** Re-registers OS alarms for all local alarms from local state only (I1). */
interface AlarmScheduler {
    suspend fun rescheduleAll()
}
