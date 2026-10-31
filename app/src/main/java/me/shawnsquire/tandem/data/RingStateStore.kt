package me.shawnsquire.tandem.data

import kotlinx.coroutines.flow.Flow
import me.shawnsquire.tandem.schedule.RingState

/** Persisted ring state; null when nothing is ringing. Its ringId pins stop-ringing commands (I3). */
interface RingStateStore {
    val state: Flow<RingState?>
    suspend fun set(state: RingState?)
}
