package me.shawnsquire.tandem.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/** A complete two-member household, from [myUid]'s point of view. */
data class Household(val id: String, val myUid: String, val partnerUid: String)

/**
 * Resolves the signed-in user's household. Emits null when signed out, unpaired, waiting for a
 * partner (one member), or when Firestore denies access (e.g. after unpair); never fails.
 */
class HouseholdSource internal constructor(
    uid: Flow<String?>,
    private val docs: (path: String) -> Flow<Map<String, Any?>?>,
    scope: CoroutineScope,
) {
    constructor(uid: Flow<String?>, db: FirebaseFirestore, scope: CoroutineScope) :
        this(uid, firestoreDocs(db), scope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val household: Flow<Household?> = uid
        .distinctUntilChanged()
        .flatMapLatest { u ->
            if (u == null) {
                flowOf(null)
            } else {
                docs("users/$u").resubscribeOnError(null)
                    .map { (it?.get("householdId") as? String)?.takeIf(String::isNotEmpty) }
                    .distinctUntilChanged()
                    .flatMapLatest { hid ->
                        if (hid == null) {
                            flowOf(null)
                        } else {
                            docs("households/$hid").resubscribeOnError(null)
                                .map { householdFrom(hid, u, it?.get("members")) }
                        }
                    }
            }
        }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
}

/**
 * Snapshot data of the doc at [path]; a missing doc is null. A listener error ends the flow with that
 * error, and [resubscribeOnError] emits null for the gap and listens again, so a dead listener
 * never stays dead. The null stands for "unknown", not "unpaired": only [householdOnServer] confirms that.
 */
private fun firestoreDocs(db: FirebaseFirestore): (String) -> Flow<Map<String, Any?>?> = { path ->
    callbackFlow<Map<String, Any?>?> {
        val reg = db.document(path).addSnapshotListener { snap, err ->
            if (err != null) {
                close(err)
            } else {
                trySend(if (snap == null || !snap.exists()) null else snap.data)
            }
        }
        awaitClose { reg.remove() }
    }
}

internal fun householdFrom(id: String, myUid: String, members: Any?): Household? {
    val list = (members as? List<*>)?.filterIsInstance<String>() ?: return null
    if (list.size != 2 || myUid !in list || list[0] == list[1]) return null
    return Household(id, myUid, list.first { it != myUid })
}

/** What the server says about the signed-in user's household (see [householdOnServer]). */
sealed interface HouseholdCheck {
    /** Confirmed by the server: no household, or one that is not a valid two-member household with this user. */
    data object Unpaired : HouseholdCheck

    data class Paired(val household: Household) : HouseholdCheck

    /** Offline, denied, timed out or answered from cache: says nothing about pairing. */
    data object Unknown : HouseholdCheck
}

/**
 * Reads `users/{uid}` and `households/{hid}` with Source.SERVER, never the cache. The household flow is
 * null on any listener error, so only this check may call a household gone. Any failure is
 * [HouseholdCheck.Unknown].
 */
suspend fun householdOnServer(db: FirebaseFirestore, uid: String): HouseholdCheck = try {
    val user = db.collection("users").document(uid).get(Source.SERVER).await()
    if (user.metadata.isFromCache) {
        HouseholdCheck.Unknown
    } else {
        val hid = user.getString("householdId")?.takeIf(String::isNotEmpty)
        if (hid == null) {
            HouseholdCheck.Unpaired
        } else {
            val doc = db.collection("households").document(hid).get(Source.SERVER).await()
            if (doc.metadata.isFromCache) {
                HouseholdCheck.Unknown
            } else {
                householdFrom(hid, uid, doc.get("members"))?.let { HouseholdCheck.Paired(it) } ?: HouseholdCheck.Unpaired
            }
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    HouseholdCheck.Unknown
}
