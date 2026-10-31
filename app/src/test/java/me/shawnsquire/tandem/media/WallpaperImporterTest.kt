package me.shawnsquire.tandem.media

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WallpaperImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val uri = Uri.parse("content://x/1")
    private val calls = mutableListOf<Triple<Uri, Int, Int>>()

    private fun importer(decode: (Uri, Int, Int) -> Bitmap = { u, w, h ->
        calls += Triple(u, w, h)
        Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
    }) = WallpaperImporter(tmp.root, decode) { 1080 to 2400 }

    private val wallpapers get() = File(tmp.root, "wallpapers")

    @Test fun importWritesJpegUnderWallpapers() = runTest {
        val path = importer().import(uri)
        val f = File(path)
        assertTrue(path.endsWith(".jpg"))
        assertEquals(wallpapers.canonicalFile, f.canonicalFile.parentFile)
        assertTrue(f.exists() && f.length() > 0)
        assertEquals(listOf(f.name), wallpapers.list()!!.toList())
    }

    @Test fun twoImportsDiffer() = runTest {
        val i = importer()
        assertNotEquals(i.import(uri), i.import(uri))
    }

    @Test fun decodeGetsScreenSize() = runTest {
        importer().import(uri)
        assertEquals(Triple(uri, 1080, 2400), calls.single())
    }

    @Test fun failingDecodeLeavesNothing() = runTest {
        val i = importer { _, _, _ -> throw IllegalStateException("boom") }
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { i.import(uri) } }
        assertTrue(wallpapers.list().orEmpty().isEmpty())
    }

    @Test fun deleteRemovesImported() = runTest {
        val i = importer()
        val path = i.import(uri)
        assertTrue(i.delete(path))
        assertFalse(File(path).exists())
    }

    @Test fun deleteRefusesOutsideDirectory() = runTest {
        val i = importer()
        i.import(uri)
        val root = File(tmp.root, "keep.jpg").apply { writeText("x") }
        val sneaky = File(tmp.root, "x.jpg").apply { writeText("x") }
        assertFalse(i.delete(root.path))
        assertFalse(i.delete(wallpapers.path + "/../x.jpg"))
        assertTrue(root.exists())
        assertTrue(sneaky.exists())
    }

    @Test fun deleteMissingIsFalse() {
        assertFalse(importer().delete(File(wallpapers, "nope.jpg").path))
    }

    @Test fun targetSizeCoversScreenWithoutUpscale() {
        // 4000x3000 into 1080x2400: cover scale is 0.8 (3200x2400), then capped to 2x screen pixels
        assertEquals(2629 to 1971, targetSize(4000, 3000, 1080, 2400))
        // 2000x1000 into 1080x2400: cover scale = 1.0 (no upscale), 2M px under the cap
        assertEquals(2000 to 1000, targetSize(2000, 1000, 1080, 2400))
        // small image is never upscaled
        assertEquals(100 to 50, targetSize(100, 50, 1080, 2400))
        // exact fit
        assertEquals(1080 to 2400, targetSize(1080, 2400, 1080, 2400))
    }

    @Test fun targetSizeCapsPanorama() {
        val (w, h) = targetSize(12000, 3000, 1440, 3120)
        assertTrue(w.toLong() * h <= 2L * 1440 * 3120)
        assertEquals(4.0, w.toDouble() / h, 0.01)
        assertTrue(w > 5900 && h > 1400)
    }

    @Test fun importDeletesLeftoverTmpFiles() = runTest {
        wallpapers.mkdirs()
        val stale = File(wallpapers, "dead.tmp").apply { writeText("x") }
        val keep = File(wallpapers, "keep.jpg").apply { writeText("x") }
        importer().import(uri)
        assertFalse(stale.exists())
        assertTrue(keep.exists())
    }
}
