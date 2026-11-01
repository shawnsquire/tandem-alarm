package me.shawnsquire.tandem.sync

import kotlinx.coroutines.flow.Flow

/** Owner-side media upload. Real implementation: L5. */
interface MediaPublisher {
    /** Owner: uploads media for alarms with a link or open invite, plus the default wallpaper; deletes own unreferenced hashes. */
    fun start()

    /** Refs for the device doc; StatePublisher combines this. Null until the first pass. */
    val published: Flow<PublishedMedia?>
}
