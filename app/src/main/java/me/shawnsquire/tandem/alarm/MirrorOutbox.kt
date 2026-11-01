package me.shawnsquire.tandem.alarm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import me.shawnsquire.tandem.schedule.RingMember
import java.time.Instant

/** A raw [RingMirrorPort.onLocal] call made while no real mirror was attached. */
data class MirrorCall(val kind: MirrorKind, val members: List<RingMember>, val snoozedUntil: Instant?)

/** A queued [call] and its [seq]: unique and increasing, never reused even once the queue empties. */
data class QueuedMirrorCall(val seq: Long, val call: MirrorCall)

/** FIFO of [MirrorCall]s in device-protected storage, stored raw (the "is it joined" check needs the CE database). */
interface MirrorOutbox {
    /** Throws when the call could not be committed. */
    fun append(call: MirrorCall)

    /** Readable entries in order; a corrupt one is skipped. */
    fun entries(): List<QueuedMirrorCall>

    /** Removes every entry with a seq up to [seq]. */
    fun removeThrough(seq: Long)
}

/** The queue is one JSON array under one key, written with commit(), with the next seq beside it. */
class SharedPrefsMirrorOutbox(context: Context) : MirrorOutbox {
    private val prefs by lazy { deviceProtectedPrefs(context, PREFS) }

    @Synchronized override fun append(call: MirrorCall) {
        val raw = rawEntries()
        val seq = nextSeq(raw)
        save(raw + encode(QueuedMirrorCall(seq, call)), nextSeq = seq + 1)
    }

    @Synchronized override fun entries(): List<QueuedMirrorCall> = rawEntries().mapNotNull { o ->
        try {
            decode(o)
        } catch (e: Exception) {
            Log.w(TAG, "skipping corrupt mirror outbox entry", e)
            null
        }
    }

    @Synchronized override fun removeThrough(seq: Long) {
        val raw = rawEntries()
        // An entry without a readable seq is corrupt and goes with the trim.
        save(raw.filter { it.optLong("seq", Long.MIN_VALUE) > seq }, nextSeq = nextSeq(raw))
    }

    private fun rawEntries(): List<JSONObject> = try {
        prefs.getString(KEY, null)?.let { s ->
            val array = JSONArray(s)
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }.orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "unreadable mirror outbox", e)
        emptyList()
    }

    private fun nextSeq(raw: List<JSONObject>): Long =
        maxOf(prefs.getLong(KEY_NEXT_SEQ, 0), (raw.maxOfOrNull { it.optLong("seq", -1) } ?: -1) + 1)

    private fun save(entries: List<JSONObject>, nextSeq: Long) {
        val array = JSONArray().apply { entries.forEach { put(it) } }
        check(prefs.edit().putString(KEY, array.toString()).putLong(KEY_NEXT_SEQ, nextSeq).commit()) { "mirror outbox write failed" }
    }

    private fun encode(q: QueuedMirrorCall): JSONObject = JSONObject()
        .put("seq", q.seq)
        .put("kind", q.call.kind.name)
        .put("members", JSONArray().apply {
            q.call.members.forEach { put(JSONObject().put("alarmId", it.alarmId).put("occurrenceAt", it.occurrenceAt.toEpochMilli())) }
        })
        .apply { q.call.snoozedUntil?.let { put("snoozedUntil", it.toEpochMilli()) } }

    private fun decode(o: JSONObject): QueuedMirrorCall {
        val members = o.getJSONArray("members").let { a ->
            (0 until a.length()).map { i ->
                val m = a.getJSONObject(i)
                RingMember(m.getString("alarmId"), Instant.ofEpochMilli(m.getLong("occurrenceAt")))
            }
        }
        val until = if (o.isNull("snoozedUntil")) null else Instant.ofEpochMilli(o.getLong("snoozedUntil"))
        return QueuedMirrorCall(o.getLong("seq"), MirrorCall(MirrorKind.valueOf(o.getString("kind")), members, until))
    }

    private companion object {
        const val TAG = "MirrorOutbox"
        const val PREFS = "alarm_mirror_outbox"
        const val KEY = "calls"
        const val KEY_NEXT_SEQ = "next_seq"
    }
}

/** In-memory queue for tests. */
class InMemoryMirrorOutbox : MirrorOutbox {
    private val calls = ArrayList<QueuedMirrorCall>()
    private var nextSeq = 0L

    @Synchronized override fun append(call: MirrorCall) { calls += QueuedMirrorCall(nextSeq++, call) }

    @Synchronized override fun entries(): List<QueuedMirrorCall> = calls.toList()

    @Synchronized override fun removeThrough(seq: Long) { calls.removeAll { it.seq <= seq } }
}
