package me.shawnsquire.tandem.data

import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.FIRE_GRACE
import java.time.Instant
import kotlin.time.toJavaDuration

// One expiry rule for the Room repository (its cleanup and its reads) and the locked mirror reads.

/** A skip at or before this instant is expired; later ones stay for [FIRE_GRACE] past firing. */
internal fun skipExpiryCutoff(now: Instant): Instant = now.minus(FIRE_GRACE.toJavaDuration())

/** An expired skip reads as null. */
internal fun Alarm.withoutExpiredSkip(now: Instant): Alarm =
    if (skippedOccurrenceAt != null && !skippedOccurrenceAt.isAfter(skipExpiryCutoff(now))) copy(skippedOccurrenceAt = null) else this
