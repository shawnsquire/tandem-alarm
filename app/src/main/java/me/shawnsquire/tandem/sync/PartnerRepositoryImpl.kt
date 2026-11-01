package me.shawnsquire.tandem.sync

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Mirrors the partner's device doc (`households/{h}/devices/{partnerUid}`). Emits null when unpaired,
 * before the partner has published, and on any listener error (e.g. PERMISSION_DENIED after unpair),
 * which is then retried with backoff; the null is "unknown", not proof of an unpair.
 */
class PartnerRepositoryImpl internal constructor(
    private val household: Flow<Household?>,
    docSource: (Household) -> Flow<Map<String, Any?>?>,
    private val serverRead: suspend (Household) -> ServerDoc?,
) : PartnerRepository {

    internal constructor(
        household: Flow<Household?>,
        docSource: (Household) -> Flow<Map<String, Any?>?>,
    ) : this(household, docSource, { null })

    constructor(household: Flow<Household?>, db: FirebaseFirestore) :
        this(household, firestoreDocSource(db), firestoreServerRead(db))

    /**
     * One `Source.SERVER` read of the partner's doc. Null on any failure, a missing doc, a snapshot
     * that came from the cache, or an unknown household, so the caller rings (I1). Never throws
     * except for the caller's own cancellation; the caller's timeout bounds the read itself.
     */
    override suspend fun readFromServer(): PartnerState? = try {
        val h = withTimeoutOrNull(HOUSEHOLD_WAIT) { household.first() }
        val doc = h?.let { serverRead(it) }
        val data = doc?.takeUnless { it.fromCache }?.data
        data?.let(::partnerStateFromDoc)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "server read failed", e)
        null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val partner: Flow<PartnerState?> = household
        .flatMapLatest { h ->
            if (h == null) {
                flowOf(null)
            } else {
                flow { emitAll(docSource(h).map { it?.let(::partnerStateFromDoc) }) }
                    .resubscribeOnError(null)
            }
        }
        .distinctUntilChanged()
}

/** A one-shot document read: [data] is null for a missing doc; [fromCache] is the snapshot's metadata. */
internal data class ServerDoc(val data: Map<String, Any?>?, val fromCache: Boolean)

private const val TAG = "PartnerRepository"
private val HOUSEHOLD_WAIT = 1.seconds

private fun firestoreServerRead(db: FirebaseFirestore): suspend (Household) -> ServerDoc? = { h ->
    val snap = db.collection("households").document(h.id).collection("devices").document(h.partnerUid)
        .get(Source.SERVER).await()
    ServerDoc(if (snap.exists()) snap.data else null, snap.metadata.isFromCache)
}

private fun firestoreDocSource(db: FirebaseFirestore): (Household) -> Flow<Map<String, Any?>?> = { h ->
    callbackFlow {
        val ref = db.collection("households").document(h.id).collection("devices").document(h.partnerUid)
        val reg = ref.addSnapshotListener { snap, err ->
            if (err != null) {
                close(err)
            } else {
                trySend(if (snap == null || !snap.exists()) null else snap.data)
            }
        }
        awaitClose { reg.remove() }
    }
}
