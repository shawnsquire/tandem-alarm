package me.shawnsquire.tandem.sync

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Diagnostics about push delivery health. */
interface HealthSignals {
    val lastFcmAt: Flow<Instant?>
    val fcmTokenPresent: Flow<Boolean>
}
