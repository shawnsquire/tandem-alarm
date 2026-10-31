package me.shawnsquire.tandem.ui.alarms.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.ui.alarms.AlarmEdits
import java.time.DayOfWeek

enum class EditError { RingtoneImport, WallpaperImport, Save }

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
) : ViewModel() {

    private val _state = MutableStateFlow(AlarmEditUiState())
    val state: StateFlow<AlarmEditUiState> = _state.asStateFlow()

    /** The id of the alarm being edited; null for a new one. */
    val existingId: String? get() = alarmId

    private var original: Alarm? = null

    /** Files imported during this session and not yet saved. */
    private var sessionRingtone: String? = null
    private var sessionWallpaper: String? = null

    /** Set when the session ended (saved, cancelled, discarded); late imports are cleaned up. */
    private var finished = false

    init {
        viewModelScope.launch {
            if (alarmId == null) {
                _state.update { it.copy(loaded = true, isNew = true, ringtoneTitle = ringtoneTitle(null)) }
            } else {
                val alarm = repository.get(alarmId)
                if (alarm == null) {
                    _state.update { it.copy(loaded = true, closed = true) }
                } else {
                    original = alarm
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

    fun setTime(hour: Int, minute: Int) = _state.update { it.copy(hour = hour, minute = minute) }

    fun toggleDay(day: DayOfWeek) = _state.update {
        it.copy(days = if (day in it.days) it.days - day else it.days + day)
    }

    fun setLabel(label: String) = _state.update { it.copy(label = label) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** A URI returned by the system sound picker; stored exactly as returned. */
    fun onSystemRingtonePicked(uri: String) {
        replaceRingtone(uri)
        updateTitle(uri)
    }

    /** A document URI from the file picker; copied into app storage right away. */
    fun onRingtoneFilePicked(documentUri: String) {
        if (_state.value.busy || finished) return
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
        if (_state.value.busy || finished) return
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
        if (!s.loaded || s.busy || finished) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val orig = original
            val unchanged = orig != null && orig.hour == s.hour && orig.minute == s.minute && orig.days == s.days
            val alarm = Alarm(
                id = orig?.id ?: alarmId ?: newId(),
                label = s.label.trim(),
                hour = s.hour,
                minute = s.minute,
                days = s.days,
                enabled = true,
                skippedOccurrenceAt = if (unchanged) orig?.skippedOccurrenceAt else null,
                ringtoneUri = s.ringtoneUri,
                wallpaperPath = s.wallpaperPath,
            )
            try {
                edits.upsert(alarm)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, error = EditError.Save) }
                return@launch
            }
            finished = true
            orig?.ringtoneUri?.takeIf { it != alarm.ringtoneUri }?.let(deleteRingtoneFile)
            orig?.wallpaperPath?.takeIf { it != alarm.wallpaperPath }?.let(deleteWallpaperFile)
            _state.update { it.copy(busy = false, closed = true) }
        }
    }

    fun cancel() {
        discardSession()
        _state.update { it.copy(closed = true) }
    }

    /** Drops files imported this session without saving; the caller handles navigation. */
    fun discardSession() {
        if (finished) return
        finished = true
        sessionRingtone?.let(deleteRingtoneFile)
        sessionWallpaper?.let(deleteWallpaperFile)
        sessionRingtone = null
        sessionWallpaper = null
    }
}
