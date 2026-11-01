package me.shawnsquire.tandem.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import me.shawnsquire.tandem.alarm.AlarmScheduler
import me.shawnsquire.tandem.alarm.RingingController
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.sync.CommandApplier
import me.shawnsquire.tandem.sync.CommandSender
import me.shawnsquire.tandem.sync.HealthSignals
import me.shawnsquire.tandem.sync.PairingRepository
import me.shawnsquire.tandem.sync.PairingStatus
import me.shawnsquire.tandem.sync.PartnerAlarm
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.sync.PartnerState
import me.shawnsquire.tandem.sync.PreFireCheck
import me.shawnsquire.tandem.sync.StatePublisher
import java.time.DayOfWeek
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration

// In-memory placeholders for previews and tests. None of these call the U1 scheduling stubs.

val SAMPLE_ALARMS: List<Alarm> = listOf(
    Alarm(
        id = "alarm-work", label = "Work", hour = 6, minute = 30,
        days = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        ),
        enabled = true, skippedOccurrenceAt = null, ringtoneUri = null, wallpaperPath = null,
    ),
    Alarm(
        id = "alarm-weekend", label = "Weekend", hour = 8, minute = 0,
        days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        enabled = true, skippedOccurrenceAt = null, ringtoneUri = null, wallpaperPath = null,
    ),
)

class FakeAlarmRepository(initial: List<Alarm> = SAMPLE_ALARMS) : AlarmRepository {
    private val state = MutableStateFlow(initial)
    override val alarms: Flow<List<Alarm>> = state

    override suspend fun get(id: String): Alarm? = state.value.firstOrNull { it.id == id }

    override suspend fun upsert(alarm: Alarm) = state.update { list ->
        if (list.any { it.id == alarm.id }) list.map { if (it.id == alarm.id) alarm else it }
        else list + alarm
    }

    override suspend fun delete(id: String) = state.update { list -> list.filterNot { it.id == id } }

    override suspend fun setEnabled(id: String, enabled: Boolean) = state.update { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    override suspend fun setSkip(id: String, occurrence: Instant?) = state.update { list ->
        list.map { if (it.id == id) it.copy(skippedOccurrenceAt = occurrence) else it }
    }
}

class FakeRingStateStore(initial: RingState? = null) : RingStateStore {
    private val flow = MutableStateFlow(initial)
    override val state: Flow<RingState?> = flow
    override suspend fun set(state: RingState?) { flow.value = state }
}

class FakeSettingsRepository : SettingsRepository {
    private val snooze = MutableStateFlow(10)
    private val wallpaper = MutableStateFlow<String?>(null)
    private val ringtone = MutableStateFlow<String?>(null)
    override val snoozeMinutes: Flow<Int> = snooze
    override val defaultWallpaperPath: Flow<String?> = wallpaper
    override val defaultRingtoneUri: Flow<String?> = ringtone
    override suspend fun setSnoozeMinutes(m: Int) { snooze.value = m }
    override suspend fun setDefaultWallpaperPath(p: String?) { wallpaper.value = p }
    override suspend fun setDefaultRingtoneUri(u: String?) { ringtone.value = u }
}

class FakeAlarmScheduler : AlarmScheduler {
    var rescheduleCount = 0
        private set
    override suspend fun rescheduleAll() { rescheduleCount++ }
}

class FakeRingingController : RingingController {
    val dismissed = mutableListOf<String>()
    var snoozeCount = 0
        private set
    override suspend fun dismiss(ringId: String) { dismissed += ringId }
    override suspend fun snooze() { snoozeCount++ }

    /** When set, [writeUnlessRinging] refuses (as if that ring had started) instead of writing. */
    var ringStartedAt: Instant? = null
    var ringingAlarmId: String? = null
    override suspend fun writeUnlessRinging(alarmId: String, occurrenceAt: Instant?, write: suspend () -> Unit): Boolean {
        val started = ringStartedAt
        if (alarmId == ringingAlarmId && started != null && (occurrenceAt == null || started >= occurrenceAt)) return false
        write()
        return true
    }
}

class FakePreFireCheck(var result: Boolean = true) : PreFireCheck {
    override suspend fun pullAndApply(timeout: Duration): Boolean = result
}

class FakeCommandApplier : CommandApplier {
    val applied = mutableListOf<Command>()
    override suspend fun apply(cmd: Command) { applied += cmd }
}

class FakeCommandSender : CommandSender {
    override fun send(
        type: CommandType,
        alarmId: String,
        occurrenceAt: Instant?,
        ringId: String?,
    ): Flow<Pair<CommandStatus, String?>> =
        flowOf(CommandStatus.PENDING to null, CommandStatus.APPLIED to null)
}

class FakePartnerRepository(initial: PartnerState? = sampleState()) : PartnerRepository {
    private val flow = MutableStateFlow(initial)
    override val partner: Flow<PartnerState?> = flow
    fun set(state: PartnerState?) { flow.value = state }

    companion object {
        fun sampleState(): PartnerState {
            val now = Instant.now()
            return PartnerState(
                displayName = "Alex",
                alarms = listOf(
                    PartnerAlarm(
                        id = "p-1", label = "Gym", hour = 5, minute = 45,
                        days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                        enabled = true,
                        nextOccurrenceAt = now.plus(8, ChronoUnit.HOURS),
                        skippedOccurrenceAt = null,
                    ),
                    PartnerAlarm(
                        id = "p-2", label = "Brunch", hour = 9, minute = 30,
                        days = setOf(DayOfWeek.SUNDAY),
                        enabled = false,
                        nextOccurrenceAt = null,
                        skippedOccurrenceAt = null,
                    ),
                ),
                ringing = null,
                lastSeen = now.minus(5, ChronoUnit.MINUTES),
            )
        }
    }
}

class FakePairingRepository : PairingRepository {
    private val flow = MutableStateFlow<PairingStatus>(PairingStatus.Unpaired)
    override val status: Flow<PairingStatus> = flow
    override suspend fun createCode(): String = "ABC123"
    override suspend fun join(code: String) {
        flow.value = PairingStatus.Paired(householdId = "household-1", partnerName = "Alex")
    }
    override suspend fun unpair() { flow.value = PairingStatus.Unpaired }
}

class FakeStatePublisher : StatePublisher {
    override fun start() = Unit
}

class FakeHealthSignals : HealthSignals {
    override val lastFcmAt: Flow<Instant?> = MutableStateFlow(null)
    override val fcmTokenPresent: Flow<Boolean> = MutableStateFlow(true)
}
