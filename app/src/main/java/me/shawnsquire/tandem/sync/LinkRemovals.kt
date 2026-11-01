package me.shawnsquire.tandem.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.URLDecoder
import java.net.URLEncoder

/** Why a linked row is waiting to be removed (see [LinkRemovals]). */
enum class RemovalReason {
    /** The owner deleted the alarm; the joiner is told when the row goes. */
    OWNER_DELETED,

    /** The joiner left the link. */
    LEFT,

    /** The household is gone or changed partner. */
    UNPAIRED,
}

/**
 * Durable set of linked rows waiting to be removed, with the reason. A row that is ringing is not
 * removed until the ring ends ("the ring finishes normally; the row is removed afterwards"), so the
 * request is stored and survives a process death. LinkSync is the only writer that executes removals;
 * other sync classes read [pendingIds] to ignore a row that is on its way out.
 */
class LinkRemovals(private val store: DataStore<Preferences>) {
    /** Row id to reason, for every pending removal. */
    val pending: Flow<Map<String, RemovalReason>> = store.data.map { decodeAll(it[KEY]) }.distinctUntilChanged()

    /** Ids of the rows pending removal. */
    val pendingIds: Flow<Set<String>> = pending.map { it.keys }.distinctUntilChanged()

    suspend fun all(): Map<String, RemovalReason> = pending.first()

    /** Records the request. An existing request is kept, except that [RemovalReason.LEFT] replaces it. */
    suspend fun add(rowId: String, reason: RemovalReason) {
        store.edit { prefs ->
            val current = decodeAll(prefs[KEY])
            if (current[rowId] == null || reason == RemovalReason.LEFT) {
                prefs[KEY] = (current + (rowId to reason)).map { (id, r) -> encode(id, r) }.toSet()
            }
        }
    }

    suspend fun remove(rowId: String) {
        store.edit { prefs ->
            val current = decodeAll(prefs[KEY])
            if (rowId in current) prefs[KEY] = (current - rowId).map { (id, r) -> encode(id, r) }.toSet()
        }
    }

    // REASON|id, with the id URL-encoded so '|' never appears in it.
    private fun encode(id: String, reason: RemovalReason) = "${reason.name}|${URLEncoder.encode(id, "UTF-8")}"

    private fun decodeAll(set: Set<String>?): Map<String, RemovalReason> = buildMap {
        for (s in set.orEmpty()) {
            val p = s.split('|', limit = 2)
            val reason = p.getOrNull(0)?.let { n -> RemovalReason.entries.firstOrNull { it.name == n } } ?: continue
            val id = p.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: continue
            put(id, reason)
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("link_removals")
    }
}
