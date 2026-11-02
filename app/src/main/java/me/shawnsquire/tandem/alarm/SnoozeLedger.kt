package me.shawnsquire.tandem.alarm

import android.content.Context
import java.time.Instant

/**
 * One armed snooze: [ringId] comes back at [until] for [alarmId]. [occurrenceAt] is the scheduled
 * occurrence the ring belongs to (null for entries written before linked alarms). [snoozeCount] is
 * how many times the ring has been snoozed, this snooze included (0 for entries written before it
 * was counted).
 */
data class SnoozeEntry(
    val ringId: String,
    val alarmId: String,
    val until: Instant,
    val occurrenceAt: Instant? = null,
    val snoozeCount: Int = 0,
)

/**
 * Every armed snooze, keyed by ringId. U3-private source of truth for snooze PendingIntents:
 * the single [me.shawnsquire.tandem.schedule.RingState] slot can hold only one ring, so a snooze
 * parked behind a newer ring lives here until it fires or is dismissed.
 */
interface SnoozeLedger {
    fun put(ringId: String, alarmId: String, until: Instant, occurrenceAt: Instant? = null, snoozeCount: Int = 0)
    fun remove(ringId: String)
    fun get(ringId: String): SnoozeEntry?
    fun all(): List<SnoozeEntry>
}

/** Durable ledger. Writes use commit(): receivers may kill the process right after. */
class SharedPrefsSnoozeLedger(context: Context) : SnoozeLedger {
    private val prefs by lazy { deviceProtectedPrefs(context, PREFS) }

    override fun put(ringId: String, alarmId: String, until: Instant, occurrenceAt: Instant?, snoozeCount: Int) {
        // "until", "until;occurrence" or "until;occurrence;count": a zero count is left out, and a count
        // without an occurrence leaves that part empty.
        val head = when {
            snoozeCount > 0 -> "${until.toEpochMilli()};${occurrenceAt?.toEpochMilli() ?: ""};$snoozeCount"
            occurrenceAt == null -> "${until.toEpochMilli()}"
            else -> "${until.toEpochMilli()};${occurrenceAt.toEpochMilli()}"
        }
        check(prefs.edit().putString(ringId, "$head|$alarmId").commit()) { "snooze ledger write failed" }
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
        // Head is "until" (old format), "until;occurrence" or "until;occurrence;count".
        val parts = value.substring(0, sep).split(';')
        val until = parts[0].toLongOrNull() ?: return null
        val occ = parts.getOrNull(1)?.toLongOrNull()?.let(Instant::ofEpochMilli)
        // A bad occurrence or count part must not drop the entry: a parked snooze still rings (I1).
        val count = parts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return SnoozeEntry(ringId, value.substring(sep + 1), Instant.ofEpochMilli(until), occ, count)
    }

    private companion object {
        const val PREFS = "alarm_snoozes"
    }
}

/** In-memory ledger for tests. */
class InMemorySnoozeLedger : SnoozeLedger {
    private val entries = LinkedHashMap<String, SnoozeEntry>()

    @Synchronized override fun put(ringId: String, alarmId: String, until: Instant, occurrenceAt: Instant?, snoozeCount: Int) {
        entries[ringId] = SnoozeEntry(ringId, alarmId, until, occurrenceAt, snoozeCount)
    }

    @Synchronized override fun remove(ringId: String) {
        entries.remove(ringId)
    }

    @Synchronized override fun get(ringId: String): SnoozeEntry? = entries[ringId]

    @Synchronized override fun all(): List<SnoozeEntry> = entries.values.toList()
}
