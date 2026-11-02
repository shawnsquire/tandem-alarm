package me.shawnsquire.tandem.sync

import kotlinx.coroutines.flow.Flow
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.WallpaperFrame
import java.time.Instant

/** Outcome of joining a partner's alarm. */
sealed interface LinkResult {
    /** [alarmId] is the linked row's local id. */
    data class Joined(val alarmId: String) : LinkResult

    /** [reason] is shown to the user, e.g. "Shawn deleted this alarm" or "not paired". */
    data class Failed(val reason: String) : LinkResult
}

/**
 * Joiner and owner operations on linked alarms. Linked rows are written here and by [LinkSync] only;
 * a ring already sounding is never changed by any of it (I1). Real implementation: L4.
 */
interface LinkRepository {
    /** Joiner: join the partner's alarm. Idempotent per [ownerAlarmId]; accepts any open invite for it. */
    suspend fun join(ownerAlarmId: String): LinkResult

    /** Joiner: accept INVITE_LINK [commandId] for [ownerAlarmId]; fails (and rejects the command) if the owner's alarm is gone. */
    suspend fun acceptInvite(commandId: String, ownerAlarmId: String): LinkResult

    /** Joiner: decline INVITE_LINK [commandId]; no row is created. */
    suspend fun declineInvite(commandId: String)

    /** Joiner: remove linked row [alarmId]; a sounding ring finishes first, unmirrored. */
    suspend fun leave(alarmId: String)

    /** Applies from the next fire. */
    suspend fun setMode(alarmId: String, mode: LinkMode)

    /** Null means "Use <owner>'s". [wallpaperFrame] frames [wallpaperPath] and is cleared with it. */
    suspend fun setOverrides(
        alarmId: String, label: String?, ringtoneUri: String?, wallpaperPath: String?, wallpaperFrame: WallpaperFrame?,
    )

    /** Replaces the joiner's override wallpaper frame and text position; null text position means "Use <owner>'s". */
    suspend fun setOverrideFraming(alarmId: String, wallpaperFrame: WallpaperFrame?, textPosition: TextPosition?)

    /** Replaces the joiner's sound, vibration switch and vibration pattern overrides; null means "Use <owner>'s". */
    suspend fun setOutputOverrides(alarmId: String, soundOn: Boolean?, vibrationOn: Boolean?, vibration: VibrationPattern?)

    /**
     * Joiner "just me" on/off (link.joinerEnabled). A "just me" skip is AlarmRepository.setSkip.
     * The real one delegates to AlarmRepository.setJoinerEnabled (deferrable while locked), then reschedules.
     */
    suspend fun setJoinerEnabled(alarmId: String, enabled: Boolean)

    /** Joiner "both of us": records link.pendingBoth (takes effect at once) and sends [type] (SKIP_NEXT, UNSKIP, DISABLE or ENABLE) for the owner's alarm; a rejection clears it and calls CommandNotifier.notifyRejected. */
    suspend fun sendBoth(alarmId: String, type: CommandType, occurrenceAt: Instant?)

    /** Owner: send INVITE_LINK for own alarm [alarmId]. */
    fun invite(alarmId: String): Flow<Pair<CommandStatus, String?>>

    /** Owner: own alarm ids with an open (pending, unexpired) invite; MediaPublisher uploads for these too. */
    val openInvites: Flow<Set<String>>

    /** This phone's links for the device doc (`links`), derived from the linked rows. */
    val links: Flow<List<PublishedLink>>
}
