package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.media.RingWallpaper
import me.shawnsquire.tandem.schedule.WallpaperFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstDecodedTest {
    private val frameA = WallpaperFrame(2f, 0.3f, 0.4f)
    private val frameB = WallpaperFrame(1.5f, 0.6f, 0.5f)
    private val candidates = listOf(RingWallpaper("/a", frameA), RingWallpaper("/b", frameB), RingWallpaper("/c", null))

    @Test fun `pairs the first readable candidate with its own frame`() {
        val result = firstDecoded(candidates) { if (it == "/a") null else "bitmap$it" }
        assertEquals("bitmap/b" to frameB, result)
    }

    @Test fun `a candidate without a frame stays unframed`() {
        assertEquals("x" to null, firstDecoded(candidates.takeLast(1)) { "x" })
    }

    @Test fun `none readable is null`() {
        assertNull(firstDecoded(candidates) { null as String? })
    }
}
