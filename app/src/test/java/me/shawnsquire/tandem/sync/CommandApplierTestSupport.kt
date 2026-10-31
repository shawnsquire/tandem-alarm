package me.shawnsquire.tandem.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import java.time.Instant

class FakeCommandRemote(var status: CommandStatus? = CommandStatus.PENDING) : CommandRemote {
    data class Resolved(val hid: String, val id: String, val status: CommandStatus, val reason: String?)

    val resolved = mutableListOf<Resolved>()
    var statusCalls = 0

    override fun create(
        householdId: String, from: String, to: String, type: CommandType, alarmId: String,
        occurrenceAt: Instant?, ringId: String?, now: Instant,
    ): String = error("unused")

    override fun observe(householdId: String, commandId: String): Flow<Pair<CommandStatus, String?>> = emptyFlow()
    override suspend fun status(householdId: String, commandId: String): CommandStatus? {
        statusCalls++
        return status
    }
    override fun resolve(householdId: String, commandId: String, status: CommandStatus, reason: String?) {
        resolved += Resolved(householdId, commandId, status, reason)
    }

    override suspend fun fetchPendingFromServer(householdId: String, uid: String): List<Command> = emptyList()
    override fun pendingUpdates(householdId: String, uid: String): Flow<List<Command>> = emptyFlow()
}

class FakeCommandNotifier(var failure: Throwable? = null) : CommandNotifier {
    data class Call(val cmd: Command, val alarm: Alarm?, val partnerName: String, val turnedOff: Boolean)

    val calls = mutableListOf<Call>()

    override fun notifyApplied(cmd: Command, alarm: Alarm?, partnerName: String, turnedOff: Boolean) {
        calls += Call(cmd, alarm, partnerName, turnedOff)
        failure?.let { throw it }
    }
}
