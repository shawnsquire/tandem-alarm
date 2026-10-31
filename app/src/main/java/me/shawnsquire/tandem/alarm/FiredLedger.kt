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
}

/** Durable ledger. Writes use commit(): receivers may kill the process right after. */
class SharedPrefsFiredLedger(context: Context) : FiredLedger {
    private val prefs by lazy { deviceProtectedPrefs(context, PREFS) }

    override fun lastRang(alarmId: String): Instant? =
        if (prefs.contains(alarmId)) Instant.ofEpochMilli(prefs.getLong(alarmId, 0L)) else null

    override fun markRang(alarmId: String, occurrence: Instant) {
        check(prefs.edit().putLong(alarmId, occurrence.toEpochMilli()).commit()) { "fired ledger write failed" }
    }

    private companion object {
        const val PREFS = "alarm_fired"
    }
}

/** In-memory ledger for tests. */
class InMemoryFiredLedger : FiredLedger {
    private val entries = HashMap<String, Instant>()
    @Synchronized override fun lastRang(alarmId: String): Instant? = entries[alarmId]
    @Synchronized override fun markRang(alarmId: String, occurrence: Instant) { entries[alarmId] = occurrence }
}
