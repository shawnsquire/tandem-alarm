package me.shawnsquire.tandem.ui.settings.health

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Reads the system switches that decide whether alarms can ring reliably. Cheap; call on every resume. */
interface HealthProbe {
    fun exactAlarms(): Boolean
    fun fullScreenIntent(): Boolean
    fun notifications(): Boolean
    fun batteryUnrestricted(): Boolean
}

class AndroidHealthProbe(private val context: Context) : HealthProbe {
    override fun exactAlarms(): Boolean =
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    // canUseFullScreenIntent() is API 34+; minSdk is 33, where the permission is a plain install-time grant.
    override fun fullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    // Both: the runtime permission and the user-level "notifications on" switch in app settings.
    override fun notifications(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    override fun batteryUnrestricted(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}

/** True when the POST_NOTIFICATIONS runtime permission itself is granted (the system dialog can no longer help if so). */
fun notificationPermissionGranted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

fun exactAlarmsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))

fun fullScreenIntentSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context))

fun batteryIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))

fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

/** Starts [intent]; falls back to the app-details page when no activity handles it. */
fun startSettingsSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {
        try {
            context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            // Nothing more we can open.
        }
    }
}
