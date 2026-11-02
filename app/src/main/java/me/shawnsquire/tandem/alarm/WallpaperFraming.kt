package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.schedule.MAX_ZOOM
import me.shawnsquire.tandem.schedule.WallpaperFrame
import kotlin.math.max

/**
 * Where to draw an image on the screen: scale it by [scale] (image pixel to screen pixel), then put
 * its top-left corner at ([left], [top]) in screen pixels.
 */
data class FrameTransform(val scale: Float, val left: Float, val top: Float)

/** The scale at which the image just covers the screen: what `ContentScale.Crop` uses. */
private fun coverScale(imageW: Float, imageH: Float, screenW: Float, screenH: Float): Float =
    max(screenW / imageW, screenH / imageH)

/** Half the visible span along one axis, in normalised image units. */
private fun halfSpan(screen: Float, image: Float, scale: Float): Float = screen / (scale * image) / 2f

private fun clampFocus(focus: Float, half: Float): Float =
    if (half >= 0.5f) 0.5f else focus.coerceIn(half, 1f - half)

/**
 * [frame] made valid for an [imageW]x[imageH] image on a [screenW]x[screenH] screen: null is
 * [WallpaperFrame.CENTERED]; zoom is 1..[MAX_ZOOM]; the focus is pulled in until the visible region
 * lies inside the image, so the image always covers the screen (an axis whose whole span is
 * visible is centred, 0.5). Non-finite parts count as centred.
 */
fun clampFrame(frame: WallpaperFrame?, imageW: Float, imageH: Float, screenW: Float, screenH: Float): WallpaperFrame {
    val f = (frame ?: WallpaperFrame.CENTERED).sanitized()
    val scale = coverScale(imageW, imageH, screenW, screenH) * f.zoom
    return WallpaperFrame(
        zoom = f.zoom,
        focusX = clampFocus(f.focusX, halfSpan(screenW, imageW, scale)),
        focusY = clampFocus(f.focusY, halfSpan(screenH, imageH, scale)),
    )
}

/**
 * The scale and top-left translation that draw the image for [frame] (clamped first), so a
 * renderer needs one scale and one translate. A null frame is a centred cover crop.
 */
fun frameTransform(frame: WallpaperFrame?, imageW: Float, imageH: Float, screenW: Float, screenH: Float): FrameTransform {
    val f = clampFrame(frame, imageW, imageH, screenW, screenH)
    val scale = coverScale(imageW, imageH, screenW, screenH) * f.zoom
    return FrameTransform(
        scale = scale,
        left = screenW / 2f - f.focusX * imageW * scale,
        top = screenH / 2f - f.focusY * imageH * scale,
    )
}

/**
 * [frame] after a drag and pinch: the fingers moved by ([panX], [panY]) screen pixels, scaled by
 * [zoomChange] about ([centroidX], [centroidY]). The image point under the centroid follows it, so
 * dragging right moves the image right (the focus left) and a pinch keeps the point under the
 * fingers in place. Clamped as [clampFrame] does.
 */
fun applyGesture(
    frame: WallpaperFrame?,
    panX: Float, panY: Float, zoomChange: Float,
    centroidX: Float, centroidY: Float,
    imageW: Float, imageH: Float, screenW: Float, screenH: Float,
): WallpaperFrame {
    val f = clampFrame(frame, imageW, imageH, screenW, screenH)
    val cover = coverScale(imageW, imageH, screenW, screenH)
    val oldScale = cover * f.zoom
    val change = if (zoomChange.isFinite() && zoomChange > 0f) zoomChange else 1f
    val zoom = (f.zoom * change).coerceIn(1f, MAX_ZOOM)
    val newScale = cover * zoom
    val pointX = f.focusX + (centroidX - screenW / 2f) / (imageW * oldScale)
    val pointY = f.focusY + (centroidY - screenH / 2f) / (imageH * oldScale)
    return clampFrame(
        WallpaperFrame(
            zoom,
            pointX - (centroidX + panX - screenW / 2f) / (imageW * newScale),
            pointY - (centroidY + panY - screenH / 2f) / (imageH * newScale),
        ),
        imageW, imageH, screenW, screenH,
    )
}
