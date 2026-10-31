package me.shawnsquire.tandem

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.functions

class TandemApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Must run before anything touches a Firebase instance.
        if (BuildConfig.USE_EMULATOR) connectToEmulators()
        container = AppContainer(this)
    }

    private fun connectToEmulators() {
        val host = BuildConfig.EMULATOR_HOST
        Firebase.auth.useEmulator(host, 9099)
        Firebase.firestore.useEmulator(host, 8080)
        Firebase.functions.useEmulator(host, 5001)
    }
}
