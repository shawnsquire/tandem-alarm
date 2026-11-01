package me.shawnsquire.tandem.sync

import kotlinx.coroutines.flow.Flow

/** Owner-side media upload. Real implementation: L5. */
interface MediaPublisher {
    /** Owner: uploads media for alarms with a link or open invite, plus the default wallpaper; deletes own unreferenced hashes. */
    fun start()

    /**
     * Refs for the device doc; StatePublisher combines this. Must emit the persisted refs right away
     * on collection, not null until the first upload pass finishes: `alarms[]` is replaced whole on
     * every device-doc write, so a null here would drop `alarms[].media` from the doc. Null only
     * when nothing has ever been published.
     */
    val published: Flow<PublishedMedia?>
}
