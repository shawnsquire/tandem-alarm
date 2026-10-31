package me.shawnsquire.tandem

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.functions
import me.shawnsquire.tandem.sync.SyncWorker

class TandemApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // No-op when FirebaseInitProvider already ran; needed under Robolectric, which skips providers.
        FirebaseApp.initializeApp(this)
        // Must run before anything touches a Firebase instance.
        if (BuildConfig.USE_EMULATOR) connectToEmulators()
        container = AppContainer(this)
        container.authManager.start(container.syncScope)
        container.statePublisher.start()
        container.ringingCommandListener.start()
        SyncWorker.schedule(this)
    }

    private fun connectToEmulators() {
        val host = BuildConfig.EMULATOR_HOST
        Firebase.auth.useEmulator(host, 9099)
        Firebase.firestore.useEmulator(host, 8080)
        Firebase.functions.useEmulator(host, 5001)
    }
}
