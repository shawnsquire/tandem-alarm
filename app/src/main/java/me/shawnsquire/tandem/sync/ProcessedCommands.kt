package me.shawnsquire.tandem.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Clock
import java.time.Instant

/** How the owner resolved a command; stored so a redelivered command is never applied twice. */
sealed interface Outcome {
    data object Applied : Outcome
    data class Rejected(val reason: String) : Outcome
}

/**
 * Persisted set of recently processed command ids with their outcomes (idempotency by id, I3).
 * Entries are dropped once their command has expired, and the set is capped at [MAX_ENTRIES].
 */
class ProcessedCommands(private val store: DataStore<Preferences>, private val clock: Clock) {
    suspend fun outcome(id: String): Outcome? =
        store.data.first()[KEY].orEmpty().mapNotNull(::decode).firstOrNull { it.id == id }?.outcome

    suspend fun record(id: String, outcome: Outcome, expiresAt: Instant) {
        val nowMs = clock.millis()
        store.edit { prefs ->
            val kept = prefs[KEY].orEmpty().mapNotNull(::decode)
                .filter { it.id != id && it.expiresAtMs > nowMs }
                .sortedBy { it.expiresAtMs }
                .takeLast(MAX_ENTRIES - 1)
            prefs[KEY] = (kept + Entry(id, outcome, expiresAt.toEpochMilli())).map(::encode).toSet()
        }
    }

    private class Entry(val id: String, val outcome: Outcome, val expiresAtMs: Long)

    // expiresAtMs|A|id  or  expiresAtMs|R|id|reason (id and reason URL-encoded, so '|' never appears in them).
    private fun encode(e: Entry): String {
        val id = URLEncoder.encode(e.id, "UTF-8")
        return when (val o = e.outcome) {
            Outcome.Applied -> "${e.expiresAtMs}|A|$id"
            is Outcome.Rejected -> "${e.expiresAtMs}|R|$id|${URLEncoder.encode(o.reason, "UTF-8")}"
        }
    }

    private fun decode(s: String): Entry? {
        val p = s.split('|', limit = 4)
        val expires = p.getOrNull(0)?.toLongOrNull() ?: return null
        val id = p.getOrNull(2)?.let { URLDecoder.decode(it, "UTF-8") } ?: return null
        return when (p[1]) {
            "A" -> Entry(id, Outcome.Applied, expires)
            "R" -> Entry(id, Outcome.Rejected(URLDecoder.decode(p.getOrNull(3) ?: return null, "UTF-8")), expires)
            else -> null
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("processedCommands")
        const val MAX_ENTRIES = 200
    }
}
