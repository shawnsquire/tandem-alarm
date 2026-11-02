package me.shawnsquire.tandem.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.schedule.WallpaperFrame
import me.shawnsquire.tandem.sync.PairingException
import me.shawnsquire.tandem.sync.PairingRepository
import me.shawnsquire.tandem.sync.PairingStatus

val SNOOZE_CHOICES: List<Int> = listOf(5, 10, 15, 20)

sealed interface SettingsError {
    data object RingtoneImport : SettingsError
    data object WallpaperImport : SettingsError
    data object Save : SettingsError

    /** [message] is already user-safe. */
    data class Unpair(val message: String) : SettingsError
}

data class SettingsUiState(
    val snoozeMinutes: Int = 10,
    val displayName: String = "",
    val ringtoneUri: String? = null,
    val ringtoneTitle: String = "",
    val wallpaperPath: String? = null,
    /** Frames [wallpaperPath] on the ringing screen; null is the centred crop. */
    val wallpaperFrame: WallpaperFrame? = null,
    /** Set after a successful image pick so the screen opens the preview; the screen clears it with [SettingsViewModel.previewRequestHandled]. */
    val previewRequested: Boolean = false,
    val paired: Boolean = false,
    val partnerName: String? = null,
    val unpairing: Boolean = false,
    /** True while a file import is running. */
    val busy: Boolean = false,
    val error: SettingsError? = null,
)

private const val GENERIC_UNPAIR_ERROR = "Something went wrong. Try again."

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val pairing: PairingRepository,
    displayName: Flow<String>,
    private val setDisplayName: suspend (String?) -> Unit,
    private val ringtoneTitle: suspend (String?) -> String,
    private val importRingtone: suspend (String) -> String,
    private val deleteRingtoneFile: (String) -> Unit,
    private val importWallpaper: suspend (String) -> String,
    private val deleteWallpaperFile: (String) -> Unit,
    /** Whether two wallpaper files hold the same bytes; a throw counts as different. Runs off the main thread. */
    private val sameWallpaperContent: suspend (String, String) -> Boolean,
) : ViewModel() {

    private data class Local(
        val unpairing: Boolean = false,
        val busy: Boolean = false,
        val previewRequested: Boolean = false,
        val error: SettingsError? = null,
    )

    private val local = MutableStateFlow(Local())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ringtone: Flow<Pair<String?, String>> =
        settings.defaultRingtoneUri.mapLatest { it to ringtoneTitle(it) }

    val state: StateFlow<SettingsUiState> = combine(
        settings.snoozeMinutes,
        displayName,
        ringtone,
        settings.defaultWallpaperPath,
        pairing.status,
        local,
        settings.defaultWallpaperFrame,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val ring = values[2] as Pair<String?, String>
        val status = values[4] as PairingStatus
        val loc = values[5] as Local
        SettingsUiState(
            snoozeMinutes = values[0] as Int,
            displayName = values[1] as String,
            ringtoneUri = ring.first,
            ringtoneTitle = ring.second,
            wallpaperPath = values[3] as String?,
            wallpaperFrame = values[6] as WallpaperFrame?,
            previewRequested = loc.previewRequested,
            paired = status is PairingStatus.Paired,
            partnerName = (status as? PairingStatus.Paired)?.partnerName,
            unpairing = loc.unpairing,
            busy = loc.busy,
            error = loc.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setSnooze(minutes: Int) {
        if (minutes !in SNOOZE_CHOICES) return
        viewModelScope.launch { guarded { settings.setSnoozeMinutes(minutes) } }
    }

    /** Trims; blank resets to the default name. */
    fun saveDisplayName(name: String) {
        val trimmed = name.trim()
        viewModelScope.launch { guarded { setDisplayName(trimmed.ifEmpty { null }) } }
    }

    /** A URI from the system sound picker; stored exactly as returned. */
    fun onSystemRingtonePicked(uri: String) {
        viewModelScope.launch { guarded { replaceRingtone(uri) } }
    }

    /** A document URI from the file picker; copied into app storage right away. */
    fun onRingtoneFilePicked(documentUri: String) {
        if (local.value.busy) return
        viewModelScope.launch {
            local.update { it.copy(busy = true, error = null) }
            val imported = try {
                importRingtone(documentUri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(busy = false, error = SettingsError.RingtoneImport) }
                return@launch
            }
            try {
                replaceRingtone(imported)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                deleteRingtoneFile(imported)
                local.update { it.copy(error = SettingsError.Save) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    fun resetRingtone() {
        viewModelScope.launch { guarded { replaceRingtone(null) } }
    }

    fun onWallpaperPicked(contentUri: String) {
        if (local.value.busy) return
        viewModelScope.launch {
            local.update { it.copy(busy = true, error = null) }
            val path = try {
                importWallpaper(contentUri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(busy = false, error = SettingsError.WallpaperImport) }
                return@launch
            }
            try {
                // The same picture again keeps its framing; anything else, or a comparison that fails, starts centred.
                val current = settings.defaultWallpaperPath.first()
                val same = current != null && try {
                    sameWallpaperContent(current, path)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                replaceWallpaper(path, keepFrame = same)
                local.update { it.copy(previewRequested = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                deleteWallpaperFile(path)
                local.update { it.copy(error = SettingsError.Save) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    fun removeWallpaper() {
        viewModelScope.launch { guarded { replaceWallpaper(null, keepFrame = false) } }
    }

    /** The preview's Save; the frame belongs to the default image, so without one there is nothing to frame. */
    fun setWallpaperFrame(frame: WallpaperFrame?) {
        viewModelScope.launch {
            guarded { if (settings.defaultWallpaperPath.first() != null) settings.setDefaultWallpaperFrame(frame) }
        }
    }

    /** The screen opened the preview that [SettingsUiState.previewRequested] asked for. */
    fun previewRequestHandled() = local.update { it.copy(previewRequested = false) }

    fun unpair() {
        if (local.value.unpairing) return
        viewModelScope.launch {
            local.update { it.copy(unpairing = true, error = null) }
            try {
                pairing.unpair()
                local.update { it.copy(unpairing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = (e as? PairingException)?.message?.takeIf { it.isNotBlank() } ?: GENERIC_UNPAIR_ERROR
                local.update { it.copy(unpairing = false, error = SettingsError.Unpair(message)) }
            }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    /** Persist the new value first, and only then delete the file it replaced. */
    private suspend fun replaceRingtone(new: String?) {
        val old = settings.defaultRingtoneUri.first()
        settings.setDefaultRingtoneUri(new)
        if (old != null && old != new) deleteRingtoneFile(old)
    }

    private suspend fun replaceWallpaper(new: String?, keepFrame: Boolean) {
        val old = settings.defaultWallpaperPath.first()
        settings.setDefaultWallpaperPath(new)
        if (!keepFrame) settings.setDefaultWallpaperFrame(null)
        if (old != null && old != new) deleteWallpaperFile(old)
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            local.update { it.copy(error = SettingsError.Save) }
        }
    }
}
