package me.shawnsquire.tandem.alarm

import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class AlarmTimeLineTest {
    private val fmt = DateTimeFormatter.ofPattern("H:mm").withZone(ZoneOffset.UTC)
    private fun format(i: Instant) = fmt.format(i)
    private fun at(h: Int, m: Int) = Instant.parse("2026-03-02T00:00:00Z").plusSeconds(h * 3600L + m * 60L)

    private fun ring(primary: Instant?, vararg merged: Instant, snoozes: Int = 0) =
        RingState("a", "R", Instant.EPOCH, null, primary, merged.mapIndexed { i, t -> RingMember("m$i", t) }, snoozes)

    @Test fun freshRing() = assertEquals("6:30 alarm", alarmTimeLine(ring(at(6, 30)), ::format))

    @Test fun snoozedRing() {
        assertEquals("6:30 alarm · snoozed 2×", alarmTimeLine(ring(at(6, 30), snoozes = 2), ::format))
        assertEquals("6:30 alarm · snoozed 1×", alarmTimeLine(ring(at(6, 30), snoozes = 1), ::format))
    }

    @Test fun mergedTwo() = assertEquals("6:30 and 6:45 alarms", alarmTimeLine(ring(at(6, 30), at(6, 45)), ::format))

    @Test fun mergedThreeAndSnoozed() =
        assertEquals("6:30, 6:45 and 7:00 alarms · snoozed 3×", alarmTimeLine(ring(at(6, 30), at(6, 45), at(7, 0), snoozes = 3), ::format))

    @Test fun sameMinuteMergeReadsOnce() {
        assertEquals("6:30 alarm", alarmTimeLine(ring(at(6, 30), at(6, 30)), ::format))
        assertEquals("6:30 and 6:45 alarms", alarmTimeLine(ring(at(6, 30), at(6, 45), at(6, 30)), ::format))
    }

    @Test fun dedupesOnTheFormattedTextNotTheInstant() =
        assertEquals("6:30 alarm", alarmTimeLine(ring(at(6, 30), at(6, 30).plusSeconds(20)), ::format))

    @Test fun keepsRingOrderNotChronologicalOrder() =
        assertEquals("7:00 and 6:30 alarms", alarmTimeLine(ring(at(7, 0), at(6, 30)), ::format))

    @Test fun noOccurrenceLeavesTheLineOut() {
        assertNull(alarmTimeLine(ring(null), ::format))
        assertNull(alarmTimeLine(ring(null, snoozes = 2), ::format))
    }

    @Test fun unknownPrimaryStillListsTheMergedMembers() =
        assertEquals("6:45 and 7:00 alarms", alarmTimeLine(ring(null, at(6, 45), at(7, 0)), ::format))

    @Test fun usesTheGivenFormatter() =
        assertEquals("<06:30> alarm", alarmTimeLine(ring(at(6, 30)), { "<${DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(it)}>" }))
}
