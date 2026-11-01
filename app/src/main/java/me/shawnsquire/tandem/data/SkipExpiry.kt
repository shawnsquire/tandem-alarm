package me.shawnsquire.tandem.data

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.FIRE_GRACE
import java.time.Instant
import kotlin.time.toJavaDuration

// One expiry rule for the Room repository (its cleanup and its reads) and the locked mirror reads.

/** A skip at or before this instant is expired; later ones stay for [FIRE_GRACE] past firing. */
internal fun skipExpiryCutoff(now: Instant): Instant = now.minus(FIRE_GRACE.toJavaDuration())

/**
 * An expired skip reads as null. Covers all three stored skips: the row's own
 * `skippedOccurrenceAt`, the owner's `soloSkippedOccurrenceAt` and `link.ownerSkippedOccurrenceAt`.
 */
internal fun Alarm.withoutExpiredSkip(now: Instant): Alarm {
    val cutoff = skipExpiryCutoff(now)
    fun Instant?.expired() = this != null && !isAfter(cutoff)
    val ownerExpired = link?.ownerSkippedOccurrenceAt.expired()
    if (!skippedOccurrenceAt.expired() && !soloSkippedOccurrenceAt.expired() && !ownerExpired) return this
    return copy(
        skippedOccurrenceAt = skippedOccurrenceAt.takeUnless { it.expired() },
        soloSkippedOccurrenceAt = soloSkippedOccurrenceAt.takeUnless { it.expired() },
        link = if (ownerExpired) link?.copy(ownerSkippedOccurrenceAt = null) else link,
    )
}
