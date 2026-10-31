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

/** FIFO of [DeferredWrite]s in device-protected storage. */
interface DeferredAlarmWrites {
    /** Throws when the write could not be committed. */
    fun append(w: DeferredWrite)
    fun all(): List<DeferredWrite>
    fun removeFirst(n: Int)
}

/** The queue is one JSON array under one key, written with commit(). */
class SharedPrefsDeferredAlarmWrites(context: Context) : DeferredAlarmWrites {
    private val prefs by lazy { deviceProtectedPrefs(context, PREFS) }

    @Synchronized override fun append(w: DeferredWrite) = save(all() + w)

    @Synchronized override fun all(): List<DeferredWrite> = try {
        prefs.getString(KEY, null)?.let(::decode).orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "unreadable deferred alarm writes", e)
        emptyList()
    }

    @Synchronized override fun removeFirst(n: Int) = save(all().drop(n))

    private fun save(writes: List<DeferredWrite>) {
        val array = JSONArray().apply { writes.forEach { put(encode(it)) } }
        check(prefs.edit().putString(KEY, array.toString()).commit()) { "deferred alarm write failed" }
    }

    private fun encode(w: DeferredWrite): JSONObject = JSONObject().put("id", w.id).apply {
        when (w) {
            is DeferredWrite.SetEnabled -> put("op", OP_ENABLED).put("enabled", w.enabled)
            is DeferredWrite.SetSkip -> {
                put("op", OP_SKIP)
                w.occurrence?.let { put("occurrence", it.toEpochMilli()) }
            }
        }
    }

    private fun decode(json: String): List<DeferredWrite> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val id = o.getString("id")
            when (val op = o.getString("op")) {
                OP_ENABLED -> DeferredWrite.SetEnabled(id, o.getBoolean("enabled"))
                OP_SKIP -> DeferredWrite.SetSkip(id, if (o.isNull("occurrence")) null else Instant.ofEpochMilli(o.getLong("occurrence")))
                else -> throw IllegalArgumentException("unknown op $op")
            }
        }
    }

    private companion object {
        const val TAG = "DeferredAlarmWrites"
        const val PREFS = "alarm_deferred_writes"
        const val KEY = "writes"
        const val OP_ENABLED = "setEnabled"
        const val OP_SKIP = "setSkip"
    }
}

/** In-memory queue for tests. */
class InMemoryDeferredAlarmWrites : DeferredAlarmWrites {
    private val writes = ArrayList<DeferredWrite>()

    @Synchronized override fun append(w: DeferredWrite) { writes += w }

    @Synchronized override fun all(): List<DeferredWrite> = writes.toList()

    @Synchronized override fun removeFirst(n: Int) = repeat(minOf(n, writes.size)) { writes.removeAt(0) }
}
