package me.shawnsquire.tandem.sync

import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveZoneClockTest {
    @Test fun `zone follows the default zone on every call`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(ZoneId.of("Asia/Tokyo"), LiveZoneClock.zone)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))
            assertEquals(ZoneId.of("America/Denver"), LiveZoneClock.zone)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
