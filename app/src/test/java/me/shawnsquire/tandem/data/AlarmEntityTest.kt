package me.shawnsquire.tandem.data

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.VibrationPattern
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
}
