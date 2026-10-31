package me.shawnsquire.tandem.data

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.FIRE_GRACE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.time.toJavaDuration

/** The one expiry rule Room reads and the locked mirror reads share. */
class SkipExpiryTest {
    private val now = Instant.parse("2026-03-02T12:00:00Z")
    private val cutoff = now.minus(FIRE_GRACE.toJavaDuration())

    private fun skipped(at: Instant?) = Alarm("a", "a", 6, 30, emptySet(), true, at, null, null)

    @Test
    fun aSkipAtOrBeforeTheCutoffReadsAsNull() {
        assertNull(skipped(cutoff).withoutExpiredSkip(now).skippedOccurrenceAt)
        assertNull(skipped(cutoff.minusSeconds(1)).withoutExpiredSkip(now).skippedOccurrenceAt)
    }

    @Test
    fun aLaterSkipIsKept() {
        val live = cutoff.plusMillis(1)
        assertEquals(skipped(live), skipped(live).withoutExpiredSkip(now))
        assertEquals(skipped(null), skipped(null).withoutExpiredSkip(now))
    }
}
