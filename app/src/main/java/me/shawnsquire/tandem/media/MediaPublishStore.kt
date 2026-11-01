package me.shawnsquire.tandem.media

import org.json.JSONArray
import org.json.JSONObject
import me.shawnsquire.tandem.sync.PublishedMedia
import java.io.File

/**
 * What the owner has published: the [published] refs and, per household id, the shas known to be in
 * Storage ([uploaded]) and those that may be there after an unfinished upload or delete ([pendingDelete]).
 * A pending sha is deleted once nothing references it and is never treated as uploaded.
 */
data class MediaPublishState(
    val published: PublishedMedia? = null,
    val uploaded: Map<String, Set<String>> = emptyMap(),
    val pendingDelete: Map<String, Set<String>> = emptyMap(),
)

/**
 * [MediaPublishState] as one JSON file, replaced atomically (temp + rename). Kept apart from the
 * wallpapers, ringtones and linked directories, which the orphan sweeper cleans.
 */
class MediaPublishStore(private val file: File) {
    /** Empty state if the file is missing or unreadable. */
    fun load(): MediaPublishState = try {
        if (!file.isFile) MediaPublishState() else decode(JSONObject(file.readText()))
    } catch (_: Exception) {
        MediaPublishState()
    }

    fun save(state: MediaPublishState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encode(state).toString())
        check(tmp.renameTo(file)) { "Cannot move $tmp into place" }
    }

    private fun encode(s: MediaPublishState): JSONObject = JSONObject().apply {
        s.published?.let { p ->
            put("published", JSONObject().apply {
                p.defaultWallpaper?.let { put("defaultWallpaper", it.toJson()) }
                put("alarms", JSONObject().apply {
                    p.alarms.forEach { (id, m) ->
                        put(id, JSONObject().apply {
                            m.wallpaper?.let { put("wallpaper", it.toJson()) }
                            m.ringtone?.let { put("ringtone", it.toJson()) }
                        })
                    }
                })
            })
        }
        put("uploaded", shaSets(s.uploaded))
        put("pendingDelete", shaSets(s.pendingDelete))
    }

    private fun shaSets(m: Map<String, Set<String>>) = JSONObject().apply { m.forEach { (hid, shas) -> put(hid, JSONArray(shas.toList())) } }

    private fun shaSets(o: JSONObject?): Map<String, Set<String>> = o?.keys()?.asSequence()?.associateWith { hid ->
        val a = o.getJSONArray(hid)
        (0 until a.length()).map(a::getString).toSet()
    }.orEmpty()

    private fun decode(o: JSONObject): MediaPublishState {
        val published = o.optJSONObject("published")?.let { p ->
            val alarms = p.optJSONObject("alarms")
            PublishedMedia(
                defaultWallpaper = p.optJSONObject("defaultWallpaper")?.let(::mediaRefFromJson),
                alarms = alarms?.keys()?.asSequence()?.associateWith { id ->
                    val m = alarms.getJSONObject(id)
                    AlarmMedia(m.optJSONObject("wallpaper")?.let(::mediaRefFromJson), m.optJSONObject("ringtone")?.let(::mediaRefFromJson))
                }.orEmpty(),
            )
        }
        // A missing pendingDelete (older file) is empty.
        return MediaPublishState(published, shaSets(o.optJSONObject("uploaded")), shaSets(o.optJSONObject("pendingDelete")))
    }

}
