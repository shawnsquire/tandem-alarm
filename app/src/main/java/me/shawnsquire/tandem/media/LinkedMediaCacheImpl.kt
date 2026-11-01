package me.shawnsquire.tandem.media

import android.net.Uri
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.schedule.Alarm
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Outcome of [LinkedMediaCacheImpl.downloadMissing]: [retryable] is set when a failure may succeed later (network, I/O), not for a missing or tampered blob. */
data class DownloadResult(val allPresent: Boolean, val retryable: Boolean)

/**
 * The joiner's cache of the owner's media: the refs live in [storeFile] (replaced atomically, kept
 * outside the swept `linked/` directory) and the blobs in `filesDir/linked/{sha}`.
 *
 * Constructing it does no IO. Per I1 [resolve] never throws and a missing file just falls to the next step.
 */
class LinkedMediaCacheImpl(
    private val filesDir: File,
    private val storage: MediaStorage,
    private val enqueueDownload: () -> Unit,
    private val systemAlarmSound: String? = Settings.System.DEFAULT_ALARM_ALERT_URI?.toString(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Fire-and-forget report of (distinct referenced files, those not cached) after [update] and [downloadMissing]. */
    private val onStatus: (referenced: Int, missing: Int) -> Unit = { _, _ -> },
) : LinkedMediaCache {
    private val linkedDir = File(filesDir, LINKED_DIR)
    private val storeFile = File(filesDir, "linked-media.json")
    private val lock = Any()
    private var cached: Refs? = null

    override fun update(ownerUid: String, alarms: Map<String, AlarmMedia>, defaultWallpaper: MediaRef?) {
        val refs = Refs(
            ownerUid,
            alarms.mapValues { (_, m) -> AlarmMedia(m.wallpaper?.takeIf(::valid), m.ringtone?.takeIf(::valid)) },
            defaultWallpaper?.takeIf(::valid),
        )
        synchronized(lock) {
            cached = refs
            try {
                persist(refs)
            } catch (e: Exception) {
                Log.w(TAG, "saving linked media refs failed; keeping them in memory", e)
            }
        }
        if (refs.shas().any { pathFor(it) == null }) {
            try {
                enqueueDownload()
            } catch (e: Exception) {
                Log.w(TAG, "could not enqueue linked media download", e)
            }
        }
        report(refs)
    }

    private fun report(refs: Refs) {
        try {
            val shas = refs.shas()
            onStatus(shas.size, shas.count { pathFor(it) == null })
        } catch (e: Exception) {
            Log.w(TAG, "reporting linked media status failed", e)
        }
    }

    override fun pathFor(sha: String): String? {
        if (!SHA.matches(sha)) return null
        val f = File(linkedDir, sha)
        return if (f.isFile && f.canRead()) f.absolutePath else null
    }

    /** Throws if the store exists but cannot be read, so the orphan sweeper deletes nothing. */
    override fun inUse(): Set<String> = refs().shas()

    override suspend fun resolve(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: String?): RingMedia {
        val link = alarm.link ?: return DefaultLinkedMediaCache.resolve(alarm, joinerDefaultRingtone, joinerDefaultWallpaper)
        return withContext(io) {
            try {
                val refs = try { refs() } catch (e: Exception) { Log.w(TAG, "reading linked media refs failed", e); null }
                    ?.takeIf { it.ownerUid == link.ownerUid }
                val owner = refs?.alarms?.get(link.ownerAlarmId)

                val photos = listOfNotNull(
                    link.overrideWallpaperPath?.takeIf { readable(it) },
                    owner?.wallpaper?.let { pathFor(it.sha) },
                    refs?.defaultWallpaper?.let { pathFor(it.sha) },
                    joinerDefaultWallpaper,
                )
                val sounds = listOfNotNull(
                    link.overrideRingtoneUri?.takeIf(::soundUsable),
                    owner?.ringtone?.let { pathFor(it.sha) }?.let { Uri.fromFile(File(it)).toString() },
                    systemAlarmSound,
                    joinerDefaultRingtone,
                )
                RingMedia(
                    label = link.overrideLabel?.takeIf { it.isNotBlank() } ?: alarm.label,
                    soundCandidates = sounds.filter { it.isNotBlank() }.distinct(),
                    wallpaperPaths = photos.filter { it.isNotBlank() }.distinct(),
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "resolving linked media failed", e)
                DefaultLinkedMediaCache.resolve(alarm, joinerDefaultRingtone, joinerDefaultWallpaper)
            }
        }
    }

    /**
     * Downloads every referenced blob that is not cached, re-reading the refs until nothing new is
     * missing (an [update] during a run is not enqueued again).
     */
    suspend fun downloadMissing(hid: String): DownloadResult = withContext(io) {
        val tried = mutableSetOf<String>()
        var failed = false
        var retryable = false
        while (true) {
            val refs = refs()
            val missing = refs.shas().filter { it !in tried && pathFor(it) == null }
            if (missing.isEmpty()) break
            for (sha in missing) {
                tried += sha
                when (fetch(hid, refs.ownerUid, sha)) {
                    Fetch.OK -> Unit
                    Fetch.RETRY -> { failed = true; retryable = true }
                    Fetch.PERMANENT -> failed = true
                }
            }
        }
        report(refs())
        DownloadResult(allPresent = !failed, retryable = retryable)
    }

    private enum class Fetch { OK, RETRY, PERMANENT }

    private suspend fun fetch(hid: String, ownerUid: String, sha: String): Fetch {
        linkedDir.mkdirs()
        val tmp = File(linkedDir, "$sha.tmp")
        return try {
            storage.download(hid, ownerUid, sha, tmp, MAX_MEDIA_BYTES)
            if (sha256Hex(tmp) != sha) {
                Log.w(TAG, "downloaded $sha does not match its hash; discarding")
                Fetch.PERMANENT
            } else if (tmp.renameTo(File(linkedDir, sha))) {
                Fetch.OK
            } else {
                Log.w(TAG, "cannot move $sha into place")
                Fetch.RETRY
            }
        } catch (e: MediaNotFoundException) {
            Log.w(TAG, "$sha is not in Storage", e)
            Fetch.PERMANENT
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "downloading $sha failed", e)
            Fetch.RETRY
        } finally {
            tmp.delete()
        }
    }

    private fun refs(): Refs = synchronized(lock) {
        cached ?: load().also { cached = it }
    }

    private fun load(): Refs {
        if (!storeFile.exists()) return Refs("", emptyMap(), null)
        return decode(JSONObject(storeFile.readText()))
    }

    private fun persist(r: Refs) {
        filesDir.mkdirs()
        val tmp = File(filesDir, storeFile.name + ".tmp")
        tmp.writeText(encode(r).toString())
        check(tmp.renameTo(storeFile)) { "Cannot move $tmp into place" }
    }

    private fun encode(r: Refs): JSONObject = JSONObject().apply {
        put("ownerUid", r.ownerUid)
        r.defaultWallpaper?.let { put("defaultWallpaper", it.toJson()) }
        put("alarms", JSONObject().apply {
            r.alarms.forEach { (id, m) ->
                put(id, JSONObject().apply {
                    m.wallpaper?.let { put("wallpaper", it.toJson()) }
                    m.ringtone?.let { put("ringtone", it.toJson()) }
                })
            }
        })
    }

    private fun decode(o: JSONObject): Refs {
        val alarms = o.getJSONObject("alarms")
        return Refs(
            ownerUid = o.getString("ownerUid"),
            alarms = alarms.keys().asSequence().associateWith { id ->
                val m = alarms.getJSONObject(id)
                AlarmMedia(m.optJSONObject("wallpaper")?.let(::mediaRefFromJson)?.takeIf(::valid), m.optJSONObject("ringtone")?.let(::mediaRefFromJson)?.takeIf(::valid))
            },
            defaultWallpaper = o.optJSONObject("defaultWallpaper")?.let(::mediaRefFromJson)?.takeIf(::valid),
        )
    }

    /** Refs come from the partner's document: only a bare SHA-256 may become a file name. */
    private fun valid(r: MediaRef) = SHA.matches(r.sha)

    private fun readable(path: String) = File(path).let { it.isFile && it.canRead() }

    /** A content:// override can't be checked cheaply (the player falls through on failure); a file must exist. */
    private fun soundUsable(s: String): Boolean = when {
        s.isBlank() -> false
        s.startsWith("content:") -> true
        s.startsWith("file:") -> Uri.parse(s).path?.let(::readable) == true
        else -> readable(s)
    }

    private data class Refs(val ownerUid: String, val alarms: Map<String, AlarmMedia>, val defaultWallpaper: MediaRef?) {
        fun shas(): Set<String> = buildSet {
            alarms.values.forEach { m -> m.wallpaper?.let { add(it.sha) }; m.ringtone?.let { add(it.sha) } }
            defaultWallpaper?.let { add(it.sha) }
        }
    }

    internal companion object {
        /** Directory under filesDir holding the owner's cached media; also swept by [MediaOrphanSweeper]. */
        const val LINKED_DIR = "linked"
        private const val TAG = "LinkedMediaCache"
        private val SHA = Regex("^[0-9a-f]{64}$")
    }
}
