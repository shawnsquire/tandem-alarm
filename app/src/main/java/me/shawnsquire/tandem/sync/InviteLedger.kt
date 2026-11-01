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

/**
 * Persisted invite bookkeeping, so a process death forgets neither side of an invite:
 * the owner's open invites (command id and own alarm id, until the partner answers or the command
 * expires) and the ids of invites already notified on the partner's phone (so a redelivered invite
 * doesn't re-post one the user swiped away). Entries are dropped once their command has expired,
 * and each set is capped at [MAX_ENTRIES].
 */
class InviteLedger(private val store: DataStore<Preferences>, private val clock: Clock) {
    class OpenInvite(val commandId: String, val alarmId: String, val expiresAt: Instant)

    /** Open invites whose command hasn't expired. */
    suspend fun open(): List<OpenInvite> {
        val nowMs = clock.millis()
        return store.data.first()[OPEN].orEmpty().mapNotNull(::decodeOpen).filter { it.expiresAtMs > nowMs }
            .map { OpenInvite(it.commandId, it.alarmId, Instant.ofEpochMilli(it.expiresAtMs)) }
    }

    suspend fun addOpen(commandId: String, alarmId: String, expiresAt: Instant) {
        val nowMs = clock.millis()
        store.edit { prefs ->
            val kept = prefs[OPEN].orEmpty().mapNotNull(::decodeOpen)
                .filter { it.commandId != commandId && it.expiresAtMs > nowMs }
                .sortedBy { it.expiresAtMs }
                .takeLast(MAX_ENTRIES - 1)
            prefs[OPEN] = (kept + OpenEntry(commandId, alarmId, expiresAt.toEpochMilli())).map(::encode).toSet()
        }
    }

    suspend fun removeOpen(commandId: String) {
        store.edit { prefs ->
            val current = prefs[OPEN] ?: return@edit
            prefs[OPEN] = current.filterNot { decodeOpen(it)?.commandId == commandId }.toSet()
        }
    }

    /** Records that [commandId] was notified; false if it already was (and hasn't expired). */
    suspend fun markNotified(commandId: String, expiresAt: Instant): Boolean {
        val nowMs = clock.millis()
        var added = false
        store.edit { prefs ->
            val live = prefs[NOTIFIED].orEmpty().mapNotNull(::decodeNotified).filter { it.second > nowMs }
            if (live.any { it.first == commandId }) {
                prefs[NOTIFIED] = live.map { encode(it.first, it.second) }.toSet()
                return@edit
            }
            added = true
            val kept = live.sortedBy { it.second }.takeLast(MAX_ENTRIES - 1)
            prefs[NOTIFIED] = (kept + (commandId to expiresAt.toEpochMilli())).map { encode(it.first, it.second) }.toSet()
        }
        return added
    }

    private class OpenEntry(val commandId: String, val alarmId: String, val expiresAtMs: Long)

    // expiresAtMs|commandId|alarmId and expiresAtMs|commandId (ids URL-encoded, so '|' never appears in them).
    private fun encode(e: OpenEntry) = "${e.expiresAtMs}|${enc(e.commandId)}|${enc(e.alarmId)}"

    private fun encode(commandId: String, expiresAtMs: Long) = "$expiresAtMs|${enc(commandId)}"

    private fun decodeOpen(s: String): OpenEntry? {
        val p = s.split('|')
        if (p.size != 3) return null
        val expires = p[0].toLongOrNull() ?: return null
        return OpenEntry(dec(p[1]), dec(p[2]), expires)
    }

    private fun decodeNotified(s: String): Pair<String, Long>? {
        val p = s.split('|')
        if (p.size != 2) return null
        return dec(p[1]) to (p[0].toLongOrNull() ?: return null)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    private companion object {
        val OPEN = stringSetPreferencesKey("openInvites")
        val NOTIFIED = stringSetPreferencesKey("notifiedInvites")
        const val MAX_ENTRIES = 200
    }
}
