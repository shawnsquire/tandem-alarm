package me.shawnsquire.tandem.ui.alarms

import android.content.Context
import androidx.core.content.edit

class SharedPrefsBannerPrefs(context: Context) : BannerPrefs {
    private val prefs = context.applicationContext.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    override fun isDismissed(): Boolean = prefs.getBoolean(KEY, false)

    override fun setDismissed() = prefs.edit { putBoolean(KEY, true) }

    private companion object {
        const val KEY = "health_banner_dismissed"
    }
}
