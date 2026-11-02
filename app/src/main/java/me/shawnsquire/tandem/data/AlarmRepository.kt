package me.shawnsquire.tandem.data

import kotlinx.coroutines.flow.Flow
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LateRing
import java.time.Instant

/** Local alarm store; sole source of truth for this phone's alarms (I2). Works offline (I1). */
interface AlarmRepository {
    /** Everything: own alarms and linked partner alarms. */
    val alarms: Flow<List<Alarm>>
    /** Rows with link == null (this phone's own alarms). */
    val ownAlarms: Flow<List<Alarm>>
    /** Rows with link != null (joined partner alarms). */
    val linkedAlarms: Flow<List<Alarm>>
    suspend fun get(id: String): Alarm?
    suspend fun upsert(alarm: Alarm)
    suspend fun delete(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun setSkip(id: String, occurrence: Instant?)

    /** Owner's "just me" skip (Alarm.soloSkippedOccurrenceAt); null clears it. */
    suspend fun setSoloSkip(id: String, occurrence: Instant?)

    /** The owner's late-ring answer on its own row (Alarm.lateRing); null clears it. No-op for a missing or linked row. */
    suspend fun setLateRing(id: String, lateRing: LateRing?)

    /**
     * Writes link.joinerEnabled only (the joiner's "just me" on/off) and nothing else, so it never
     * touches the copied `enabled` field. No-op if the row is missing or not linked. Deferrable while
     * locked, unlike [updateLink]: a linked one-shot dismissed on a locked boot must still turn it off.
     */
    suspend fun setJoinerEnabled(id: String, enabled: Boolean)

    /**
     * Writes a linked row; [alarm].link must be non-null (IllegalArgumentException otherwise).
     * Inserts it if absent. If a row with that id exists, replaces only the owner-copied fields
     * (label, hour, minute, days, enabled, lateRing, vibration, vibrationOn, soundOn, link.ownerSkippedOccurrenceAt) and keeps every joiner-owned
     * field (skippedOccurrenceAt, soloSkippedOccurrenceAt, ringtoneUri, wallpaperPath and the rest of link),
     * so a sync never overwrites the joiner's own state. If an existing row with that id is not linked,
     * throws IllegalStateException without writing: an id collision or LinkSync bug must never turn an
     * own alarm into a linked row (I2). One transaction.
     */
    suspend fun upsertLinked(alarm: Alarm)

    /** Atomic read-modify-write of row [id]'s link in one transaction. False if the row is missing or not linked. */
    suspend fun updateLink(id: String, transform: (AlarmLink) -> AlarmLink): Boolean
}
