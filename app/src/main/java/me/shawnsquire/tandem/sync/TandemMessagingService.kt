package me.shawnsquire.tandem.sync

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.shawnsquire.tandem.TandemApp

class TandemMessagingService : FirebaseMessagingService() {
    /** Runs on a background thread, and the process stays alive while it runs, so block until handled. */
    override fun onMessageReceived(message: RemoteMessage) {
        try {
            val app = application as TandemApp
            app.ensureStarted()
            val container = app.container
            runBlocking { container.fcmCommandHandler.handle(message.data) }
        } catch (e: Throwable) {
            Log.e(TAG, "onMessageReceived failed", e)
        }
    }

    override fun onNewToken(token: String) {
        try {
            val app = application as TandemApp
            app.ensureStarted()
            val container = app.container
            runBlocking { container.fcmTokenStore.set(token) }
        } catch (e: Throwable) {
            Log.e(TAG, "onNewToken failed", e)
        }
    }

    private companion object {
        const val TAG = "TandemMessaging"
    }
}

/** Fetches the current FCM token once and stores it; the state publisher then writes it to the device doc. */
fun refreshFcmToken(scope: CoroutineScope, tokens: FcmTokenStore) {
    try {
        scope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                if (!token.isNullOrBlank()) tokens.set(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("TandemMessaging", "could not fetch FCM token", e)
            }
        }
    } catch (e: Throwable) {
        Log.w("TandemMessaging", "could not start FCM token fetch", e)
    }
}
