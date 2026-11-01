package me.shawnsquire.tandem.alarm

import android.content.Context

/**
 * The snoozes one snoozed merged ring split into, keyed by groupId: each member's ringId, primary
 * first. The group shares one snoozed notification, tagged with its groupId (the primary's ringId
 * until the primary moves out), whose Dismiss resolves every member still snoozed. A ringId in no
 * group is a group of one. Only the controller writes it, under its lock, and only after the
 * [SnoozeLedger] writes: the ledger alone keeps a snooze armed, so a group that can't be read or
 * written falls back to one snooze per entry (I1). A member no longer in the ledger is ignored.
 */
interface SnoozeGroups {
    fun put(groupId: String, members: List<String>)
    fun remove(groupId: String)
    fun all(): Map<String, List<String>>
}

/** Durable groups. Writes use commit(): receivers may kill the process right after. */
class SharedPrefsSnoozeGroups(context: Context) : SnoozeGroups {
    private val prefs by lazy { deviceProtectedPrefs(context, PREFS) }

    override fun put(groupId: String, members: List<String>) {
        check(prefs.edit().putString(groupId, members.joinToString(SEPARATOR)).commit()) { "snooze groups write failed" }
    }

    override fun remove(groupId: String) {
        check(prefs.edit().remove(groupId).commit()) { "snooze groups write failed" }
    }

    override fun all(): Map<String, List<String>> =
        prefs.all.mapNotNull { (groupId, value) -> (value as? String)?.let { groupId to it.split(SEPARATOR).filter(String::isNotEmpty) } }.toMap()

    private companion object {
        const val PREFS = "alarm_snooze_groups"
        const val SEPARATOR = "|"
    }
}

/** In-memory groups for tests. */
class InMemorySnoozeGroups : SnoozeGroups {
    private val groups = LinkedHashMap<String, List<String>>()

    @Synchronized override fun put(groupId: String, members: List<String>) {
        groups[groupId] = members.toList()
    }

    @Synchronized override fun remove(groupId: String) {
        groups.remove(groupId)
    }

    @Synchronized override fun all(): Map<String, List<String>> = LinkedHashMap(groups)
}
