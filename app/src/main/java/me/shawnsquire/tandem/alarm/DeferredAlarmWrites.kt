package me.shawnsquire.tandem.alarm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** An alarm write made before the first unlock, replayed into Room once it is readable. */
sealed interface DeferredWrite {
    val id: String

    data class SetEnabled(override val id: String, val enabled: Boolean) : DeferredWrite
    data class SetSkip(override val id: String, val occurrence: Instant?) : DeferredWrite
}

/** A queued [write] and its [seq]: unique and increasing, never reused even once the queue empties. */
data class QueuedWrite(val seq: Long, val write: DeferredWrite)

/** FIFO of [DeferredWrite]s in device-protected storage. */
interface DeferredAlarmWrites {
    /** Throws when the write could not be committed. */
    fun append(w: DeferredWrite)
    fun entries(): List<QueuedWrite>
    fun all(): List<DeferredWrite> = entries().map { it.write }

    /** Removes every entry with a seq up to [seq]: by id, so a trim never removes an op it hasn't seen. */
    fun removeThrough(seq: Long)
}

/** The queue is one JSON array under one key, written with commit(), with the next seq beside it. */
class SharedPrefsDeferredAlarmWrites(context: Context) : DeferredAlarmWrites {
    private val prefs by lazy { deviceProtectedPrefs(context, PREFS) }

    @Synchronized override fun append(w: DeferredWrite) {
        val queued = entries()
        // Past the counter and every queued seq, so legacy positional seqs can't collide.
        val seq = maxOf(prefs.getLong(KEY_NEXT_SEQ, 0), (queued.maxOfOrNull { it.seq } ?: -1) + 1)
        save(queued + QueuedWrite(seq, w), nextSeq = seq + 1)
    }

    @Synchronized override fun entries(): List<QueuedWrite> = try {
        prefs.getString(KEY, null)?.let(::decode).orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "unreadable deferred alarm writes", e)
        emptyList()
    }

    @Synchronized override fun removeThrough(seq: Long) = save(entries().filter { it.seq > seq })

    private fun save(writes: List<QueuedWrite>, nextSeq: Long? = null) {
        val array = JSONArray().apply { writes.forEach { put(encode(it)) } }
        val edit = prefs.edit().putString(KEY, array.toString())
        nextSeq?.let { edit.putLong(KEY_NEXT_SEQ, it) }
        check(edit.commit()) { "deferred alarm write failed" }
    }

    private fun encode(q: QueuedWrite): JSONObject = JSONObject().put("seq", q.seq).put("id", q.write.id).apply {
        when (val w = q.write) {
            is DeferredWrite.SetEnabled -> put("op", OP_ENABLED).put("enabled", w.enabled)
            is DeferredWrite.SetSkip -> {
                put("op", OP_SKIP)
                w.occurrence?.let { put("occurrence", it.toEpochMilli()) }
            }
        }
    }

    private fun decode(json: String): List<QueuedWrite> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val id = o.getString("id")
            val write = when (val op = o.getString("op")) {
                OP_ENABLED -> DeferredWrite.SetEnabled(id, o.getBoolean("enabled"))
                OP_SKIP -> DeferredWrite.SetSkip(id, if (o.isNull("occurrence")) null else Instant.ofEpochMilli(o.getLong("occurrence")))
                else -> throw IllegalArgumentException("unknown op $op")
            }
            // Written before seqs existed: by position, which keeps their order.
            QueuedWrite(if (o.has("seq")) o.getLong("seq") else i.toLong(), write)
        }
    }

    private companion object {
        const val TAG = "DeferredAlarmWrites"
        const val PREFS = "alarm_deferred_writes"
        const val KEY = "writes"
        const val KEY_NEXT_SEQ = "next_seq"
        const val OP_ENABLED = "setEnabled"
        const val OP_SKIP = "setSkip"
    }
}

/** In-memory queue for tests. */
class InMemoryDeferredAlarmWrites : DeferredAlarmWrites {
    private val writes = ArrayList<QueuedWrite>()
    private var nextSeq = 0L

    @Synchronized override fun append(w: DeferredWrite) { writes += QueuedWrite(nextSeq++, w) }

    @Synchronized override fun entries(): List<QueuedWrite> = writes.toList()

    @Synchronized override fun removeThrough(seq: Long) { writes.removeAll { it.seq <= seq } }
}
