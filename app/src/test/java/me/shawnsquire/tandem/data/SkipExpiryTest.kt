package me.shawnsquire.tandem.data

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.FIRE_GRACE
import me.shawnsquire.tandem.schedule.LinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
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

    private fun solo(at: Instant?) = skipped(null).copy(soloSkippedOccurrenceAt = at)

    private fun ownerSkip(at: Instant?) = skipped(null).copy(
        link = AlarmLink("u", "o", LinkMode.RING, at, true, null, null, null, null),
    )

    @Test
    fun anExpiredSoloSkipReadsAsNull() {
        assertNull(solo(cutoff).withoutExpiredSkip(now).soloSkippedOccurrenceAt)
        assertNull(solo(cutoff.minusSeconds(1)).withoutExpiredSkip(now).soloSkippedOccurrenceAt)
    }

    @Test
    fun aLaterSoloSkipIsKept() {
        val live = cutoff.plusMillis(1)
        assertEquals(solo(live), solo(live).withoutExpiredSkip(now))
    }

    @Test
    fun anExpiredOwnerSkipReadsAsNull() {
        assertNull(ownerSkip(cutoff).withoutExpiredSkip(now).link!!.ownerSkippedOccurrenceAt)
        assertNull(ownerSkip(cutoff.minusSeconds(1)).withoutExpiredSkip(now).link!!.ownerSkippedOccurrenceAt)
    }

    @Test
    fun aLaterOwnerSkipIsKept() {
        val live = cutoff.plusMillis(1)
        assertEquals(ownerSkip(live), ownerSkip(live).withoutExpiredSkip(now))
    }

    @Test
    fun eachSkipExpiresIndependently() {
        val live = cutoff.plusMillis(1)
        val both = skipped(cutoff).copy(
            soloSkippedOccurrenceAt = live,
            link = ownerSkip(cutoff).link,
        )
        val result = both.withoutExpiredSkip(now)
        assertNull(result.skippedOccurrenceAt)
        assertNull(result.link!!.ownerSkippedOccurrenceAt)
        assertEquals(live, result.soloSkippedOccurrenceAt)
    }

    private fun ownOneShot(solo: Instant?) = solo(solo).copy(days = emptySet())

    @Test
    fun ownOneShotWithLiveSoloSkipStaysEnabledAndKeepsSkip() {
        val live = cutoff.plusMillis(1)
        assertEquals(ownOneShot(live), ownOneShot(live).withoutExpiredSkip(now))
    }

    @Test
    fun ownOneShotWithExpiredSoloSkipTurnsOff() {
        for (at in listOf(cutoff, cutoff.minusSeconds(1))) {
            val result = ownOneShot(at).withoutExpiredSkip(now)
            assertFalse(result.enabled)
            assertNull(result.soloSkippedOccurrenceAt)
        }
    }

    @Test
    fun repeatingOwnAlarmWithExpiredSoloSkipStaysEnabled() {
        val result = solo(cutoff).copy(days = setOf(DayOfWeek.MONDAY)).withoutExpiredSkip(now)
        assertNull(result.soloSkippedOccurrenceAt)
        assertTrue(result.enabled)
    }

    @Test
    fun linkedOneShotWithExpiredSoloSkipKeepsEnabled() {
        val linked = ownerSkip(null).copy(days = emptySet(), soloSkippedOccurrenceAt = cutoff)
        val result = linked.withoutExpiredSkip(now)
        assertNull(result.soloSkippedOccurrenceAt)
        assertTrue(result.enabled)
    }
}
