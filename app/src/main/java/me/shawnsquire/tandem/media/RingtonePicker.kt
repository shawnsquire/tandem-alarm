package me.shawnsquire.tandem.media

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // The typed getParcelableExtra(name, Class) is buggy on API 33, so use the untyped one.
        @Suppress("DEPRECATION")
        return intent?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?.toString()
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

/** Human-readable title for a stored sound URI, for UI display. Never throws (except cancellation). */
suspend fun ringtoneTitle(context: Context, uri: String?): String {
    if (uri.isNullOrBlank()) return context.getString(R.string.ringtone_default_alarm)
    val fallback = context.getString(R.string.ringtone_unknown)
    return withContext(Dispatchers.IO) {
        try {
            val parsed = Uri.parse(uri)
            RingtoneImporter.titleOf(context.filesDir, parsed)?.let { return@withContext it }
            val system = parsed.authority.let { it == "media" || it == "settings" } &&
                !DocumentsContract.isDocumentUri(context, parsed)
            // Ringtone.getTitle falls back to the raw document id for document URIs, so ask the
            // provider for DISPLAY_NAME first and use RingtoneManager only for system sounds.
            if (system) {
                systemTitle(context, parsed) ?: displayName(context, parsed)
            } else {
                displayName(context, parsed)
            } ?: fallback
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }
    }
}

private fun systemTitle(context: Context, uri: Uri): String? {
    val ringtone = RingtoneManager.getRingtone(context, uri) ?: return null
    return try {
        ringtone.getTitle(context)?.takeIf { it.isNotBlank() }
    } finally {
        ringtone.stop()
    }
}

private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
    }
} catch (_: Exception) {
    null
}
