package me.shawnsquire.tandem.alarm

/** The ringIds of sessions alive in this process. A RingState whose ringId is not here is a leftover. */
interface RingRegistry {
    fun register(ringId: String)
    fun unregister(ringId: String)
    fun isLive(ringId: String): Boolean
}

/** Thread-safe set; one instance per test, [LiveRings] for the app. */
class SyncRingRegistry : RingRegistry {
    private val ids = mutableSetOf<String>()
    @Synchronized override fun register(ringId: String) { ids += ringId }
    @Synchronized override fun unregister(ringId: String) { ids -= ringId }
    @Synchronized override fun isLive(ringId: String): Boolean = ringId in ids
}

/** Process-wide registry used by [RingingService]. */
object LiveRings : RingRegistry by SyncRingRegistry()
