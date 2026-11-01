package me.shawnsquire.tandem.alarm

import android.content.Context
import java.time.Instant

/**
 * Last occurrence of each alarm that rang, keyed by alarmId. Lets the fire path recognise the
 * second delivery of the same occurrence (a TIME_SET replay racing AlarmManager's own immediate
 * fire of a past alarm). Snooze fires never consult it.
 */
interface FiredLedger {
    fun lastRang(alarmId: String): Instant?
    fun markRang(alarmId: String, occurrence: Instant)
    /**
     * The last occurrence of a linked alarm that rang late, at the old time of an owner's move
     * earlier that day (RingingSession). Not the owner's occurrence, so its dismiss leaves a linked
     * one-shot on. Null when none was ever marked, as for data from before this key existed.
     */
    fun lateAt(alarmId: String): Instant?
    fun markLate(alarmId: String, occurrence: Instant)
}

/** Durable ledger. Writes use commit(): receivers may kill the process right after. */
class SharedPrefsFiredLedger(context: Context) : FiredLedger {
    private val prefs by lazy { deviceProtectedPrefs(context, PREFS) }
    private val late by lazy { deviceProtectedPrefs(context, LATE_PREFS) }

    override fun lastRang(alarmId: String): Instant? =
        if (prefs.contains(alarmId)) Instant.ofEpochMilli(prefs.getLong(alarmId, 0L)) else null

    override fun markRang(alarmId: String, occurrence: Instant) {
        check(prefs.edit().putLong(alarmId, occurrence.toEpochMilli()).commit()) { "fired ledger write failed" }
    }

    override fun lateAt(alarmId: String): Instant? =
        if (late.contains(alarmId)) Instant.ofEpochMilli(late.getLong(alarmId, 0L)) else null

    override fun markLate(alarmId: String, occurrence: Instant) {
        check(late.edit().putLong(alarmId, occurrence.toEpochMilli()).commit()) { "late ring write failed" }
    }

    private companion object {
        const val PREFS = "alarm_fired"
        const val LATE_PREFS = "alarm_rang_late"
    }
}

/** In-memory ledger for tests. */
class InMemoryFiredLedger : FiredLedger {
    private val entries = HashMap<String, Instant>()
    private val late = HashMap<String, Instant>()
    @Synchronized override fun lastRang(alarmId: String): Instant? = entries[alarmId]
    @Synchronized override fun markRang(alarmId: String, occurrence: Instant) { entries[alarmId] = occurrence }
    @Synchronized override fun lateAt(alarmId: String): Instant? = late[alarmId]
    @Synchronized override fun markLate(alarmId: String, occurrence: Instant) { late[alarmId] = occurrence }
}
