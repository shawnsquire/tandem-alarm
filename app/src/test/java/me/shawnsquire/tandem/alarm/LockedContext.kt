package me.shawnsquire.tandem.alarm

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Robolectric never locks credential-encrypted storage, so this tripwire stands in for a phone
 * before first unlock: every CE accessor throws, while the device-protected context works.
 * Each touch is also recorded in [touches], since code that swallows errors would hide the throw.
 */
class LockedContext(private val base: Context) : ContextWrapper(base) {
    val touches: MutableList<String> = CopyOnWriteArrayList()

    private fun locked(what: String): Nothing {
        touches += what
        throw IllegalStateException("CE storage touched while locked: $what")
    }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = locked("prefs $name")
    override fun getFilesDir(): File = locked("filesDir")
    override fun getDataDir(): File = locked("dataDir")
    override fun getDatabasePath(name: String?): File = locked("database $name")
    override fun createDeviceProtectedStorageContext(): Context = base.createDeviceProtectedStorageContext()
}
