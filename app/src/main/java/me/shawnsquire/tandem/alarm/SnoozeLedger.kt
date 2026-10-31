package me.shawnsquire.tandem.alarm

import android.content.Context
import java.time.Instant

/** One armed snooze: [ringId] comes back at [until] for [alarmId]. */
data class SnoozeEntry(val ringId: String, val alarmId: String, val until: Instant)

/**
 * Every armed snooze, keyed by ringId. U3-private source of truth for snooze PendingIntents:
 * the single [me.shawnsquire.tandem.schedule.RingState] slot can hold only one ring, so a snooze
 * parked behind a newer ring lives here until it fires or is dismissed.
 */
interface SnoozeLedger {
    fun put(ringId: String, alarmId: String, until: Instant)
    fun remove(ringId: String)
    fun get(ringId: String): SnoozeEntry?
    fun all(): List<SnoozeEntry>
}

/** Durable ledger. Writes use commit(): receivers may kill the process right after. */
class SharedPrefsSnoozeLedger(context: Context) : SnoozeLedger {
    private val prefs by lazy { deviceProtectedPrefs(context, PREFS) }

    override fun put(ringId: String, alarmId: String, until: Instant) {
        check(prefs.edit().putString(ringId, "${until.toEpochMilli()}|$alarmId").commit()) { "snooze ledger write failed" }
    }

    override fun remove(ringId: String) {
        check(prefs.edit().remove(ringId).commit()) { "snooze ledger write failed" }
    }

    override fun get(ringId: String): SnoozeEntry? = prefs.getString(ringId, null)?.let { decode(ringId, it) }

    override fun all(): List<SnoozeEntry> =
        prefs.all.mapNotNull { (ringId, value) -> (value as? String)?.let { decode(ringId, it) } }

    private fun decode(ringId: String, value: String): SnoozeEntry? {
        val sep = value.indexOf('|')
        if (sep <= 0) return null
        val until = value.substring(0, sep).toLongOrNull() ?: return null
        return SnoozeEntry(ringId, value.substring(sep + 1), Instant.ofEpochMilli(until))
    }

    private companion object {
        const val PREFS = "alarm_snoozes"
    }
}

/** In-memory ledger for tests. */
class InMemorySnoozeLedger : SnoozeLedger {
    private val entries = LinkedHashMap<String, SnoozeEntry>()

    @Synchronized override fun put(ringId: String, alarmId: String, until: Instant) {
        entries[ringId] = SnoozeEntry(ringId, alarmId, until)
    }

    @Synchronized override fun remove(ringId: String) {
        entries.remove(ringId)
    }

    @Synchronized override fun get(ringId: String): SnoozeEntry? = entries[ringId]

    @Synchronized override fun all(): List<SnoozeEntry> = entries.values.toList()
}
