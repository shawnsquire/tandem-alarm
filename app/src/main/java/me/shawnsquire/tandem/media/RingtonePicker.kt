package me.shawnsquire.tandem.media

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import me.shawnsquire.tandem.R

/** System alarm-sound picker. Input is the current URI string (or null); output the picked URI string or null. */
class PickSystemRingtone : ActivityResultContract<String?, String?>() {
    override fun createIntent(context: Context, input: String?): Intent =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.ringtone_picker_title))
            if (!input.isNullOrBlank()) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(input))
            }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)?.toString()
    }
}

/** Pick an audio file via the document picker and keep read access to it. */
class PickAudioFile(private val resolver: ContentResolver) : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        val uri = intent?.data ?: return null
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Keep the URI; playback falls back at fire time.
        }
        return uri.toString()
    }
}

/** Human-readable title for a stored sound URI, for UI display. Never throws. */
fun ringtoneTitle(context: Context, uri: String?): String {
    if (uri.isNullOrBlank()) return context.getString(R.string.ringtone_default_alarm)
    val fallback = context.getString(R.string.ringtone_unknown)
    return try {
        val parsed = Uri.parse(uri)
        RingtoneManager.getRingtone(context, parsed)?.getTitle(context)?.takeIf { it.isNotBlank() }
            ?: displayName(context, parsed)
            ?: fallback
    } catch (_: Exception) {
        fallback
    }
}

private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
    }
} catch (_: Exception) {
    null
}
