package me.shawnsquire.tandem.ui.alarms.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.sync.LinkRepository
import me.shawnsquire.tandem.sync.PairingRepository
import me.shawnsquire.tandem.sync.PairingStatus
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.ui.alarms.AlarmEdits
import me.shawnsquire.tandem.ui.partner.CommandUiStatus
import me.shawnsquire.tandem.ui.partner.FALLBACK_NAME
import me.shawnsquire.tandem.ui.resolvePartnerName
import java.time.DayOfWeek

enum class EditError { RingtoneImport, WallpaperImport, Save }

/** What the editor offers about the partner joining this alarm. */
enum class EditInvite { Hidden, Invite, Invited, Joined }

data class AlarmEditUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val hour: Int = 7,
    val minute: Int = 0,
    val days: Set<DayOfWeek> = emptySet(),
    val label: String = "",
    val ringtoneUri: String? = null,
    val ringtoneTitle: String = "",
    val wallpaperPath: String? = null,
    val busy: Boolean = false,
    val error: EditError? = null,
    /** Set once the screen should close (saved, cancelled, or the alarm vanished). */
    val closed: Boolean = false,
    val invite: EditInvite = EditInvite.Hidden,
    val partnerName: String = FALLBACK_NAME,
    /** Set to Rejected when the last invite did not go through; cleared on the next send. */
    val inviteStatus: CommandUiStatus? = null,
)

class AlarmEditViewModel(
    /** Null for a new alarm. */
    private val alarmId: String?,
    private val repository: AlarmRepository,
    private val edits: AlarmEdits,
    private val ringtoneTitle: suspend (String?) -> String,
    private val importRingtone: suspend (String) -> String,
    private val deleteRingtoneFile: (String) -> Unit,
    private val importWallpaper: suspend (String) -> String,
    private val deleteWallpaperFile: (String) -> Unit,
    private val newId: () -> String,
    private val links: LinkRepository,
    pairing: PairingRepository,
    partnerRepository: PartnerRepository,
    /** Shown when an invite is rejected without a reason. */
    private val inviteFailedReason: String,
) : ViewModel() {

    private val _state = MutableStateFlow(AlarmEditUiState())
    val state: StateFlow<AlarmEditUiState> = _state.asStateFlow()

    /** The id of the alarm being edited; null for a new one. */
    val existingId: String? get() = alarmId

    /** Files imported during this session and not yet saved. */
    private var sessionRingtone: String? = null
    private var sessionWallpaper: String? = null

    /** Set when the session ended (saved, cancelled, discarded); late imports are cleaned up. */
    private var finished = false

    /** True from [save] until its write settles; the session's files must not be touched meanwhile. */
    private var saving = false

    /** A new alarm keeps one id across failed saves so a retry cannot create a duplicate. */
    private var newAlarmId: String? = null

    /** True once a saved own alarm has loaded; only then can the partner be invited to it. */
    private val invitable = MutableStateFlow(false)

    /** This screen's own invite is waiting for the partner. */
    private val invitePending = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(
                invitable, pairing.status, partnerRepository.partner, links.openInvites, invitePending,
            ) { canInvite, status, partner, open, pending ->
                val joined = partner?.links?.any { it.ownerAlarmId == alarmId } == true
                val invite = when {
                    !canInvite || status !is PairingStatus.Paired -> EditInvite.Hidden
                    joined -> EditInvite.Joined
                    pending || alarmId in open -> EditInvite.Invited
                    else -> EditInvite.Invite
                }
                invite to resolvePartnerName(status, partner)
            }.collect { (invite, name) -> _state.update { it.copy(invite = invite, partnerName = name) } }
        }
        viewModelScope.launch {
            if (alarmId == null) {
                _state.update { it.copy(loaded = true, isNew = true, ringtoneTitle = ringtoneTitle(null)) }
            } else {
                val alarm = repository.get(alarmId)
                if (alarm == null || alarm.link != null) {
                    // A joined row is edited in its sheet, never here.
                    _state.update { it.copy(loaded = true, closed = true) }
                } else {
                    invitable.value = true
                    _state.update {
                        it.copy(
                            loaded = true, isNew = false, hour = alarm.hour, minute = alarm.minute,
                            days = alarm.days, label = alarm.label, ringtoneUri = alarm.ringtoneUri,
                            wallpaperPath = alarm.wallpaperPath,
                            ringtoneTitle = ringtoneTitle(alarm.ringtoneUri),
                        )
                    }
                }
            }
        }
    }

    /** Invites the partner to join this alarm; the result shows as Invited, then Joined, or a reason. */
    fun invite() {
        val id = alarmId ?: return
        if (_state.value.invite != EditInvite.Invite) return
        _state.update { it.copy(inviteStatus = null) }
        viewModelScope.launch {
            invitePending.value = true
            try {
                links.invite(id).collect { (status, reason) ->
                    when (status) {
                        CommandStatus.PENDING -> invitePending.value = true
                        CommandStatus.APPLIED -> invitePending.value = false
                        CommandStatus.REJECTED -> {
                            invitePending.value = false
                            _state.update {
                                it.copy(inviteStatus = CommandUiStatus.Rejected(reason ?: inviteFailedReason))
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(inviteStatus = CommandUiStatus.Rejected(inviteFailedReason)) }
            } finally {
                invitePending.value = false
            }
        }
    }

    fun setTime(hour: Int, minute: Int) = _state.update { it.copy(hour = hour, minute = minute) }

    fun toggleDay(day: DayOfWeek) = _state.update {
        it.copy(days = if (day in it.days) it.days - day else it.days + day)
    }

    fun setLabel(label: String) = _state.update { it.copy(label = label) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** A URI returned by the system sound picker; stored exactly as returned. */
    fun onSystemRingtonePicked(uri: String) {
        if (saving || finished) return
        replaceRingtone(uri)
        updateTitle(uri)
    }

    /** A document URI from the file picker; copied into app storage right away. */
    fun onRingtoneFilePicked(documentUri: String) {
        if (_state.value.busy || saving || finished) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val imported = try {
                importRingtone(documentUri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, error = EditError.RingtoneImport) }
                return@launch
            }
            if (finished) {
                deleteRingtoneFile(imported)
                return@launch
            }
            replaceRingtone(imported, sessionImport = true)
            _state.update { it.copy(busy = false) }
            updateTitle(imported)
        }
    }

    fun onWallpaperPicked(contentUri: String) {
        if (_state.value.busy || saving || finished) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val path = try {
                importWallpaper(contentUri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, error = EditError.WallpaperImport) }
                return@launch
            }
            if (finished) {
                deleteWallpaperFile(path)
                return@launch
            }
            sessionWallpaper?.let(deleteWallpaperFile)
            sessionWallpaper = path
            _state.update { it.copy(busy = false, wallpaperPath = path) }
        }
    }

    fun removeWallpaper() {
        if (saving || finished) return
        // A file imported this session was never saved, so drop it now; the original goes on save.
        sessionWallpaper?.let(deleteWallpaperFile)
        sessionWallpaper = null
        _state.update { it.copy(wallpaperPath = null) }
    }

    private fun replaceRingtone(uri: String, sessionImport: Boolean = false) {
        sessionRingtone?.let(deleteRingtoneFile)
        sessionRingtone = if (sessionImport) uri else null
        _state.update { it.copy(ringtoneUri = uri) }
    }

    private fun updateTitle(uri: String?) {
        viewModelScope.launch {
            val title = ringtoneTitle(uri)
            _state.update { if (it.ringtoneUri == uri) it.copy(ringtoneTitle = title) else it }
        }
    }

    fun save() {
        val s = _state.value
        if (!s.loaded || s.busy || saving || finished) return
        saving = true
        // ATOMIC: even if the scope is cancelled before the body starts, the catch below settles the session.
        viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            var current: Alarm? = null
            var alarm: Alarm? = null
            try {
                _state.update { it.copy(busy = true, error = null) }
                // Re-read the row: a partner skip may have been applied while the editor was open.
                current = alarmId?.let { repository.get(it) }
                val unchanged = current != null && current.hour == s.hour && current.minute == s.minute &&
                    current.days == s.days
                alarm = Alarm(
                    id = alarmId ?: newAlarmId ?: newId().also { newAlarmId = it },
                    label = s.label.trim(),
                    hour = s.hour,
                    minute = s.minute,
                    days = s.days,
                    enabled = true,
                    skippedOccurrenceAt = if (unchanged) current?.skippedOccurrenceAt else null,
                    soloSkippedOccurrenceAt = if (unchanged) current?.soloSkippedOccurrenceAt else null,
                    link = current?.link,
                    ringtoneUri = s.ringtoneUri,
                    wallpaperPath = s.wallpaperPath,
                )
                edits.upsert(alarm)
            } catch (e: CancellationException) {
                // The upsert is NonCancellable, so it may have committed even though we were cancelled.
                withContext(NonCancellable) { settleInterrupted(alarm, current) }
                throw e
            } catch (_: Exception) {
                // The write may have committed before a later step (the reschedule) threw; then the alarm is saved and
                // its files are in use, so close like a success rather than offer a retry or cleanup.
                val a = alarm
                if (a != null && writeLanded(a)) {
                    saving = false
                    finishSaved(a, current)
                    _state.update { it.copy(busy = false, closed = true) }
                } else {
                    saving = false
                    _state.update { it.copy(busy = false, error = EditError.Save) }
                }
                return@launch
            }
            saving = false
            finishSaved(alarm, current)
            _state.update { it.copy(busy = false, closed = true) }
        }
    }

    /** The write committed: replaced files of the stored row are now unreferenced. */
    private fun finishSaved(alarm: Alarm, previous: Alarm?) {
        finished = true
        previous?.ringtoneUri?.takeIf { it != alarm.ringtoneUri }?.let(deleteRingtoneFile)
        previous?.wallpaperPath?.takeIf { it != alarm.wallpaperPath }?.let(deleteWallpaperFile)
    }

    private suspend fun settleInterrupted(alarm: Alarm?, previous: Alarm?) {
        saving = false
        if (alarm != null && writeLanded(alarm)) finishSaved(alarm, previous) else dropSessionFiles()
    }

    /**
     * Whether [alarm] was written. Compares every field the editor writes and ignores only those other
     * writers change: `enabled` (partner ENABLE/DISABLE), `skippedOccurrenceAt` (partner skip, and the
     * expired-skip strip on read) and `soloSkippedOccurrenceAt` (the owner's solo skip). A failed upsert leaves the old row, which differs in whatever was edited.
     */
    private suspend fun writeLanded(alarm: Alarm): Boolean {
        val stored = runCatching { repository.get(alarm.id) }.getOrNull() ?: return false
        return stored.copy(
            enabled = alarm.enabled,
            skippedOccurrenceAt = alarm.skippedOccurrenceAt,
            soloSkippedOccurrenceAt = alarm.soloSkippedOccurrenceAt,
        ) == alarm
    }

    fun cancel() {
        if (saving) return
        discardSession()
        _state.update { it.copy(closed = true) }
    }

    /** The editor can be destroyed without Save or Cancel (e.g. a tab switch); drop unsaved imports then. */
    public override fun onCleared() {
        discardSession()
    }

    /** Drops files imported this session without saving; the caller handles navigation. */
    fun discardSession() {
        if (finished || saving) return
        dropSessionFiles()
    }

    private fun dropSessionFiles() {
        finished = true
        sessionRingtone?.let(deleteRingtoneFile)
        sessionWallpaper?.let(deleteWallpaperFile)
        sessionRingtone = null
        sessionWallpaper = null
    }
}
