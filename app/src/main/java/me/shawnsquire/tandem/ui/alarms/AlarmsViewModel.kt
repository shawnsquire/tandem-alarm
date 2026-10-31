package me.shawnsquire.tandem.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.isOneShot
import me.shawnsquire.tandem.schedule.nextOccurrence
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
)

data class AlarmsUiState(
    val loaded: Boolean = false,
    val rows: List<AlarmRowState> = emptyList(),
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** One-off events for the screen. */
sealed interface AlarmsEvent {
    /** Show "Alarm deleted · Undo"; report the outcome via [AlarmsViewModel.undoDelete] or [AlarmsViewModel.onDeleteSnackbarDismissed]. */
    data class AlarmDeleted(val alarmId: String) : AlarmsEvent
}

class AlarmsViewModel(
    private val repository: AlarmRepository,
    private val edits: AlarmEdits,
    private val clock: Clock,
    /** Must emit once immediately, then on each refresh (see tickerFlow). */
    ticker: Flow<Unit>,
    private val onAlarmPurged: (Alarm) -> Unit,
) : ViewModel() {

    val state: StateFlow<AlarmsUiState> =
        combine(repository.alarms, ticker) { alarms, _ -> buildState(alarms) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmsUiState(zone = clock.zone))

    private val eventChannel = Channel<AlarmsEvent>(Channel.BUFFERED)
    val events: Flow<AlarmsEvent> = eventChannel.receiveAsFlow()

    /** The deleted alarm whose snackbar is showing; its media is purged only if undo never happens. */
    private var pendingDelete: Alarm? = null

    private fun buildState(alarms: List<Alarm>): AlarmsUiState {
        val now = clock.instant()
        val zone = clock.zone
        val rows = alarms
            .sortedWith(compareBy<Alarm>({ it.hour }, { it.minute }, { it.label.lowercase() }, { it.id }))
            .map { a ->
                val next = nextOccurrence(a, now, zone)
                val skipped = a.enabled && a.skippedOccurrenceAt?.let { it > now } == true
                AlarmRowState(
                    id = a.id, hour = a.hour, minute = a.minute, label = a.label,
                    daysText = formatDays(a.days), enabled = a.enabled,
                    nextRing = next,
                    ringsInText = next?.let { formatRingsIn(Duration.between(now, it)) },
                    skipped = skipped, oneShot = a.isOneShot(),
                )
            }
        return AlarmsUiState(loaded = true, rows = rows, zone = zone)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { edits.setEnabled(id, enabled) }
    }

    /** Repeating alarms pin the skip to the occurrence due right now; a one-shot is simply turned off. */
    fun skipNext(id: String) {
        viewModelScope.launch {
            val alarm = repository.get(id) ?: return@launch
            if (!alarm.enabled) return@launch
            if (alarm.isOneShot()) {
                edits.setEnabled(id, false)
            } else {
                val next = nextOccurrence(alarm, clock.instant(), clock.zone) ?: return@launch
                edits.setSkip(id, next)
            }
        }
    }

    fun unskip(id: String) {
        viewModelScope.launch { edits.setSkip(id, null) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val alarm = repository.get(id) ?: return@launch
            // A newer delete replaces the one whose snackbar is going away; its undo window is over.
            pendingDelete?.let(onAlarmPurged)
            pendingDelete = alarm
            edits.delete(id)
            eventChannel.send(AlarmsEvent.AlarmDeleted(id))
        }
    }

    /** Restores the pending delete only if it is still [alarmId]; a stale Undo is a no-op. */
    fun undoDelete(alarmId: String) {
        val alarm = pendingDelete?.takeIf { it.id == alarmId } ?: return
        pendingDelete = null
        viewModelScope.launch { edits.upsert(alarm) }
    }

    /** The snackbar for [alarmId] went away without undo: the alarm is gone for good, so purge its media. */
    fun onDeleteSnackbarDismissed(alarmId: String) {
        val alarm = pendingDelete?.takeIf { it.id == alarmId } ?: return
        pendingDelete = null
        onAlarmPurged(alarm)
    }

    override fun onCleared() {
        pendingDelete?.let(onAlarmPurged)
        pendingDelete = null
    }
}
