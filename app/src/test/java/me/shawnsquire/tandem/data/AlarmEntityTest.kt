package me.shawnsquire.tandem.data

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.MAX_ZOOM
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.WallpaperFrame
import me.shawnsquire.tandem.schedule.effectiveTextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek

class AlarmEntityTest {
    private val joined = Alarm(
        "j", "x", 6, 30, setOf(DayOfWeek.MONDAY), true, null, null, null,
        link = AlarmLink("u", "o", LinkMode.RING, null, true, null, null, null, null),
    ).toEntity()

    @Test fun unknownPatternNamesReadAsPulseForTheAlarmAndNullForTheOverride() {
        val alarm = joined.copy(vibration = "FROM_THE_FUTURE", linkOverrideVibration = "FROM_THE_FUTURE").toAlarm()
        assertEquals(VibrationPattern.PULSE, alarm.vibration)
        assertNull(alarm.link!!.overrideVibration)
    }

    @Test fun knownOverrideNamesAreKept() {
        val alarm = joined.copy(linkOverrideVibration = "RAPID").toAlarm()
        assertEquals(VibrationPattern.RAPID, alarm.link!!.overrideVibration)
    }

    @Test fun wallpaperFramesAndTextPositionsRoundTrip() {
        val link = joined.toAlarm().link!!.copy(
            overrideWallpaperFrame = WallpaperFrame(3f, 0.1f, 0.9f),
            overrideTextPosition = TextPosition.TOP,
        )
        val alarm = joined.toAlarm().copy(
            wallpaperFrame = WallpaperFrame(2f, 0.25f, 0.75f),
            textPosition = TextPosition.BOTTOM,
            link = link,
        )
        assertEquals(alarm, alarm.toEntity().toAlarm())
    }

    @Test fun noFrameAndNoOverridesReadAsNullAndMiddle() {
        val alarm = joined.toAlarm()
        assertNull(alarm.wallpaperFrame)
        assertEquals(TextPosition.MIDDLE, alarm.textPosition)
        assertNull(alarm.link!!.overrideWallpaperFrame)
        assertNull(alarm.link!!.overrideTextPosition)
    }

    @Test fun aFrameNeedsAllThreePartsAndIsSanitizedOnRead() {
        assertNull(joined.copy(wallpaperZoom = 2f, wallpaperFocusX = 0.5f).toAlarm().wallpaperFrame)
        assertNull(joined.copy(linkOverrideWallpaperZoom = 2f, linkOverrideWallpaperFocusY = 0.5f).toAlarm().link!!.overrideWallpaperFrame)
        val read = joined.copy(wallpaperZoom = 9f, wallpaperFocusX = -1f, wallpaperFocusY = Float.NaN).toAlarm().wallpaperFrame
        assertEquals(WallpaperFrame(MAX_ZOOM, 0f, 0.5f), read)
    }

    @Test fun unknownPositionNamesReadAsMiddleForTheAlarmAndNullForTheOverride() {
        val alarm = joined.copy(textPosition = "FROM_THE_FUTURE", linkOverrideTextPosition = "FROM_THE_FUTURE").toAlarm()
        assertEquals(TextPosition.MIDDLE, alarm.textPosition)
        assertNull(alarm.link!!.overrideTextPosition)
    }

    @Test fun effectiveTextPositionPrefersTheOverride() {
        val alarm = joined.toAlarm().copy(textPosition = TextPosition.BOTTOM)
        assertEquals(TextPosition.BOTTOM, alarm.effectiveTextPosition())
        assertEquals(TextPosition.TOP, alarm.copy(link = alarm.link!!.copy(overrideTextPosition = TextPosition.TOP)).effectiveTextPosition())
        assertEquals(TextPosition.BOTTOM, alarm.copy(link = null).effectiveTextPosition())
    }

    @Test fun sanitizedKeepsAValidFrameAndRepairsABadOne() {
        val ok = WallpaperFrame(1.5f, 0.2f, 0.8f)
        assertEquals(ok, ok.sanitized())
        assertEquals(WallpaperFrame.CENTERED, WallpaperFrame(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).sanitized())
        assertEquals(WallpaperFrame(1f, 1f, 0f), WallpaperFrame(0.2f, 5f, -5f).sanitized())
    }
}
