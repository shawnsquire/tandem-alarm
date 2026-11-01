package me.shawnsquire.tandem.media

import org.json.JSONArray
import org.json.JSONObject
import me.shawnsquire.tandem.sync.PublishedMedia
import java.io.File

/** What the owner has published: the [published] refs and, per household id, the shas known to be in Storage. */
data class MediaPublishState(
    val published: PublishedMedia? = null,
    val uploaded: Map<String, Set<String>> = emptyMap(),
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
                p.defaultWallpaper?.let { put("defaultWallpaper", encode(it)) }
                put("alarms", JSONObject().apply {
                    p.alarms.forEach { (id, m) ->
                        put(id, JSONObject().apply {
                            m.wallpaper?.let { put("wallpaper", encode(it)) }
                            m.ringtone?.let { put("ringtone", encode(it)) }
                        })
                    }
                })
            })
        }
        put("uploaded", JSONObject().apply { s.uploaded.forEach { (hid, shas) -> put(hid, JSONArray(shas.toList())) } })
    }

    private fun encode(r: MediaRef) = JSONObject().put("sha", r.sha).put("size", r.size).apply { r.title?.let { put("title", it) } }

    private fun decode(o: JSONObject): MediaPublishState {
        val published = o.optJSONObject("published")?.let { p ->
            val alarms = p.optJSONObject("alarms")
            PublishedMedia(
                defaultWallpaper = p.optJSONObject("defaultWallpaper")?.let(::ref),
                alarms = alarms?.keys()?.asSequence()?.associateWith { id ->
                    val m = alarms.getJSONObject(id)
                    AlarmMedia(m.optJSONObject("wallpaper")?.let(::ref), m.optJSONObject("ringtone")?.let(::ref))
                }.orEmpty(),
            )
        }
        val up = o.optJSONObject("uploaded")
        val uploaded = up?.keys()?.asSequence()?.associateWith { hid ->
            val a = up.getJSONArray(hid)
            (0 until a.length()).map(a::getString).toSet()
        }.orEmpty()
        return MediaPublishState(published, uploaded)
    }

    private fun ref(o: JSONObject) = MediaRef(o.getString("sha"), o.getLong("size"), if (o.has("title")) o.getString("title") else null)
}
