package me.shawnsquire.tandem.alarm

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/**
 * Robolectric never locks credential-encrypted storage, so this tripwire stands in for a phone
 * before first unlock: every CE accessor throws, while the device-protected context works.
 */
class LockedContext(private val base: Context) : ContextWrapper(base) {
    private fun locked(): Nothing = throw IllegalStateException("CE storage touched while locked")

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = locked()
    override fun getFilesDir(): File = locked()
    override fun getDataDir(): File = locked()
    override fun getDatabasePath(name: String?): File = locked()
    override fun createDeviceProtectedStorageContext(): Context = base.createDeviceProtectedStorageContext()
}
