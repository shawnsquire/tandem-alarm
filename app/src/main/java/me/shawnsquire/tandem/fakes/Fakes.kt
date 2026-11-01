package me.shawnsquire.tandem.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.shawnsquire.tandem.alarm.AlarmScheduler
import me.shawnsquire.tandem.alarm.MirrorKind
import me.shawnsquire.tandem.alarm.chooseWallpaperPaths
import me.shawnsquire.tandem.alarm.RingingController
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.media.LinkedMediaCache
import me.shawnsquire.tandem.media.RingMedia
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingOrigin
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.sync.CommandApplier
import me.shawnsquire.tandem.sync.AlarmMedia
import me.shawnsquire.tandem.sync.CommandSender
import me.shawnsquire.tandem.sync.HealthSignals
import me.shawnsquire.tandem.sync.LinkRepository
import me.shawnsquire.tandem.sync.LinkResult
import me.shawnsquire.tandem.sync.LinkSync
import me.shawnsquire.tandem.sync.MediaPublisher
import me.shawnsquire.tandem.sync.MediaRef
import me.shawnsquire.tandem.sync.PairingRepository
import me.shawnsquire.tandem.sync.PairingStatus
import me.shawnsquire.tandem.sync.PartnerAlarm
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.sync.PartnerState
import me.shawnsquire.tandem.sync.PreFireCheck
import me.shawnsquire.tandem.sync.PublishedLink
import me.shawnsquire.tandem.sync.PublishedMedia
import me.shawnsquire.tandem.sync.RingMirror
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
    override val ownAlarms: Flow<List<Alarm>> = state.map { list -> list.filter { it.link == null } }
    override val linkedAlarms: Flow<List<Alarm>> = state.map { list -> list.filter { it.link != null } }

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

    override suspend fun setSoloSkip(id: String, occurrence: Instant?) = state.update { list ->
        list.map { if (it.id == id) it.copy(soloSkippedOccurrenceAt = occurrence) else it }
    }

    override suspend fun upsertLinked(alarm: Alarm) {
        val incoming = alarm.link ?: throw IllegalArgumentException("upsertLinked needs a linked alarm")
        state.update { list ->
            val stored = list.firstOrNull { it.id == alarm.id }
            val storedLink = stored?.link
            if (stored == null || storedLink == null) {
                if (stored == null) list + alarm else list.map { if (it.id == alarm.id) alarm else it }
            } else {
                val merged = stored.copy(
                    label = alarm.label,
                    hour = alarm.hour,
                    minute = alarm.minute,
                    days = alarm.days,
                    enabled = alarm.enabled,
                    link = storedLink.copy(ownerSkippedOccurrenceAt = incoming.ownerSkippedOccurrenceAt),
                )
                list.map { if (it.id == alarm.id) merged else it }
            }
        }
    }

    override suspend fun updateLink(id: String, transform: (AlarmLink) -> AlarmLink): Boolean {
        var found = false
        state.update { list ->
            list.map {
                val link = it.link
                if (it.id == id && link != null) {
                    found = true
                    it.copy(link = transform(link))
                } else it
            }
        }
        return found
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
    val dismissOrigins = mutableListOf<RingOrigin>()
    var snoozeCount = 0
        private set
    val snoozeOrigins = mutableListOf<RingOrigin>()
    val snoozeUntils = mutableListOf<Instant?>()
    data class OccurrenceCall(val alarmId: String, val occurrenceAt: Instant, val origin: RingOrigin)
    data class SnoozeOccurrenceCall(val alarmId: String, val occurrenceAt: Instant, val until: Instant, val origin: RingOrigin)
    val dismissedOccurrences = mutableListOf<OccurrenceCall>()
    val snoozedOccurrences = mutableListOf<SnoozeOccurrenceCall>()
    override suspend fun dismiss(ringId: String, origin: RingOrigin) { dismissed += ringId; dismissOrigins += origin }
    override suspend fun snooze(origin: RingOrigin, until: Instant?) {
        snoozeCount++; snoozeOrigins += origin; snoozeUntils += until
    }
    override suspend fun dismissOccurrence(alarmId: String, occurrenceAt: Instant, origin: RingOrigin) {
        dismissedOccurrences += OccurrenceCall(alarmId, occurrenceAt, origin)
    }
    override suspend fun snoozeOccurrence(alarmId: String, occurrenceAt: Instant, until: Instant, origin: RingOrigin) {
        snoozedOccurrences += SnoozeOccurrenceCall(alarmId, occurrenceAt, until, origin)
    }

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
    /** The [linkedOwnerAlarmId] of each call, in order (null for non-linked rows). */
    val linkedOwnerAlarmIds = mutableListOf<String?>()

    override suspend fun pullAndApply(timeout: Duration, linkedOwnerAlarmId: String?): Boolean {
        linkedOwnerAlarmIds += linkedOwnerAlarmId
        return result
    }
}

class FakeCommandApplier : CommandApplier {
    val applied = mutableListOf<Command>()
    override suspend fun apply(cmd: Command) { applied += cmd }
}

class FakeCommandSender : CommandSender {
    data class Sent(
        val type: CommandType,
        val alarmId: String,
        val occurrenceAt: Instant?,
        val ringId: String?,
        val snoozedUntil: Instant? = null,
    )

    val sent = mutableListOf<Sent>()

    override fun send(
        type: CommandType,
        alarmId: String,
        occurrenceAt: Instant?,
        ringId: String?,
        snoozedUntil: Instant?,
    ): Flow<Pair<CommandStatus, String?>> {
        sent += Sent(type, alarmId, occurrenceAt, ringId, snoozedUntil)
        return flowOf(CommandStatus.PENDING to null, CommandStatus.APPLIED to null)
    }
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

class FakeLinkRepository : LinkRepository {
    sealed interface Call {
        data class Join(val ownerAlarmId: String) : Call
        data class AcceptInvite(val commandId: String, val ownerAlarmId: String) : Call
        data class DeclineInvite(val commandId: String) : Call
        data class Leave(val alarmId: String) : Call
        data class SetMode(val alarmId: String, val mode: LinkMode) : Call
        data class SetOverrides(
            val alarmId: String,
            val label: String?,
            val ringtoneUri: String?,
            val wallpaperPath: String?,
        ) : Call
        data class SetJoinerEnabled(val alarmId: String, val enabled: Boolean) : Call
        data class SendBoth(val alarmId: String, val type: CommandType, val occurrenceAt: Instant?) : Call
        data class Invite(val alarmId: String) : Call
    }

    val calls = mutableListOf<Call>()

    /** Result of [join]; null derives `Joined("linked-<ownerAlarmId>")`. */
    var joinResult: LinkResult? = null

    /** Result of [acceptInvite]; null derives `Joined("linked-<ownerAlarmId>")`. */
    var acceptResult: LinkResult? = null

    override val openInvites = MutableStateFlow<Set<String>>(emptySet())
    override val links = MutableStateFlow<List<PublishedLink>>(emptyList())

    override suspend fun join(ownerAlarmId: String): LinkResult {
        calls += Call.Join(ownerAlarmId)
        return joinResult ?: LinkResult.Joined("linked-$ownerAlarmId")
    }

    override suspend fun acceptInvite(commandId: String, ownerAlarmId: String): LinkResult {
        calls += Call.AcceptInvite(commandId, ownerAlarmId)
        return acceptResult ?: LinkResult.Joined("linked-$ownerAlarmId")
    }

    override suspend fun declineInvite(commandId: String) { calls += Call.DeclineInvite(commandId) }
    override suspend fun leave(alarmId: String) { calls += Call.Leave(alarmId) }
    override suspend fun setMode(alarmId: String, mode: LinkMode) { calls += Call.SetMode(alarmId, mode) }

    override suspend fun setOverrides(alarmId: String, label: String?, ringtoneUri: String?, wallpaperPath: String?) {
        calls += Call.SetOverrides(alarmId, label, ringtoneUri, wallpaperPath)
    }

    override suspend fun setJoinerEnabled(alarmId: String, enabled: Boolean) {
        calls += Call.SetJoinerEnabled(alarmId, enabled)
    }

    override suspend fun sendBoth(alarmId: String, type: CommandType, occurrenceAt: Instant?) {
        calls += Call.SendBoth(alarmId, type, occurrenceAt)
    }

    override fun invite(alarmId: String): Flow<Pair<CommandStatus, String?>> {
        calls += Call.Invite(alarmId)
        return flowOf(CommandStatus.PENDING to null, CommandStatus.APPLIED to null)
    }
}

class FakeLinkSync : LinkSync {
    var started = 0
    val applied = mutableListOf<PartnerState>()
    var removedAll = 0

    override fun start() { started++ }
    override suspend fun apply(owner: PartnerState) { applied += owner }
    override suspend fun removeAll() { removedAll++ }
}

class FakeRingMirror : RingMirror {
    data class Local(val kind: MirrorKind, val members: List<RingMember>, val snoozedUntil: Instant?)

    val locals = mutableListOf<Local>()
    var flushes = 0

    override fun onLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?) {
        locals += Local(kind, members, snoozedUntil)
    }

    override suspend fun flushOutbox() { flushes++ }
}

class FakeMediaPublisher : MediaPublisher {
    var started = 0
    override val published = MutableStateFlow<PublishedMedia?>(null)
    override fun start() { started++ }
}

/** Reproduces today's ring choice (no owner media), so L2 can call it before L5 exists. */
class FakeLinkedMediaCache : LinkedMediaCache {
    data class Update(val ownerUid: String, val alarms: Map<String, AlarmMedia>, val defaultWallpaper: MediaRef?)

    val updates = mutableListOf<Update>()

    override fun update(ownerUid: String, alarms: Map<String, AlarmMedia>, defaultWallpaper: MediaRef?) {
        updates += Update(ownerUid, alarms, defaultWallpaper)
    }

    override fun pathFor(sha: String): String? = null
    override fun inUse(): Set<String> = emptySet()

    override suspend fun resolve(alarm: Alarm, joinerDefaultRingtone: String?, joinerDefaultWallpaper: String?) = RingMedia(
        label = alarm.link?.overrideLabel ?: alarm.label,
        soundCandidates = listOfNotNull(alarm.ringtoneUri, joinerDefaultRingtone),
        wallpaperPaths = chooseWallpaperPaths(alarm.wallpaperPath, joinerDefaultWallpaper),
    )
}
