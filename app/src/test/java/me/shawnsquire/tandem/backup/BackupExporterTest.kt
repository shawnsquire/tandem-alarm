package me.shawnsquire.tandem.backup

import android.net.Uri
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LateRing
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.WallpaperFrame
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.Instant
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
class BackupExporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val at = Instant.parse("2026-11-03T12:00:00Z")
    private val ringtones get() = File(tmp.root, "ringtones").also { it.mkdirs() }
    private val wallpapers get() = File(tmp.root, "wallpapers").also { it.mkdirs() }
    private val bytes = ByteArray(3000) { (it % 251).toByte() }

    private fun alarm(
        id: String = "a",
        ringtone: String? = null,
        wallpaper: String? = null,
        frame: WallpaperFrame? = null,
        link: AlarmLink? = null,
    ) = Alarm(
        id = id, label = "Work", hour = 6, minute = 30,
        days = setOf(DayOfWeek.TUESDAY, DayOfWeek.MONDAY), enabled = true,
        skippedOccurrenceAt = null, ringtoneUri = ringtone, wallpaperPath = wallpaper,
        link = link, wallpaperFrame = frame,
    )

    private fun snapshot(
        alarms: List<Alarm> = emptyList(),
        ringtone: String? = null,
        wallpaper: String? = null,
        frame: WallpaperFrame? = null,
        name: String? = null,
    ) = BackupSnapshot(alarms, 15, ringtone, wallpaper, frame, name)

    private class Zip(val entries: List<String>, val files: Map<String, ByteArray>, val result: ExportResult) {
        val manifest get() = JSONObject(String(files.getValue("manifest.json")))
    }

    private fun export(s: BackupSnapshot): Zip {
        val out = ByteArrayOutputStream()
        val result = BackupExporter(tmp.root, "0.4.1") { at }.export(s, out)
        val names = mutableListOf<String>()
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names += e.name
                files[e.name] = z.readBytes()
            }
        }
        return Zip(names, files, result)
    }

    /** Every `file` value in [node], at any depth. */
    private fun fileRefs(node: Any?): List<String> = when (node) {
        is JSONObject -> node.keys().asSequence().flatMap { k ->
            if (k == "file") listOf(node.getString(k)) else fileRefs(node.get(k))
        }.toList()
        is JSONArray -> (0 until node.length()).flatMap { fileRefs(node.get(it)) }
        else -> emptyList()
    }

    /** The manifest names exactly the media entries the zip holds, and each is named once in the zip. */
    private fun assertManifestMatchesEntries(z: Zip) {
        val named = fileRefs(z.manifest).toSet()
        val media = z.entries.filter { it.startsWith("media/") }
        assertEquals(media.size, media.toSet().size)
        assertTrue("named but absent: ${named - media.toSet()}", media.containsAll(named))
        assertTrue("present but unnamed: ${media.toSet() - named}", named.containsAll(media))
    }

    private fun keys(o: JSONObject) = o.keys().asSequence().toSet()

    private fun ringtoneFile(name: String) = File(ringtones, name).also { it.writeBytes(bytes) }
    private fun wallpaperFile(name: String) = File(wallpapers, name).also { it.writeBytes(bytes) }
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun manifestHoldsEverything() {
        val r = ringtoneFile("$uuid-Morning birds.wav")
        val w = wallpaperFile("$uuid.jpg")
        val z = export(
            snapshot(
                alarms = listOf(
                    alarm(ringtone = "content://media/internal/audio/media/12", wallpaper = w.path, frame = WallpaperFrame(1.5f, 0.3f, 0.6f))
                        .copy(vibration = VibrationPattern.RAPID, vibrationOn = false, soundOn = true, textPosition = TextPosition.BOTTOM, enabled = false),
                ),
                ringtone = Uri.fromFile(r).toString(), wallpaper = w.path,
                frame = WallpaperFrame(1.5f, 0.3f, 0.6f), name = "Shawn",
            ),
        )
        val m = z.manifest
        assertEquals("tandem-backup", m.getString("format"))
        assertEquals(1, m.getInt("version"))
        assertEquals("2026-11-03T12:00:00Z", m.getString("exportedAt"))
        assertEquals("0.4.1", m.getString("appVersion"))
        val s = m.getJSONObject("settings")
        assertEquals(15, s.getInt("snoozeMinutes"))
        assertEquals("Shawn", s.getString("displayName"))
        assertEquals("media/ringtones/$uuid-Morning birds.wav", s.getJSONObject("ringtone").getString("file"))
        val sw = s.getJSONObject("wallpaper")
        assertEquals("media/wallpapers/$uuid.jpg", sw.getString("file"))
        val f = sw.getJSONObject("frame")
        assertEquals(1.5, f.getDouble("zoom"), 0.0)
        assertEquals(0.3, f.getDouble("focusX"), 0.0)
        assertEquals(0.6, f.getDouble("focusY"), 0.0)
        val a = m.getJSONArray("alarms").getJSONObject(0)
        assertEquals("Work", a.getString("label"))
        assertEquals(6, a.getInt("hour"))
        assertEquals(30, a.getInt("minute"))
        assertEquals(listOf("MONDAY", "TUESDAY"), (0 until 2).map { a.getJSONArray("days").getString(it) })
        assertFalse(a.getBoolean("enabled"))
        assertEquals("content://media/internal/audio/media/12", a.getJSONObject("ringtone").getString("uri"))
        assertEquals("media/wallpapers/$uuid.jpg", a.getJSONObject("wallpaper").getString("file"))
        assertEquals("RAPID", a.getString("vibration"))
        assertFalse(a.getBoolean("vibrationOn"))
        assertTrue(a.getBoolean("soundOn"))
        assertEquals("BOTTOM", a.getString("textPosition"))
        assertManifestMatchesEntries(z)
    }

    @Test fun manifestIsFirstEntry() {
        val w = wallpaperFile("$uuid.jpg")
        val z = export(snapshot(listOf(alarm(wallpaper = w.path))))
        assertEquals(listOf("manifest.json", "media/wallpapers/$uuid.jpg"), z.entries)
        assertManifestMatchesEntries(z)
    }

    @Test fun linkedRowsAreLeftOut() {
        val link = AlarmLink("u", "o", LinkMode.RING, null, true, null, null, null, null)
        val z = export(snapshot(listOf(alarm("own"), alarm("linked", link = link))))
        assertEquals(1, z.result.alarms)
        assertEquals(1, z.manifest.getJSONArray("alarms").length())
    }

    @Test fun runtimeStateAndIdAreNotWritten() {
        val a = alarm().copy(
            skippedOccurrenceAt = at, soloSkippedOccurrenceAt = at, lateRing = LateRing(at, true),
        )
        val o = export(snapshot(listOf(a))).manifest.getJSONArray("alarms").getJSONObject(0)
        assertEquals(
            setOf("label", "hour", "minute", "days", "enabled", "vibration", "vibrationOn", "soundOn", "textPosition"),
            keys(o),
        )
    }

    @Test fun fileRingtoneIsCopiedWithTitle() {
        val r = ringtoneFile("$uuid-Morning birds.wav")
        val z = export(snapshot(listOf(alarm(ringtone = Uri.fromFile(r).toString()))))
        val rt = z.manifest.getJSONArray("alarms").getJSONObject(0).getJSONObject("ringtone")
        assertEquals("media/ringtones/$uuid-Morning birds.wav", rt.getString("file"))
        assertEquals("Morning birds.wav", rt.getString("title"))
        assertTrue(bytes.contentEquals(z.files.getValue("media/ringtones/$uuid-Morning birds.wav")))
        assertManifestMatchesEntries(z)
    }

    @Test fun contentRingtoneStaysAUri() {
        val uri = "content://media/internal/audio/media/12"
        val z = export(snapshot(listOf(alarm(ringtone = uri))))
        val rt = z.manifest.getJSONArray("alarms").getJSONObject(0).getJSONObject("ringtone")
        assertEquals(setOf("uri"), keys(rt))
        assertEquals(uri, rt.getString("uri"))
        assertEquals(0, z.result.missingMedia)
        assertEquals(listOf("manifest.json"), z.entries)
        assertManifestMatchesEntries(z)
    }

    @Test fun wallpaperIsCopiedWithFrame() {
        val w = wallpaperFile("$uuid.jpg")
        val z = export(snapshot(listOf(alarm(wallpaper = w.path, frame = WallpaperFrame(2f, 0.25f, 0.75f)))))
        val wp = z.manifest.getJSONArray("alarms").getJSONObject(0).getJSONObject("wallpaper")
        assertEquals(setOf("file", "frame"), keys(wp))
        assertEquals(2.0, wp.getJSONObject("frame").getDouble("zoom"), 0.0)
        assertTrue(bytes.contentEquals(z.files.getValue("media/wallpapers/$uuid.jpg")))
        assertManifestMatchesEntries(z)
    }

    @Test fun wallpaperWithoutFrameHasNoFrameKey() {
        val w = wallpaperFile("$uuid.jpg")
        val z = export(snapshot(listOf(alarm(wallpaper = w.path))))
        assertEquals(setOf("file"), keys(z.manifest.getJSONArray("alarms").getJSONObject(0).getJSONObject("wallpaper")))
        assertManifestMatchesEntries(z)
    }

    @Test fun missingFilesAreOmittedAndCounted() {
        val r = File(ringtones, "$uuid-Gone.wav")
        val w = File(wallpapers, "$uuid.jpg")
        val z = export(snapshot(listOf(alarm(ringtone = Uri.fromFile(r).toString(), wallpaper = w.path, frame = WallpaperFrame.CENTERED))))
        val a = z.manifest.getJSONArray("alarms").getJSONObject(0)
        assertFalse(a.has("ringtone"))
        assertFalse(a.has("wallpaper"))
        assertEquals(2, z.result.missingMedia)
        assertEquals(1, z.result.alarms)
        assertEquals(listOf("manifest.json"), z.entries)
        assertManifestMatchesEntries(z)
    }

    @Test fun ringtoneOutsideTheRingtonesDirIsMissing() {
        val other = File(tmp.newFolder("elsewhere"), "$uuid-x.wav").also { it.writeBytes(bytes) }
        val z = export(snapshot(listOf(alarm(ringtone = Uri.fromFile(other).toString()))))
        assertFalse(z.manifest.getJSONArray("alarms").getJSONObject(0).has("ringtone"))
        assertEquals(1, z.result.missingMedia)
        assertEquals(listOf("manifest.json"), z.entries)
        assertManifestMatchesEntries(z)
    }

    @Test fun sharedFileIsStoredOnce() {
        val r = ringtoneFile("$uuid-Morning birds.wav")
        val w = wallpaperFile("$uuid.jpg")
        val uri = Uri.fromFile(r).toString()
        val z = export(snapshot(listOf(alarm("a", uri, w.path), alarm("b", uri, w.path)), ringtone = uri, wallpaper = w.path))
        assertEquals(3, z.entries.size)
        assertEquals(0, z.result.missingMedia)
        assertManifestMatchesEntries(z)
    }

    @Test fun blankOrNullDisplayNameIsAbsent() {
        for (n in listOf(null, "", "   ")) {
            assertFalse(export(snapshot(name = n)).manifest.getJSONObject("settings").has("displayName"))
        }
    }

    @Test fun absentSettingsMediaKeysAreOmitted() {
        val s = export(snapshot()).manifest.getJSONObject("settings")
        assertEquals(setOf("snoozeMinutes"), keys(s))
    }

    @Test fun wallpaperOutsideTheWallpapersDirIsMissing() {
        val inRingtones = ringtoneFile("$uuid.jpg")
        val traversal = File(File(tmp.root, "wallpapers").also { it.mkdirs() }, "../ringtones/x.jpg")
        ringtoneFile("x.jpg")
        for (path in listOf(inRingtones.path, traversal.path)) {
            val z = export(snapshot(listOf(alarm(wallpaper = path))))
            assertFalse(z.manifest.getJSONArray("alarms").getJSONObject(0).has("wallpaper"))
            assertEquals(1, z.result.missingMedia)
            assertEquals(listOf("manifest.json"), z.entries)
            assertManifestMatchesEntries(z)
        }
    }

    @Test fun fileDeletedAfterItWasChosenIsStillWritten() {
        val r = ringtoneFile("$uuid-Morning birds.wav")
        val w = wallpaperFile("$uuid.jpg")
        // The first byte written is the manifest, so the files are chosen by then.
        val out = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                r.delete(); w.delete()
                super.write(b, off, len)
            }
        }
        val result = BackupExporter(tmp.root, "0.4.1") { at }
            .export(snapshot(listOf(alarm(ringtone = Uri.fromFile(r).toString(), wallpaper = w.path))), out)
        assertEquals(0, result.missingMedia)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) names += (z.nextEntry ?: break).name
        }
        assertEquals(listOf("manifest.json", "media/ringtones/$uuid-Morning birds.wav", "media/wallpapers/$uuid.jpg"), names)
    }

    @Test fun callersStreamIsLeftOpen() {
        var closed = false
        val out = object : OutputStream() {
            override fun write(b: Int) {}
            override fun close() { closed = true }
        }
        BackupExporter(tmp.root, "0.4.1") { at }.export(snapshot(), out)
        assertFalse(closed)
    }
}
