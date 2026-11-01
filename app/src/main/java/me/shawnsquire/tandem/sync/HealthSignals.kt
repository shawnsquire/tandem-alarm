package me.shawnsquire.tandem.sync

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Outcome of the most recent Cloud Storage call and when it finished. */
data class StorageStatus(val ok: Boolean, val at: Instant)

/** How many distinct linked-media files the partner's alarms reference, and how many are not cached yet. */
data class LinkedMediaStatus(val referenced: Int, val missing: Int)

/** Diagnostics about push delivery and linked-media health. */
interface HealthSignals {
    val lastFcmAt: Flow<Instant?>
    val fcmTokenPresent: Flow<Boolean>
    /** Null until a Storage call has been recorded. */
    val storageStatus: Flow<StorageStatus?>
    /** Null until the joiner's media cache has reported once. */
    val linkedMedia: Flow<LinkedMediaStatus?>
}
