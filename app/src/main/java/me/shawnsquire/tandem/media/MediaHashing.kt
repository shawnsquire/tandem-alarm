package me.shawnsquire.tandem.media

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Lowercase hex SHA-256 of [file], streamed. */
internal fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

internal fun MediaRef.toJson(): JSONObject = JSONObject().put("sha", sha).put("size", size).apply { title?.let { put("title", it) } }

internal fun mediaRefFromJson(o: JSONObject): MediaRef =
    MediaRef(o.getString("sha"), o.getLong("size"), if (o.has("title")) o.getString("title") else null)
