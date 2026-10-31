package me.shawnsquire.tandem.alarm

/** Controls the active ring. Dismiss is pinned to [ringId] (I3). */
interface RingingController {
    suspend fun dismiss(ringId: String)
    suspend fun snooze()
}
