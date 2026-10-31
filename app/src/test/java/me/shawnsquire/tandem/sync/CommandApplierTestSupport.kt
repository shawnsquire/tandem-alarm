package me.shawnsquire.tandem.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import java.time.Instant

class FakeCommandRemote(var status: CommandStatus? = CommandStatus.PENDING) : CommandRemote {
    data class Resolved(val hid: String, val id: String, val status: CommandStatus, val reason: String?)

    data class Created(
        val hid: String, val from: String, val to: String, val type: CommandType, val alarmId: String,
        val occurrenceAt: Instant?, val ringId: String?, val now: Instant,
    )

    val resolved = mutableListOf<Resolved>()
    val created = mutableListOf<Created>()
    var statusCalls = 0

    /** When set, [status] suspends in it (and returns its result) instead of returning [status] directly. */
    var statusBlock: (suspend () -> CommandStatus?)? = null

    /** Id returned by [create]. */
    var createdId = "c1"

    /** When set, [create] throws it instead of recording. */
    var createFailure: Throwable? = null

    private val updates = mutableMapOf<String, MutableSharedFlow<Pair<CommandStatus, String?>>>()

    /** Drives [observe] for [commandId]; replays the latest so a late collector still sees it. */
    fun updatesFor(commandId: String): MutableSharedFlow<Pair<CommandStatus, String?>> =
        updates.getOrPut(commandId) { MutableSharedFlow(replay = 1) }

    override fun create(
        householdId: String, from: String, to: String, type: CommandType, alarmId: String,
        occurrenceAt: Instant?, ringId: String?, now: Instant,
    ): String {
        createFailure?.let { throw it }
        created += Created(householdId, from, to, type, alarmId, occurrenceAt, ringId, now)
        return createdId
    }

    override fun observe(householdId: String, commandId: String): Flow<Pair<CommandStatus, String?>> =
        updatesFor(commandId)
    override suspend fun status(householdId: String, commandId: String): CommandStatus? {
        statusCalls++
        statusBlock?.let { return it() }
        return status
    }
    override fun resolve(householdId: String, commandId: String, status: CommandStatus, reason: String?) {
        resolved += Resolved(householdId, commandId, status, reason)
    }

    /** Result of [fetchPendingFromServer]; [fetchBlock] (if set) runs instead and may suspend or throw. */
    var fetchResult: List<Command> = emptyList()
    var fetchBlock: (suspend () -> List<Command>)? = null
    var fetchCalls = 0

    override suspend fun fetchPendingFromServer(householdId: String, uid: String): List<Command> {
        fetchCalls++
        return fetchBlock?.invoke() ?: fetchResult
    }

    /** Drives [pendingUpdates]; each collection counts as one subscription until it completes or is cancelled. */
    val pending = MutableSharedFlow<List<Command>>(extraBufferCapacity = 16)

    /** When true, [pendingUpdates] completes right after subscribing (like a snapshot error). */
    var pendingCompletes = false
    var pendingSubscriptions = 0
    var activePendingSubscriptions = 0

    override fun pendingUpdates(householdId: String, uid: String): Flow<List<Command>> =
        (if (pendingCompletes) kotlinx.coroutines.flow.emptyFlow() else pending)
            .onStart { pendingSubscriptions++; activePendingSubscriptions++ }
            .onCompletion { activePendingSubscriptions-- }
}

class FakeCommandNotifier(var failure: Throwable? = null) : CommandNotifier {
    data class Call(val cmd: Command, val alarm: Alarm?, val partnerName: String, val turnedOff: Boolean)

    val calls = mutableListOf<Call>()

    override fun notifyApplied(cmd: Command, alarm: Alarm?, partnerName: String, turnedOff: Boolean) {
        calls += Call(cmd, alarm, partnerName, turnedOff)
        failure?.let { throw it }
    }
}
