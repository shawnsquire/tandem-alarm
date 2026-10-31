package me.shawnsquire.tandem.sync

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/** Seam over the owner's `households/{hid}/devices/{myUid}` doc so the publisher can be tested with a fake. */
interface DeviceDocWriter {
    /** Enqueues [data] as a merge write; never throws for remote failures. */
    fun write(household: Household, data: Map<String, Any?>)

    /** Merge write that suspends until the server acknowledges it; throws on failure. Used by the heartbeat. */
    suspend fun writeAndAwait(household: Household, data: Map<String, Any?>)
}

class FirestoreDeviceDocWriter(private val db: FirebaseFirestore) : DeviceDocWriter {
    // Not awaited: Firestore queues offline writes. Merge so a missing fcmToken never erases the stored one.
    override fun write(household: Household, data: Map<String, Any?>) {
        db.document("households/${household.id}/devices/${household.myUid}")
            .set(data, SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "device doc write failed", it) }
    }

    override suspend fun writeAndAwait(household: Household, data: Map<String, Any?>) {
        db.document("households/${household.id}/devices/${household.myUid}")
            .set(data, SetOptions.merge())
            .await()
    }

    private companion object {
        const val TAG = "DeviceDocWriter"
    }
}
