package me.shawnsquire.tandem.sync

import me.shawnsquire.tandem.alarm.RingMirrorPort

/** L4: sends SNOOZE / STOP_RINGING for linked members (joiner) or partner-joined members (owner, from the partner's links); never for REMOTE resolutions (I4); queues in a device-protected outbox while locked. */
interface RingMirror : RingMirrorPort {
    /** Sends commands queued in the outbox while locked; stale ones are rejected by I3 on the other phone. */
    suspend fun flushOutbox()
}
