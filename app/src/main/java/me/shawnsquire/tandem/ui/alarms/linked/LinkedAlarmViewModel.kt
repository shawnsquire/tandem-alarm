package me.shawnsquire.tandem.ui.alarms.linked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.isArmed
import me.shawnsquire.tandem.sync.LinkRepository
import me.shawnsquire.tandem.sync.PairingRepository
import me.shawnsquire.tandem.sync.PartnerAlarm
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.sync.PartnerState
import me.shawnsquire.tandem.ui.formatDays
import me.shawnsquire.tandem.ui.partner.CommandUiStatus
import me.shawnsquire.tandem.ui.partner.FALLBACK_NAME
import me.shawnsquire.tandem.ui.resolvePartnerName
import java.time.Clock

enum class LinkedError { RingtoneImport, WallpaperImport, Save }

data class LinkedSheetState(
    val loaded: Boolean = false,
    /** The row is gone (left, or the owner deleted it): the sheet should close. */
    val closed: Boolean = false,
    val hour: Int = 0,
    val minute: Int = 0,
    val daysText: String = "",
    /** The label shown: the override, else the owner's. */
    val label: String = "",
    val partnerName: String = FALLBACK_NAME,
    val mode: LinkMode = LinkMode.RING,
    /** Null means "Use <owner>'s". */
    val overrideLabel: String? = null,
    val overrideRingtone: String? = null,
    val ringtoneTitle: String = "",
    val overrideWallpaper: String? = null,
    val armed: Boolean = false,
    val skippedForYou: Boolean = false,
    /** "Both of us" needs an owner occurrence to pin to; without one it is not offered. */
    val bothAvailable: Boolean = false,
    val bothStatus: CommandUiStatus? = null,
    val askingSkip: Boolean = false,
    val confirmingLeave: Boolean = false,
    val busy: Boolean = false,
    val error: LinkedError? = null,
)

private data class SheetLocal(
    val askingSkip: Boolean = false,
    val confirmingLeave: Boolean = false,
    val busy: Boolean = false,
    val error: LinkedError? = null,
)

class LinkedAlarmViewModel(
    /** The linked row's local id. */
    private val alarmId: String,
    private val repository: AlarmRepository,
    private val links: LinkRepository,
    private val actions: LinkedAlarmActions,
    pairing: PairingRepository,
    private val partnerRepository: PartnerRepository,
    private val clock: Clock,
    private val ringtoneTitle: suspend (String?) -> String,
    private val importRingtone: suspend (String) -> String,
    private val deleteRingtoneFile: (String) -> Unit,
    private val importWallpaper: suspend (String) -> String,
    private val deleteWallpaperFile: (String) -> Unit,
    /** Shown when a "both of us" command did not take effect. */
    private val rejectedReason: String,
) : ViewModel() {

    private val local = MutableStateFlow(SheetLocal())
    private val sent = MutableStateFlow<SentBoth?>(null)

    /** setOverrides writes all three fields, so every override change reads the row and writes under this. */
    private val overrideLock = Mutex()

    private var lastTitle: Pair<String, String>? = null

    init {
        // A command this sheet did not send (the Alarms switch, an earlier process or VM) is tracked too, so its
        // Pending, then Applied or Rejected, shows here as well.
        viewModelScope.launch {
            repository.alarms.collect { alarms ->
                val pending = alarms.firstOrNull { it.id == alarmId }?.link?.pendingBoth ?: return@collect
                sent.update { tracked ->
                    if (tracked?.type == pending.type && tracked.occurrenceAt == pending.occurrenceAt) tracked
                    else SentBoth(pending.type, pending.occurrenceAt, returned = true)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LinkedSheetState> =
        combine(repository.alarms, pairing.status, partnerRepository.partner, local, sent) { alarms, status, partner, local, sent ->
            val alarm = alarms.firstOrNull { it.id == alarmId }
            val link = alarm?.link
            if (alarm == null || link == null) return@combine LinkedSheetState(loaded = true, closed = true)
            val now = clock.instant()
            LinkedSheetState(
                loaded = true,
                hour = alarm.hour, minute = alarm.minute, daysText = formatDays(alarm.days),
                label = link.overrideLabel ?: alarm.label,
                partnerName = resolvePartnerName(status, partner),
                mode = link.mode,
                overrideLabel = link.overrideLabel,
                overrideRingtone = link.overrideRingtoneUri,
                overrideWallpaper = link.overrideWallpaperPath,
                armed = isArmed(alarm),
                skippedForYou = isArmed(alarm) && alarm.skippedOccurrenceAt?.let { it > now } == true,
                bothAvailable = ownerAlarm(partner, link)?.nextOccurrenceAt != null,
                bothStatus = sent?.let { deriveBothStatus(it, alarm, rejectedReason) },
                askingSkip = local.askingSkip, confirmingLeave = local.confirmingLeave,
                busy = local.busy, error = local.error,
            )
        }
            .mapLatest { s -> s.copy(ringtoneTitle = s.overrideRingtone?.let { titleOf(it) }.orEmpty()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkedSheetState())

    private suspend fun titleOf(uri: String): String =
        lastTitle?.takeIf { it.first == uri }?.second ?: ringtoneTitle(uri).also { lastTitle = uri to it }

    private fun ownerAlarm(partner: PartnerState?, link: AlarmLink): PartnerAlarm? =
        partner?.alarms?.firstOrNull { it.id == link.ownerAlarmId }

    private suspend fun currentRow(): Alarm? = repository.get(alarmId)?.takeIf { it.link != null }

    private suspend fun currentOwnerAlarm(row: Alarm): PartnerAlarm? =
        ownerAlarm(partnerRepository.partner.first(), row.link!!)

    fun setMode(mode: LinkMode) {
        viewModelScope.launch {
            if (currentRow()?.link?.mode == mode) return@launch
            runSaving { links.setMode(alarmId, mode) }
        }
    }

    // Skip.

    fun askSkip() = local.update { it.copy(askingSkip = true) }

    fun cancelSkip() = local.update { it.copy(askingSkip = false) }

    fun skipJustMe() {
        viewModelScope.launch {
            local.update { it.copy(askingSkip = false) }
            val row = currentRow()?.takeIf { isArmed(it) } ?: return@launch
            runSaving { actions.skipJustMe(row, clock.instant(), clock.zone) }
        }
    }

    fun unskip() {
        viewModelScope.launch { if (currentRow() != null) runSaving { actions.unskipJustMe(alarmId) } }
    }

    fun skipBoth() {
        viewModelScope.launch {
            local.update { it.copy(askingSkip = false) }
            val row = currentRow()?.takeIf { isArmed(it) } ?: return@launch
            val owner = currentOwnerAlarm(row)
            val pin = owner?.nextOccurrenceAt ?: return@launch
            // Published before the call so the row never reads as settled while the command is on its way.
            val command = SentBoth(CommandType.SKIP_NEXT, pin)
            sent.value = command
            try {
                actions.skipBoth(alarmId, owner)
                sent.compareAndSetReturned(command)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                sent.update { if (it == command) command.copy(failed = true) else it }
            }
        }
    }

    private fun MutableStateFlow<SentBoth?>.compareAndSetReturned(command: SentBoth) =
        update { if (it == command) command.copy(returned = true) else it }

    // Leave.

    fun askLeave() = local.update { it.copy(confirmingLeave = true) }

    fun cancelLeave() = local.update { it.copy(confirmingLeave = false) }

    /** Leaves, then calls [onLeft] so the sheet closes even before the row's removal is observed. */
    fun leave(onLeft: () -> Unit) {
        viewModelScope.launch {
            local.update { it.copy(confirmingLeave = false) }
            if (runSaving { links.leave(alarmId) }) onLeft()
        }
    }

    // Overrides.

    /** Commits the message field; blank (or the owner's own wording unset) means "Use <owner>'s". */
    fun setMessage(text: String) {
        viewModelScope.launch {
            overrideLock.withLock {
                val link = currentRow()?.link ?: return@withLock
                val label = text.trim().ifEmpty { null }
                if (label != link.overrideLabel) saveOverrides(link, label = label)
            }
        }
    }

    /** A URI returned by the system sound picker; stored exactly as returned. */
    fun onSystemRingtonePicked(uri: String) {
        viewModelScope.launch {
            overrideLock.withLock {
                val link = currentRow()?.link ?: return@withLock
                saveOverrides(link, ringtone = uri)
            }
        }
    }

    /** A document URI from the file picker; copied into app storage first, and nothing changes if that fails. */
    fun onRingtoneFilePicked(documentUri: String) {
        viewModelScope.launch {
            overrideLock.withLock {
                importThenSave(
                    import = { importRingtone(documentUri) }, delete = deleteRingtoneFile,
                    failure = LinkedError.RingtoneImport,
                ) { link, path -> saveOverrides(link, ringtone = path) }
            }
        }
    }

    fun useOwnersRingtone() {
        viewModelScope.launch {
            overrideLock.withLock {
                val link = currentRow()?.link ?: return@withLock
                if (link.overrideRingtoneUri != null) saveOverrides(link, ringtone = null)
            }
        }
    }

    fun onWallpaperPicked(contentUri: String) {
        viewModelScope.launch {
            overrideLock.withLock {
                importThenSave(
                    import = { importWallpaper(contentUri) }, delete = deleteWallpaperFile,
                    failure = LinkedError.WallpaperImport,
                ) { link, path -> saveOverrides(link, wallpaper = path) }
            }
        }
    }

    fun useOwnersWallpaper() {
        viewModelScope.launch {
            overrideLock.withLock {
                val link = currentRow()?.link ?: return@withLock
                if (link.overrideWallpaperPath != null) saveOverrides(link, wallpaper = null)
            }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    /** The sheet went away: forget a settled "both of us" result so reopening starts clean. */
    fun onSheetClosed() {
        viewModelScope.launch {
            local.update { SheetLocal() }
            val row = currentRow()
            if (row?.link?.pendingBoth == null) sent.value = null
        }
    }

    private suspend fun importThenSave(
        import: suspend () -> String,
        delete: (String) -> Unit,
        failure: LinkedError,
        save: suspend (AlarmLink, String) -> Boolean,
    ) {
        local.update { it.copy(busy = true, error = null) }
        try {
            val imported = try {
                import()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(error = failure) }
                return
            }
            val link = currentRow()?.link
            // Nothing took the new file (row gone or the write failed), so it is unreferenced.
            if (link == null || !save(link, imported)) delete(imported)
        } finally {
            local.update { it.copy(busy = false) }
        }
    }

    /**
     * Writes the three overrides, defaulting each field to the row's current value (setOverrides resets any
     * field it is not given). Files the row no longer references are deleted once the write has returned.
     */
    private suspend fun saveOverrides(
        link: AlarmLink,
        label: String? = link.overrideLabel,
        ringtone: String? = link.overrideRingtoneUri,
        wallpaper: String? = link.overrideWallpaperPath,
    ): Boolean {
        if (!runSaving { links.setOverrides(alarmId, label, ringtone, wallpaper) }) return false
        if (link.overrideRingtoneUri != ringtone) link.overrideRingtoneUri?.let(deleteRingtoneFile)
        if (link.overrideWallpaperPath != wallpaper) link.overrideWallpaperPath?.let(deleteWallpaperFile)
        return true
    }

    private suspend fun runSaving(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        local.update { it.copy(error = LinkedError.Save) }
        false
    }
}
