package me.shawnsquire.tandem.ui.partner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.sync.CommandSender
import me.shawnsquire.tandem.sync.PairingException
import me.shawnsquire.tandem.sync.PairingRepository
import me.shawnsquire.tandem.sync.PairingStatus
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.sync.PartnerAlarm
import me.shawnsquire.tandem.sync.PartnerState
import me.shawnsquire.tandem.ui.formatDays
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Live status of a command the user sent from this screen. */
sealed interface CommandUiStatus {
    data object Pending : CommandUiStatus
    data object Applied : CommandUiStatus
    data class Rejected(val reason: String) : CommandUiStatus
    /** The status stream ended while still pending, so the outcome is not known. */
    data object Unknown : CommandUiStatus
}

enum class AlarmChip { ON, OFF, SKIPPED_NEXT }

enum class PartnerAction { SKIP_NEXT, UNSKIP, TURN_OFF, TURN_ON }

data class PartnerAlarmRow(
    val id: String,
    val label: String,
    val hour: Int,
    val minute: Int,
    val daysText: String,
    val chip: AlarmChip,
    val actions: List<PartnerAction>,
    val commandStatus: CommandUiStatus?,
    /** The action whose command is still in flight; only its button is disabled. */
    val pendingAction: PartnerAction? = null,
)

data class RingingBanner(
    val partnerName: String,
    val alarmId: String,
    val ringId: String,
    /** Local wall-clock time the snooze ends, or null while the alarm is actually ringing. */
    val snoozedHour: Int?,
    val snoozedMinute: Int?,
    val stopStatus: CommandUiStatus?,
)

data class PartnerUiState(
    /** True until the first real emission, so a paired user never sees the unpaired UI for a frame. */
    val loading: Boolean = true,
    val paired: Boolean = false,
    // Unpaired.
    val code: String? = null,
    val codeLoading: Boolean = false,
    val joinInput: String = "",
    val joining: Boolean = false,
    val error: String? = null,
    // Paired.
    val partnerName: String = FALLBACK_NAME,
    /** False until the first partner snapshot arrives. */
    val partnerLoaded: Boolean = false,
    /** "3 min ago", "just now"; null when the partner has never been seen. */
    val lastSeenText: String? = null,
    val asleepWarning: Boolean = false,
    val ringing: RingingBanner? = null,
    val alarms: List<PartnerAlarmRow> = emptyList(),
)

const val FALLBACK_NAME = "Your partner"

/** Beyond this the partner's phone is probably dozing; the heartbeat normally lands every ~15 min. */
val ASLEEP_AFTER: Duration = Duration.ofMinutes(60)

fun formatLastSeen(age: Duration): String {
    val minutes = age.toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

internal fun partnerAlarmRow(
    a: PartnerAlarm,
    now: Instant,
    status: CommandUiStatus?,
    sentAction: PartnerAction? = null,
): PartnerAlarmRow {
    val skipped = a.enabled && a.skippedOccurrenceAt?.let { it > now } == true
    val chip = when {
        !a.enabled -> AlarmChip.OFF
        skipped -> AlarmChip.SKIPPED_NEXT
        else -> AlarmChip.ON
    }
    val actions = when {
        !a.enabled -> listOf(PartnerAction.TURN_ON)
        // Pinned commands need the occurrence the partner saw; without one, offer nothing.
        skipped -> listOfNotNull(
            PartnerAction.UNSKIP,
            PartnerAction.TURN_OFF.takeIf { a.nextOccurrenceAt != null },
        )
        a.nextOccurrenceAt != null -> listOf(PartnerAction.SKIP_NEXT, PartnerAction.TURN_OFF)
        else -> emptyList()
    }
    return PartnerAlarmRow(
        id = a.id, label = a.label, hour = a.hour, minute = a.minute, daysText = formatDays(a.days),
        chip = chip, actions = actions, commandStatus = status,
        pendingAction = sentAction.takeIf { status == CommandUiStatus.Pending },
    )
}

private data class UnpairedLocal(
    val code: String? = null,
    val codeLoading: Boolean = false,
    val joinInput: String = "",
    val joining: Boolean = false,
    val error: String? = null,
)

private const val GENERIC_ERROR = "Something went wrong. Try again."

class PartnerViewModel(
    private val pairing: PairingRepository,
    partnerRepository: PartnerRepository,
    private val sender: CommandSender,
    private val clock: Clock,
    /** Must emit once immediately, then on each refresh (see tickerFlow). */
    ticker: Flow<Unit>,
) : ViewModel() {

    private val local = MutableStateFlow(UnpairedLocal())
    private val alarmStatuses = MutableStateFlow<Map<String, CommandUiStatus>>(emptyMap())
    private val stopStatuses = MutableStateFlow<Map<String, CommandUiStatus>>(emptyMap())
    private val jobs = HashMap<String, Job>()

    /** Which action each alarm's latest command carried; written before the Pending status is published. */
    private val sentActions = MutableStateFlow<Map<String, PartnerAction>>(emptyMap())

    private val partner: StateFlow<PartnerState?> =
        partnerRepository.partner.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<PartnerUiState> = combine(
        pairing.status, partner, local, alarmStatuses, stopStatuses, sentActions, ticker,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        build(
            values[0] as PairingStatus, values[1] as PartnerState?, values[2] as UnpairedLocal,
            values[3] as Map<String, CommandUiStatus>, values[4] as Map<String, CommandUiStatus>,
            values[5] as Map<String, PartnerAction>,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PartnerUiState())

    init {
        // Once paired the code has served its purpose; do not show a stale one after a later unpair.
        viewModelScope.launch {
            pairing.status.collect { s ->
                if (s is PairingStatus.Paired) local.update { UnpairedLocal() }
            }
        }
    }

    private fun build(
        status: PairingStatus,
        partner: PartnerState?,
        local: UnpairedLocal,
        alarmStatuses: Map<String, CommandUiStatus>,
        stopStatuses: Map<String, CommandUiStatus>,
        sentActions: Map<String, PartnerAction>,
    ): PartnerUiState {
        if (status !is PairingStatus.Paired) {
            return PartnerUiState(
                loading = false, code = local.code, codeLoading = local.codeLoading, joinInput = local.joinInput,
                joining = local.joining, error = local.error,
            )
        }
        val now = clock.instant()
        val name = status.partnerName?.takeIf { it.isNotBlank() }
            ?: partner?.displayName?.takeIf { it.isNotBlank() }
            ?: FALLBACK_NAME
        val lastSeen = partner?.lastSeen
        val age = lastSeen?.let { Duration.between(it, now) }
        val ringing = partner?.ringing?.let { r ->
            val snooze = r.snoozedUntil?.atZone(clock.zone)
            RingingBanner(
                partnerName = name, alarmId = r.alarmId, ringId = r.ringId,
                snoozedHour = snooze?.hour, snoozedMinute = snooze?.minute,
                stopStatus = stopStatuses[r.ringId],
            )
        }
        val rows = partner?.alarms.orEmpty()
            .sortedWith(compareBy<PartnerAlarm>({ it.hour }, { it.minute }, { it.label.lowercase() }, { it.id }))
            .map { a -> partnerAlarmRow(a, now, alarmStatuses[a.id], sentActions[a.id]) }
        return PartnerUiState(
            loading = false, paired = true, partnerName = name, partnerLoaded = partner != null,
            lastSeenText = age?.let { formatLastSeen(it) },
            asleepWarning = age != null && age > ASLEEP_AFTER,
            ringing = ringing, alarms = rows,
        )
    }

    // Pairing.

    fun showCode() {
        if (local.value.codeLoading) return
        local.update { it.copy(codeLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val code = pairing.createCode()
                local.update { it.copy(code = code, codeLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(codeLoading = false, error = errorText(e)) }
            }
        }
    }

    fun onJoinInput(text: String) {
        local.update { it.copy(joinInput = text.trim().uppercase().take(6)) }
    }

    fun join() {
        val current = local.value
        val code = current.joinInput.trim().uppercase()
        if (code.isEmpty() || current.joining) return
        local.update { it.copy(joining = true, error = null) }
        viewModelScope.launch {
            try {
                pairing.join(code)
                local.update { it.copy(joining = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(joining = false, error = errorText(e)) }
            }
        }
    }

    private fun errorText(e: Exception): String =
        if (e is PairingException) e.message?.takeIf { it.isNotBlank() } ?: GENERIC_ERROR else GENERIC_ERROR

    // Commands.

    fun skipNext(alarmId: String) {
        val at = alarm(alarmId)?.nextOccurrenceAt ?: return
        sendForAlarm(alarmId, CommandType.SKIP_NEXT, at)
    }

    fun unskip(alarmId: String) {
        val at = alarm(alarmId)?.skippedOccurrenceAt ?: return
        sendForAlarm(alarmId, CommandType.UNSKIP, at)
    }

    fun turnOff(alarmId: String) {
        val at = alarm(alarmId)?.nextOccurrenceAt ?: return
        sendForAlarm(alarmId, CommandType.DISABLE, at)
    }

    fun turnOn(alarmId: String) {
        if (alarm(alarmId) == null) return
        sendForAlarm(alarmId, CommandType.ENABLE, null)
    }

    fun stopRinging() {
        val ring = partner.value?.ringing ?: return
        run(stopStatuses, ring.ringId, CommandType.STOP_RINGING, ring.alarmId, null, ring.ringId)
    }

    private fun alarm(id: String): PartnerAlarm? = partner.value?.alarms?.firstOrNull { it.id == id }

    private fun sendForAlarm(alarmId: String, type: CommandType, occurrenceAt: Instant?) =
        run(alarmStatuses, alarmId, type, alarmId, occurrenceAt, null) {
            val action = when (type) {
                CommandType.SKIP_NEXT -> PartnerAction.SKIP_NEXT
                CommandType.UNSKIP -> PartnerAction.UNSKIP
                CommandType.DISABLE -> PartnerAction.TURN_OFF
                else -> PartnerAction.TURN_ON
            }
            sentActions.update { it + (alarmId to action) }
        }

    /** One tap = one [CommandSender.send]; a newer command on the same target replaces the old collection. */
    private fun run(
        statuses: MutableStateFlow<Map<String, CommandUiStatus>>,
        key: String,
        type: CommandType,
        alarmId: String,
        occurrenceAt: Instant?,
        ringId: String?,
        beforePending: () -> Unit = {},
    ) {
        val jobKey = (if (statuses === stopStatuses) "stop:" else "alarm:") + key
        jobs.remove(jobKey)?.cancel()
        val flow = sender.send(type, alarmId, occurrenceAt, ringId)
        beforePending()
        statuses.update { it + (key to CommandUiStatus.Pending) }
        jobs[jobKey] = viewModelScope.launch {
            var last: CommandStatus? = null
            try {
                flow.collect { (s, reason) ->
                    last = s
                    val ui = when (s) {
                        CommandStatus.PENDING -> CommandUiStatus.Pending
                        CommandStatus.APPLIED -> CommandUiStatus.Applied
                        CommandStatus.REJECTED -> CommandUiStatus.Rejected(reason ?: "Rejected")
                    }
                    statuses.update { it + (key to ui) }
                }
                if (last != CommandStatus.APPLIED && last != CommandStatus.REJECTED) {
                    statuses.update { it + (key to CommandUiStatus.Unknown) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statuses.update { it + (key to CommandUiStatus.Unknown) }
            }
        }
    }
}
