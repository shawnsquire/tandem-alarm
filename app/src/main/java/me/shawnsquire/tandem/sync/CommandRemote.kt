package me.shawnsquire.tandem.sync

import android.util.Log
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import java.time.Instant

/** Seam over `households/{hid}/commands` so the applier and pre-fire check can be tested with fakes. */
interface CommandRemote {
    /** Creates households/{hid}/commands/{newId} and returns the id without waiting for the server (writes queue offline). */
    fun create(
        householdId: String,
        from: String,
        to: String,
        type: CommandType,
        alarmId: String,
        occurrenceAt: Instant?,
        ringId: String?,
        now: Instant,
    ): String

    /** (status, reason) updates for one command doc. */
    fun observe(householdId: String, commandId: String): Flow<Pair<CommandStatus, String?>>

    /** Current status of the doc (default source), or null if missing or unreadable. Never throws. */
    suspend fun status(householdId: String, commandId: String): CommandStatus?

    /** Enqueues the owner's verdict (status + reason, reason only for REJECTED). Does not wait for the server; failures (e.g. already resolved -> PERMISSION_DENIED) are logged, never thrown. */
    fun resolve(householdId: String, commandId: String, status: CommandStatus, reason: String?)

    /** Pending commands with to == uid, read with Source.SERVER. Throws on failure. */
    suspend fun fetchPendingFromServer(householdId: String, uid: String): List<Command>

    /** Live pending commands with to == uid. Errors (incl. PERMISSION_DENIED) end the flow quietly; never thrown to collectors. */
    fun pendingUpdates(householdId: String, uid: String): Flow<List<Command>>
}

class FirestoreCommandRemote(private val db: FirebaseFirestore) : CommandRemote {
    private fun commands(hid: String): CollectionReference =
        db.collection("households").document(hid).collection("commands")

    override fun create(
        householdId: String,
        from: String,
        to: String,
        type: CommandType,
        alarmId: String,
        occurrenceAt: Instant?,
        ringId: String?,
        now: Instant,
    ): String {
        val ref = commands(householdId).document()
        ref.set(commandCreateMap(from, to, type, alarmId, occurrenceAt, ringId, now))
            .addOnFailureListener { Log.w(TAG, "create ${ref.id} failed", it) }
        return ref.id
    }

    override fun observe(householdId: String, commandId: String): Flow<Pair<CommandStatus, String?>> = callbackFlow {
        val reg = commands(householdId).document(commandId).addSnapshotListener { snap, err ->
            if (err != null) {
                close()
                return@addSnapshotListener
            }
            val status = snap?.getString("status")?.let(::commandStatusFromString) ?: return@addSnapshotListener
            trySend(status to snap.getString("reason"))
        }
        awaitClose { reg.remove() }
    }

    override suspend fun status(householdId: String, commandId: String): CommandStatus? =
        withTimeoutOrNull(3.seconds) {
            try {
                commands(householdId).document(commandId).get().await()
                    .getString("status")?.let(::commandStatusFromString)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    override fun resolve(householdId: String, commandId: String, status: CommandStatus, reason: String?) {
        val update = buildMap<String, Any> {
            put("status", commandStatusToString(status))
            if (reason != null) put("reason", reason)
        }
        commands(householdId).document(commandId).update(update)
            .addOnFailureListener { Log.w(TAG, "resolve $commandId failed", it) }
    }

    override suspend fun fetchPendingFromServer(householdId: String, uid: String): List<Command> =
        pendingQuery(householdId, uid).get(Source.SERVER).await().documents
            .mapNotNull { d -> d.data?.let { commandFromDoc(d.id, it) } }

    override fun pendingUpdates(householdId: String, uid: String): Flow<List<Command>> = callbackFlow {
        val reg = pendingQuery(householdId, uid).addSnapshotListener { snap, err ->
            if (err != null) {
                close()
                return@addSnapshotListener
            }
            if (snap != null) {
                trySend(snap.documents.mapNotNull { d -> d.data?.let { commandFromDoc(d.id, it) } })
            }
        }
        awaitClose { reg.remove() }
    }

    private fun pendingQuery(hid: String, uid: String) =
        commands(hid).whereEqualTo("to", uid).whereEqualTo("status", commandStatusToString(CommandStatus.PENDING))

    private companion object {
        const val TAG = "CommandRemote"
    }
}
