package me.shawnsquire.tandem.media

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RingtonePickerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://media/internal/audio/media/42")

    @Test fun systemIntentExtras() {
        val i = PickSystemRingtone().createIntent(context, uri.toString())
        assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, i.action)
        assertEquals(RingtoneManager.TYPE_ALARM, i.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1))
        assertFalse(i.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true))
        assertFalse(i.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
        @Suppress("DEPRECATION")
        assertEquals(uri, i.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI))
    }

    @Test fun systemIntentNullExisting() {
        val i = PickSystemRingtone().createIntent(context, null)
        assertFalse(i.hasExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI))
    }

    @Test fun systemParse() {
        val c = PickSystemRingtone()
        val data = Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, uri)
        assertEquals(uri.toString(), c.parseResult(Activity.RESULT_OK, data))
        assertNull(c.parseResult(Activity.RESULT_CANCELED, data))
        assertNull(c.parseResult(Activity.RESULT_OK, null))
        assertNull(c.parseResult(Activity.RESULT_OK, Intent()))
    }

    @Test fun fileIntent() {
        val i = PickAudioFile(context.contentResolver).createIntent(context, Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, i.action)
        assertEquals("audio/*", i.type)
        assertTrue(i.categories.contains(Intent.CATEGORY_OPENABLE))
    }

    private class RecordingResolver(ctx: Context, val fail: Boolean) : ContentResolver(ctx) {
        var taken: Pair<Uri, Int>? = null
        override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
            taken = uri to modeFlags
            if (fail) throw SecurityException("nope")
        }
    }

    @Test fun fileParseTakesPermission() {
        val r = RecordingResolver(context, fail = false)
        val out = PickAudioFile(r).parseResult(Activity.RESULT_OK, Intent().setData(uri))
        assertEquals(uri.toString(), out)
        assertEquals(uri to Intent.FLAG_GRANT_READ_URI_PERMISSION, r.taken)
    }

    @Test fun fileParseKeepsUriWhenGrantFails() {
        val r = RecordingResolver(context, fail = true)
        assertEquals(uri.toString(), PickAudioFile(r).parseResult(Activity.RESULT_OK, Intent().setData(uri)))
    }

    @Test fun fileParseCancel() {
        val c = PickAudioFile(RecordingResolver(context, false))
        assertNull(c.parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)))
        assertNull(c.parseResult(Activity.RESULT_OK, null))
    }

    @Test fun titleBlankIsDefaultAlarm() {
        assertEquals("Default alarm", ringtoneTitle(context, null))
        assertEquals("Default alarm", ringtoneTitle(context, "  "))
    }

    @Test fun titleUnresolvableFallsBack() {
        val t = ringtoneTitle(context, "content://nope/1")
        assertTrue(t.isNotBlank())
    }
}
