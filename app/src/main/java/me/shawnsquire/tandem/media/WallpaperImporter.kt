package me.shawnsquire.tandem.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Size to decode an image of [w]x[h] so it covers the screen, never upscaling. */
internal fun targetSize(w: Int, h: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
    var scale = min(1.0, max(screenW.toDouble() / w, screenH.toDouble() / h))
    // Cap the pixel count so a panorama can't decode to a bitmap too large to draw.
    val maxPixels = 2.0 * screenW * screenH
    val scaled = w * scale * h * scale
    if (scaled > maxPixels) {
        scale *= sqrt(maxPixels / scaled)
        // Truncate so rounding can't push the product back over the cap.
        return max(1, (w * scale).toInt()) to max(1, (h * scale).toInt())
    }
    return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
}

class WallpaperImporter(
    private val filesDir: File,
    private val decode: (Uri, targetW: Int, targetH: Int) -> Bitmap,
    private val screenSize: () -> Pair<Int, Int>,
) {
    constructor(context: Context) : this(
        filesDir = context.filesDir,
        decode = { uri, screenW, screenH ->
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val (w, h) = targetSize(info.size.width, info.size.height, screenW, screenH)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        },
        screenSize = {
            val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            b.width() to b.height()
        },
    )

    private val dir get() = File(filesDir, "wallpapers")

    /** Decodes [uri] (downscaled to the screen), saves a JPEG, and returns its absolute path. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val dir = dir
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create $dir" }
        // Leftovers from a process death mid-import; a young one may belong to a concurrent import.
        val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
        dir.listFiles { f -> f.extension == "tmp" && f.lastModified() < cutoff }?.forEach { it.delete() }
        val name = UUID.randomUUID().toString()
        val tmp = File(dir, "$name.tmp")
        val out = File(dir, "$name.jpg")
        try {
            val (w, h) = screenSize()
            val bitmap = decode(uri, w, h)
            try {
                tmp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "JPEG encode failed" } }
            } finally {
                bitmap.recycle()
            }
            check(tmp.renameTo(out)) { "Cannot move $tmp into place" }
            out.absolutePath
        } catch (t: Throwable) {
            tmp.delete()
            out.delete()
            throw t
        }
    }

    /** Deletes an imported wallpaper. Refuses anything outside the wallpapers directory. */
    fun delete(path: String): Boolean {
        val root = dir.canonicalFile
        val file = File(path).canonicalFile
        if (file.parentFile != root) return false
        return file.isFile && file.delete()
    }
}
