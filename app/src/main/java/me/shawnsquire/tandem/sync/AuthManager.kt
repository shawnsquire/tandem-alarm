package me.shawnsquire.tandem.sync

import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Anonymous Firebase sign-in plus the user-chosen display name (kept in the sync-owned store). */
class AuthManager(
    private val auth: FirebaseAuth,
    store: DataStore<Preferences>,
    defaultName: String = Build.MODEL,
) {
    private val names = DisplayNameStore(store, defaultName)
    private val _uid = MutableStateFlow(auth.currentUser?.uid)

    /** Current user's uid; null when signed out. */
    val uid: StateFlow<String?> = _uid.asStateFlow()

    private val listener = FirebaseAuth.AuthStateListener { _uid.value = it.currentUser?.uid }

    init {
        auth.addAuthStateListener(listener)
    }

    /** Detaches the auth-state listener; [uid] stops following sign-in changes. */
    fun close() {
        auth.removeAuthStateListener(listener)
    }

    /** Returns the current uid, signing in anonymously if needed. Throws on failure. */
    suspend fun ensureSignedIn(): String {
        auth.currentUser?.uid?.let { return it }
        val user = auth.signInAnonymously().await().user
        return checkNotNull(user) { "anonymous sign-in returned no user" }.uid
    }

    /** Signs in once in the background; failures (e.g. offline at boot) are logged only. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            try {
                ensureSignedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "anonymous sign-in failed", e)
            }
        }
    }

    val displayName: Flow<String> get() = names.displayName

    /** Null or blank clears the override so the default is used again. */
    suspend fun setDisplayName(name: String?) = names.set(name)

    private companion object {
        const val TAG = "AuthManager"
    }
}

/** DataStore-backed display name with a default; split out so it is testable without Firebase. */
internal class DisplayNameStore(
    private val store: DataStore<Preferences>,
    private val defaultName: String,
) {
    val displayName: Flow<String> = store.data.map { p ->
        p[KEY]?.takeIf { it.isNotBlank() } ?: defaultName
    }

    suspend fun set(name: String?) {
        store.edit { p ->
            val trimmed = name?.trim()
            if (trimmed.isNullOrEmpty()) p.remove(KEY) else p[KEY] = trimmed
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("displayName")
    }
}
