package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.schedule.MAX_ZOOM
import me.shawnsquire.tandem.schedule.WallpaperFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.random.Random

class WallpaperFramingTest {
    private val eps = 1e-3f

    private fun clamp(f: WallpaperFrame?, iw: Float, ih: Float, sw: Float = 1080f, sh: Float = 2400f) = clampFrame(f, iw, ih, sw, sh)

    private fun finite(f: WallpaperFrame) = f.zoom.isFinite() && f.focusX.isFinite() && f.focusY.isFinite()

    // clamp

    @Test fun nullIsCentred() = assertEquals(WallpaperFrame.CENTERED, clamp(null, 4000f, 3000f))

    @Test fun zoomIsCoercedToOneToMax() {
        assertEquals(1f, clamp(WallpaperFrame(0.2f, 0.5f, 0.5f), 4000f, 3000f).zoom, 0f)
        assertEquals(1f, clamp(WallpaperFrame(-5f, 0.5f, 0.5f), 4000f, 3000f).zoom, 0f)
        assertEquals(MAX_ZOOM, clamp(WallpaperFrame(99f, 0.5f, 0.5f), 4000f, 3000f).zoom, 0f)
        assertEquals(2.5f, clamp(WallpaperFrame(2.5f, 0.5f, 0.5f), 4000f, 3000f).zoom, 0f)
    }

    @Test fun focusAtZoomOneOnAWideImagePansOnlyAlongX() {
        // 4000x1000 on 1080x2400: cover = 2.4, the image is 9600 wide, the screen shows 1080 of it.
        val half = 1080f / 9600f / 2f
        val lo = clamp(WallpaperFrame(1f, 0f, 0f), 4000f, 1000f)
        val hi = clamp(WallpaperFrame(1f, 1f, 1f), 4000f, 1000f)
        assertEquals(half, lo.focusX, eps)
        assertEquals(1f - half, hi.focusX, eps)
        assertEquals(0.5f, lo.focusY, 0f) // the whole height is visible
        assertEquals(0.5f, hi.focusY, 0f)
    }

    @Test fun focusAtZoomOneOnATallImagePansOnlyAlongY() {
        // 1000x4000 on 1080x2400: cover = 1.08, the image is 4320 tall, the screen shows 2400 of it.
        val half = 2400f / 4320f / 2f
        val lo = clamp(WallpaperFrame(1f, 0f, 0f), 1000f, 4000f)
        val hi = clamp(WallpaperFrame(1f, 1f, 1f), 1000f, 4000f)
        assertEquals(half, lo.focusY, eps)
        assertEquals(1f - half, hi.focusY, eps)
        assertEquals(0.5f, lo.focusX, 0f)
        assertEquals(0.5f, hi.focusX, 0f)
    }

    @Test fun anInRangeFocusIsKept() {
        val f = WallpaperFrame(3f, 0.4f, 0.55f)
        assertEquals(f, clamp(f, 4000f, 3000f))
    }

    @Test fun nanAndInfinityGiveFiniteFrames() {
        val bad = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (a in bad) for (b in bad) {
            assertTrue(finite(clamp(WallpaperFrame(a, b, a), 4000f, 3000f)))
            assertTrue(finite(clamp(WallpaperFrame(2f, a, b), 1000f, 4000f)))
        }
        val t = frameTransform(WallpaperFrame(Float.NaN, Float.NaN, Float.NaN), 4000f, 3000f, 1080f, 2400f)
        assertTrue(t.scale.isFinite() && t.left.isFinite() && t.top.isFinite())
    }

    // transform

    @Test fun nullFrameIsACentredCoverCrop() {
        for ((iw, ih) in listOf(4000f to 3000f, 1000f to 4000f, 1080f to 2400f, 300f to 300f)) {
            val t = frameTransform(null, iw, ih, 1080f, 2400f)
            val cover = max(1080f / iw, 2400f / ih)
            assertEquals(cover, t.scale, 1e-5f)
            assertEquals((1080f - iw * cover) / 2f, t.left, eps) // what ContentScale.Crop + Alignment.Center does
            assertEquals((2400f - ih * cover) / 2f, t.top, eps)
            assertEquals(t, frameTransform(WallpaperFrame.CENTERED, iw, ih, 1080f, 2400f))
        }
    }

    @Test fun theImageAlwaysCoversTheScreenForRandomFrames() {
        val rnd = Random(7)
        repeat(2000) {
            val iw = rnd.nextInt(200, 6000).toFloat()
            val ih = rnd.nextInt(200, 6000).toFloat()
            val sw = rnd.nextInt(300, 1500).toFloat()
            val sh = rnd.nextInt(600, 3000).toFloat()
            val f = WallpaperFrame(rnd.nextFloat() * 6f - 1f, rnd.nextFloat() * 3f - 1f, rnd.nextFloat() * 3f - 1f)
            val t = frameTransform(f, iw, ih, sw, sh)
            assertTrue(t.left <= 0.01f)
            assertTrue(t.top <= 0.01f)
            assertTrue("right edge ${t.left + iw * t.scale} < $sw", t.left + iw * t.scale >= sw - 0.01f)
            assertTrue("bottom edge ${t.top + ih * t.scale} < $sh", t.top + ih * t.scale >= sh - 0.01f)
        }
    }

    @Test fun theFocusIsTheImagePointAtTheScreenCentre() {
        val f = WallpaperFrame(2f, 0.4f, 0.6f)
        val t = frameTransform(f, 4000f, 3000f, 1080f, 2400f)
        assertEquals(0.4f, (1080f / 2f - t.left) / t.scale / 4000f, 1e-4f)
        assertEquals(0.6f, (2400f / 2f - t.top) / t.scale / 3000f, 1e-4f)
    }

    @Test fun aFrameShowsTheSameCentrePointOnScreensOfOtherAspectRatios() {
        val f = WallpaperFrame(2f, 0.4f, 0.55f)
        val (iw, ih) = 4000f to 3000f
        for ((sw, sh) in listOf(1080f to 2400f, 1080f to 1920f)) {
            val t = frameTransform(f, iw, ih, sw, sh)
            assertEquals(0.4f, (sw / 2f - t.left) / t.scale / iw, 1e-4f)
            assertEquals(0.55f, (sh / 2f - t.top) / t.scale / ih, 1e-4f)
            assertTrue(t.left <= 0.01f && t.top <= 0.01f)
            assertTrue(t.left + iw * t.scale >= sw - 0.01f && t.top + ih * t.scale >= sh - 0.01f)
            assertEquals(f, clampFrame(f, iw, ih, sw, sh))
        }
    }

    // gesture

    private fun gesture(f: WallpaperFrame?, panX: Float = 0f, panY: Float = 0f, zoom: Float = 1f, cx: Float = 540f, cy: Float = 1200f) =
        applyGesture(f, panX, panY, zoom, cx, cy, 4000f, 3000f, 1080f, 2400f)

    @Test fun draggingRightMovesTheImageRightSoTheFocusMovesLeft() {
        val start = WallpaperFrame(2f, 0.5f, 0.5f)
        val r = gesture(start, panX = 100f)
        assertTrue(r.focusX < start.focusX)
        assertEquals(start.focusY, r.focusY, 1e-6f)
        val before = frameTransform(start, 4000f, 3000f, 1080f, 2400f)
        val after = frameTransform(r, 4000f, 3000f, 1080f, 2400f)
        assertEquals(100f, after.left - before.left, 0.01f) // the image followed the finger exactly
        assertEquals(before.top, after.top, 0.01f)
    }

    @Test fun draggingDownMovesTheImageDown() {
        val start = WallpaperFrame(2f, 0.5f, 0.5f)
        val r = gesture(start, panY = 50f)
        val before = frameTransform(start, 4000f, 3000f, 1080f, 2400f)
        val after = frameTransform(r, 4000f, 3000f, 1080f, 2400f)
        assertEquals(50f, after.top - before.top, 0.01f)
        assertTrue(r.focusY < start.focusY)
    }

    @Test fun pinchKeepsTheImagePointUnderTheCentroid() {
        val start = WallpaperFrame(1.5f, 0.5f, 0.5f)
        val (cx, cy) = 800f to 700f
        fun pointUnder(f: WallpaperFrame): Pair<Float, Float> {
            val t = frameTransform(f, 4000f, 3000f, 1080f, 2400f)
            return ((cx - t.left) / t.scale / 4000f) to ((cy - t.top) / t.scale / 3000f)
        }
        val r = gesture(start, zoom = 1.6f, cx = cx, cy = cy)
        assertEquals(2.4f, r.zoom, 1e-5f)
        assertEquals(pointUnder(start).first, pointUnder(r).first, 1e-4f)
        assertEquals(pointUnder(start).second, pointUnder(r).second, 1e-4f)
    }

    @Test fun pinchAtTheCentreKeepsTheFocus() {
        val r = gesture(WallpaperFrame(1.5f, 0.45f, 0.5f), zoom = 2f)
        assertEquals(0.45f, r.focusX, 1e-5f)
        assertEquals(0.5f, r.focusY, 1e-5f)
    }

    @Test fun zoomIsClampedAndTheResultIsClamped() {
        assertEquals(MAX_ZOOM, gesture(WallpaperFrame(3f, 0.5f, 0.5f), zoom = 10f).zoom, 0f)
        assertEquals(1f, gesture(WallpaperFrame(1.2f, 0.5f, 0.5f), zoom = 0.1f).zoom, 0f)
        val far = gesture(WallpaperFrame(2f, 0.5f, 0.5f), panX = 100_000f, panY = -100_000f)
        assertEquals(far, clamp(far, 4000f, 3000f))
        val t = frameTransform(far, 4000f, 3000f, 1080f, 2400f)
        assertTrue(t.left <= 0.01f && t.top <= 0.01f)
        assertTrue(t.left + 4000f * t.scale >= 1080f - 0.01f && t.top + 3000f * t.scale >= 2400f - 0.01f)
    }

    @Test fun anInvalidZoomChangeIsIgnored() {
        val start = WallpaperFrame(2f, 0.5f, 0.5f)
        for (z in listOf(Float.NaN, 0f, -1f, Float.POSITIVE_INFINITY)) assertEquals(start, gesture(start, zoom = z))
    }

    @Test fun aNullFrameStartsFromCentred() {
        assertFalse(gesture(null, panX = 10f, zoom = 2f).let { it == WallpaperFrame.CENTERED })
        assertEquals(WallpaperFrame.CENTERED, gesture(null))
    }
}
