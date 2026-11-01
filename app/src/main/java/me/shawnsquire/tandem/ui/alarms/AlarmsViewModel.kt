package me.shawnsquire.tandem.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.isArmed
import me.shawnsquire.tandem.sync.LinkRepository
import me.shawnsquire.tandem.sync.PairingRepository
import me.shawnsquire.tandem.sync.PartnerAlarm
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.sync.PublishedLink
import me.shawnsquire.tandem.ui.partner.FALLBACK_NAME
import me.shawnsquire.tandem.ui.resolvePartnerName
import me.shawnsquire.tandem.schedule.isOneShot
import me.shawnsquire.tandem.schedule.nextOccurrence
import me.shawnsquire.tandem.ui.alarms.linked.LinkedAlarmActions
import me.shawnsquire.tandem.ui.alarms.linked.LinkedSwitchOptions
import me.shawnsquire.tandem.ui.alarms.linked.linkedSwitchOptions
import me.shawnsquire.tandem.ui.formatDays
import me.shawnsquire.tandem.ui.formatRingsIn
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class AlarmRowState(
    val id: String,
    val hour: Int,
    val minute: Int,
    val label: String,
    val daysText: String,
    val enabled: Boolean,
    /** Next ring time; when [skipped], the occurrence after the skipped one. Null when disabled. */
    val nextRing: Instant?,
    /** "Rings in 7 h 12 min", or null when disabled. */
    val ringsInText: String?,
    val skipped: Boolean,
    val oneShot: Boolean,
    /** Non-null for a joined row: the partner's alarm this phone mirrors. */
    val linked: LinkedRowState? = null,
    /** On an own row: the partner's name once they have joined it, else null. */
    val partnerJoined: String? = null,
    /** On an own row: [skipped] only because of the owner's "just me" skip, shown as "Skipped for you". */
    val skippedSolo: Boolean = false,
)

/** What a joined row shows beyond a plain alarm. At most one of the skip flags is shown, in this order. */
data class LinkedRowState(
    val partnerName: String,
    val mode: LinkMode,
    /** The owner's shared skip (everyone skips the next ring). */
    val ownerSkipped: Boolean,
    /** This phone's own "skip for me". */
    val skippedForYou: Boolean,
    /** The owner's "just me" skip: they skip, this phone still rings. */
    val ownerSkippingSolo: Boolean,
    /** A "both of us" command is waiting on the owner's phone. */
    val pendingBoth: Boolean,
    /** What the row's switch offers when asked "Just me or both of us?". */
    val switchOptions: LinkedSwitchOptions = LinkedSwitchOptions(justMe = true, both = true),
)

data class AlarmsUiState(
    val loaded: Boolean = false,
    val rows: List<AlarmRowState> = emptyList(),
    val zone: ZoneId = ZoneId.systemDefault(),
)

/**
 * Maps stored alarms to rows. Shared by the ViewModel and the previews. [partnerAlarms] is keyed by the
 * partner's alarm id and supplies the owner's solo skip for joined rows.
 */
internal fun buildAlarmsState(
    alarms: List<Alarm>,
    now: Instant,
    zone: ZoneId,
    partnerName: String = FALLBACK_NAME,
    partnerAlarms: Map<String, PartnerAlarm> = emptyMap(),
    /** What the partner has joined; matched against own rows' ids. */
    partnerLinks: List<PublishedLink> = emptyList(),
): AlarmsUiState {
    val joinedIds = partnerLinks.mapTo(HashSet()) { it.ownerAlarmId }
    val rows = alarms
        .sortedWith(compareBy<Alarm>({ it.hour }, { it.minute }, { it.label.lowercase() }, { it.id }))
        .map { a ->
            val link = a.link
            val armed = isArmed(a)
            val next = nextOccurrence(a, now, zone)
            val ownSkip = armed && a.skippedOccurrenceAt?.let { it > now } == true
            // The owner's "just me" skip; a joiner's copy never carries one.
            val soloSkip = link == null && armed && a.soloSkippedOccurrenceAt?.let { it > now } == true
            val linked = link?.let {
                val ownerSkipped = armed && it.ownerSkippedOccurrenceAt?.let { t -> t > now } == true
                LinkedRowState(
                    partnerName = partnerName,
                    mode = it.mode,
                    ownerSkipped = ownerSkipped,
                    skippedForYou = ownSkip,
                    ownerSkippingSolo = armed &&
                        partnerAlarms[it.ownerAlarmId]?.soloSkippedOccurrenceAt?.let { t -> t > now } == true,
                    pendingBoth = it.pendingBoth != null,
                    switchOptions = linkedSwitchOptions(a, partnerAlarms[it.ownerAlarmId]),
                )
            }
            AlarmRowState(
                id = a.id, hour = a.hour, minute = a.minute, label = link?.overrideLabel ?: a.label,
                daysText = formatDays(a.days), enabled = armed,
                nextRing = next,
                ringsInText = next?.let { formatRingsIn(Duration.between(now, it)) },
                skipped = ownSkip || soloSkip || linked?.ownerSkipped == true, oneShot = a.isOneShot(),
                linked = linked,
                partnerJoined = partnerName.takeIf { link == null && a.id in joinedIds },
                skippedSolo = soloSkip && !ownSkip,
            )
        }
    return AlarmsUiState(loaded = true, rows = rows, zone = zone)
}

class AlarmsViewModel(
    private val repository: AlarmRepository,
    private val edits: AlarmEdits,
    links: LinkRepository,
    pairing: PairingRepository,
    private val partnerRepository: PartnerRepository,
    private val clock: Clock,
    /** Must emit once immediately, then on each refresh (see tickerFlow). */
    ticker: Flow<Unit>,
    private val onAlarmPurged: (Alarm) -> Unit,
) : ViewModel() {

    private val actions = LinkedAlarmActions(links, edits)

    val state: StateFlow<AlarmsUiState> =
        combine(repository.alarms, pairing.status, partnerRepository.partner, ticker) { alarms, status, partner, _ ->
            buildAlarmsState(
                alarms, clock.instant(), clock.zone,
                partnerName = resolvePartnerName(status, partner),
                partnerAlarms = partner?.alarms.orEmpty().associateBy { it.id },
                partnerLinks = partner?.links.orEmpty(),
            )
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmsUiState(zone = clock.zone))

    /** The deleted alarm whose snackbar is showing; its media is purged only if undo never happens. */
    private var pendingDelete: Alarm? = null

    private val _pendingDeleteId = MutableStateFlow<String?>(null)

    /**
     * The id whose "Alarm deleted · Undo" snackbar should be showing. State, not an event, so the screen
     * re-shows it after rotation or navigation; report the outcome via [undoDelete] or [onDeleteSnackbarDismissed].
     */
    val pendingDeleteId: StateFlow<String?> = _pendingDeleteId.asStateFlow()

    // A joined row's enabled and occurrence are the owner's; the UI routes those actions to the linked sheet.
    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            if (repository.get(id)?.link != null) return@launch
            edits.setEnabled(id, enabled)
        }
    }

    /** A joined row's switch, "just me": flips this phone's own on/off. */
    fun setLinkedJustMe(id: String) {
        viewModelScope.launch {
            val row = repository.get(id)?.takeIf { it.link != null } ?: return@launch
            quietly { actions.setJustMe(row, on = !isArmed(row)) }
        }
    }

    /** A joined row's switch, "both of us": asks the owner to flip theirs too. */
    fun setLinkedBoth(id: String) {
        viewModelScope.launch {
            val row = repository.get(id)?.takeIf { it.link != null } ?: return@launch
            val owner = partnerRepository.partner.first()?.alarms?.firstOrNull { it.id == row.link?.ownerAlarmId }
            quietly { actions.setBoth(row, owner, on = !isArmed(row)) }
        }
    }

    /** A failed switch action leaves the row as it was; it must not crash the app. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Skip "both of us" (and the only skip for an alarm nobody joined). Repeating alarms pin the skip to the
     * occurrence due right now; a one-shot is simply turned off.
     */
    fun skipNext(id: String) {
        viewModelScope.launch {
            val alarm = repository.get(id) ?: return@launch
            if (alarm.link != null || !alarm.enabled) return@launch
            if (alarm.isOneShot()) {
                edits.setEnabled(id, false)
            } else {
                val next = nextOccurrence(alarm, clock.instant(), clock.zone) ?: return@launch
                edits.setSkip(id, next)
            }
        }
    }

    /**
     * Skip "just me" on an alarm the partner joined: only this phone stops ringing. A one-shot stays on
     * (turning it off would be copied to the partner), so the occurrence is pinned like a repeating one.
     */
    fun skipJustMe(id: String) {
        viewModelScope.launch {
            val alarm = repository.get(id) ?: return@launch
            if (alarm.link != null || !alarm.enabled) return@launch
            val next = nextOccurrence(alarm, clock.instant(), clock.zone) ?: return@launch
            edits.setSoloSkip(id, next)
        }
    }

    fun unskip(id: String) {
        viewModelScope.launch {
            if (repository.get(id)?.link != null) return@launch
            edits.clearSkips(id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val alarm = repository.get(id) ?: return@launch
            // A newer delete replaces the one whose snackbar is going away; its undo window is over.
            pendingDelete?.let(onAlarmPurged)
            pendingDelete = alarm
            edits.delete(id)
            _pendingDeleteId.value = id
        }
    }

    /** Restores the pending delete only if it is still [alarmId]; a stale Undo is a no-op. */
    fun undoDelete(alarmId: String) {
        val alarm = pendingDelete?.takeIf { it.id == alarmId } ?: return
        pendingDelete = null
        _pendingDeleteId.value = null
        viewModelScope.launch { edits.upsert(alarm) }
    }

    /** The snackbar for [alarmId] went away without undo: the alarm is gone for good, so purge its media. */
    fun onDeleteSnackbarDismissed(alarmId: String) {
        val alarm = pendingDelete?.takeIf { it.id == alarmId } ?: return
        pendingDelete = null
        _pendingDeleteId.value = null
        onAlarmPurged(alarm)
    }

    override fun onCleared() {
        pendingDelete?.let(onAlarmPurged)
        pendingDelete = null
    }
}
