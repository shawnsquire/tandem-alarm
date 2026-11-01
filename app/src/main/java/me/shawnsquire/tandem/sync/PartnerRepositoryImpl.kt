package me.shawnsquire.tandem.sync

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Mirrors the partner's device doc (`households/{h}/devices/{partnerUid}`). Emits null when unpaired,
 * before the partner has published, and on any listener error (e.g. PERMISSION_DENIED after unpair).
 */
class PartnerRepositoryImpl internal constructor(
    household: Flow<Household?>,
    docSource: (Household) -> Flow<Map<String, Any?>?>,
) : PartnerRepository {

    constructor(household: Flow<Household?>, db: FirebaseFirestore) : this(household, firestoreDocSource(db))

    /** Stub until L4: always null, so a caller that needs a server-confirmed read rings (I1). */
    override suspend fun readFromServer(): PartnerState? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override val partner: Flow<PartnerState?> = household
        .flatMapLatest { h ->
            if (h == null) {
                flowOf(null)
            } else {
                flow { emitAll(docSource(h).map { it?.let(::partnerStateFromDoc) }) }
                    .catch { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        emit(null)
                    }
            }
        }
        .distinctUntilChanged()
}

private fun firestoreDocSource(db: FirebaseFirestore): (Household) -> Flow<Map<String, Any?>?> = { h ->
    callbackFlow {
        val ref = db.collection("households").document(h.id).collection("devices").document(h.partnerUid)
        val reg = ref.addSnapshotListener { snap, err ->
            trySend(if (err != null || snap == null || !snap.exists()) null else snap.data)
            if (err != null) close()
        }
        awaitClose { reg.remove() }
    }
}
