package me.shawnsquire.tandem.sync

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** System clock that reads the device time zone on every call, so a zone change applies without a restart. */
object LiveZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()
}
