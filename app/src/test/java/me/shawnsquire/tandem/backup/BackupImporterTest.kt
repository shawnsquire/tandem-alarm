package me.shawnsquire.tandem.backup

import android.app.AlarmManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.alarm.AlarmIntents
import me.shawnsquire.tandem.alarm.AlarmSchedulerImpl
import me.shawnsquire.tandem.alarm.InMemorySnoozeLedger
import me.shawnsquire.tandem.awaitTandemStartup
import me.shawnsquire.tandem.backup.BackupRejected.Reason
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.media.RingtoneImporter
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.WallpaperFrame
import me.shawnsquire.tandem.ui.alarms.AlarmEdits
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class BackupImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val filesDir get() = File(tmp.root, "files").also { it.mkdirs() }
    private val stagingRoot get() = File(tmp.root, "staging")
    private val ringtones get() = File(filesDir, "ringtones")
    private val wallpapers get() = File(filesDir, "wallpapers")

    private val rPath = "media/ringtones/0b7c1a52-4f1e-4c55-9d1e-2a6b3c4d5e01-Morning birds.wav"
    private val rPath2 = "media/ringtones/0b7c1a52-4f1e-4c55-9d1e-2a6b3c4d5e02-Gentle chime.wav"
    private val wPath = "media/wallpapers/5d2e8f10-3a4b-4c5d-8e9f-0a1b2c3d4e01.jpg"
    private val wPath2 = "media/wallpapers/5d2e8f10-3a4b-4c5d-8e9f-0a1b2c3d4e02.jpg"
    private val rBytes = ByteArray(5000) { (it % 241).toByte() }
    private val wBytes = ByteArray(3000) { (it % 199).toByte() }

    private fun importer(
        canOpen: (String) -> Boolean = { true },
        maxMedia: Long = MAX_MEDIA_BYTES_PER_FILE,
        maxEntries: Int = MAX_ENTRIES,
        ids: Iterator<String> = generateSequence(1) { it + 1 }.map { "new-$it" }.iterator(),
        maxStaged: Long = MAX_STAGED_BYTES,
        maxImport: Long = MAX_IMPORT_BYTES,
        usableSpace: () -> Long = { Long.MAX_VALUE },
    ) = BackupImporter(
        filesDir, stagingRoot, canOpen, { ids.next() }, maxMedia, maxEntries,
        maxStagedBytes = maxStaged, maxImportBytes = maxImport, usableSpace = usableSpace,
    )

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    private fun file(path: String, title: String? = null) =
        JSONObject().put("file", path).also { o -> title?.let { o.put("title", it) } }

    private fun alarmJson(
        label: String = "A",
        days: List<String> = listOf("MONDAY"),
        ringtone: JSONObject? = null,
        wallpaper: JSONObject? = null,
        vibrationOn: Boolean = true,
        soundOn: Boolean = true,
    ) = JSONObject().put("label", label).put("hour", 7).put("minute", 5)
        .put("days", JSONArray(days)).put("enabled", true)
        .put("vibration", "PULSE").put("vibrationOn", vibrationOn).put("soundOn", soundOn)
        .put("textPosition", "MIDDLE")
        .also { o ->
            ringtone?.let { o.put("ringtone", it) }
            wallpaper?.let { o.put("wallpaper", it) }
        }

    private fun wall(path: String) = JSONObject().put("file", path)
        .put("frame", JSONObject().put("zoom", 1.5).put("focusX", 0.25).put("focusY", 0.75))

    private fun manifest(alarms: List<JSONObject>, settings: JSONObject = JSONObject().put("snoozeMinutes", 12)) =
        JSONObject().put("format", "tandem-backup").put("version", 1).put("appVersion", "0.1.0")
            .put("settings", settings).put("alarms", JSONArray(alarms)).toString().toByteArray()

    private fun backup(alarms: List<JSONObject>, settings: JSONObject = JSONObject().put("snoozeMinutes", 12), vararg media: Pair<String, ByteArray>) =
        zip(MANIFEST_ENTRY to manifest(alarms, settings), *media)

    private fun stagingEmpty() {
        val left = stagingRoot.walkTopDown().filter { it.isFile }.toList()
        assertTrue("staging left $left", left.isEmpty())
        assertTrue("staging left directories", stagingRoot.listFiles().orEmpty().isEmpty())
    }

    private fun rejects(reason: Reason, bytes: ByteArray, imp: BackupImporter = importer()) {
        try {
            imp.read(ByteArrayInputStream(bytes))
            fail("expected $reason")
        } catch (e: BackupRejected) {
            assertEquals(reason, e.reason)
        }
        stagingEmpty()
    }

    private fun fixture() = javaClass.classLoader!!.getResourceAsStream("backup/fixture-v1.zip")!!.readBytes()

    private suspend fun applyTo(
        imp: BackupImporter,
        bytes: ByteArray,
        repo: FakeAlarmRepository = FakeAlarmRepository(emptyList()),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        name: MutableList<String?> = mutableListOf(),
        add: suspend (Alarm) -> Unit = { repo.upsert(it) },
        storedName: String? = null,
    ): ImportResult {
        val staged = imp.read(ByteArrayInputStream(bytes))
        return imp.apply(staged, add, { repo.delete(it) }, settings, { storedName }, { name += it })
    }

    // --- Rejections ---

    @Test fun notAZip() = rejects(Reason.NOT_A_BACKUP, ByteArray(500) { (it * 7).toByte() })

    @Test fun emptyZip() = rejects(Reason.NOT_A_BACKUP, zip())

    @Test fun manifestNotFirst() =
        rejects(Reason.NOT_A_BACKUP, zip("a.txt" to ByteArray(3), MANIFEST_ENTRY to manifest(emptyList())))

    @Test fun manifestOverCap() =
        rejects(Reason.TOO_LARGE, zip(MANIFEST_ENTRY to ByteArray(MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }))

    @Test fun tooManyEntries() = rejects(
        Reason.TOO_LARGE,
        zip(MANIFEST_ENTRY to manifest(emptyList()), "x1" to ByteArray(1), "x2" to ByteArray(1), "x3" to ByteArray(1)),
        importer(maxEntries = 2),
    )

    @Test fun truncatedFixtureIsIncomplete() {
        val f = fixture()
        rejects(Reason.INCOMPLETE, f.copyOf(f.size / 2))
    }

    @Test fun missingEntryIsIncomplete() =
        rejects(Reason.INCOMPLETE, backup(listOf(alarmJson(ringtone = file(rPath)))))

    @Test fun duplicateWantedEntryIsDamaged() {
        // ZipOutputStream refuses duplicate names, so write a twin name and patch it back.
        val twin = rPath.dropLast(1) + "X"
        val raw = backup(listOf(alarmJson(ringtone = file(rPath))), media = arrayOf(rPath to rBytes, twin to rBytes))
        val patched = String(raw, Charsets.ISO_8859_1).replace(twin, rPath).toByteArray(Charsets.ISO_8859_1)
        rejects(Reason.DAMAGED, patched)
    }

    @Test fun manifestErrorsPropagate() {
        val newer = JSONObject(String(manifest(emptyList()))).put("version", 2).toString().toByteArray()
        rejects(Reason.NEWER_VERSION, zip(MANIFEST_ENTRY to newer))
        rejects(Reason.NOT_A_BACKUP, zip(MANIFEST_ENTRY to "nope".toByteArray()))
    }

    @Test fun leftoverStagingIsSweptOnRead() {
        File(stagingRoot, "old").mkdirs()
        File(stagingRoot, "old/x").writeText("x")
        importer().read(ByteArrayInputStream(backup(emptyList()))).discard()
        assertFalse(File(stagingRoot, "old").exists())
    }

    @Test fun stagedBytesOverBudgetAreRejected() = rejects(
        Reason.TOO_LARGE,
        backup(
            listOf(alarmJson("A", ringtone = file(rPath), wallpaper = wall(wPath))),
            media = arrayOf(rPath to ByteArray(600), wPath to ByteArray(600)),
        ),
        importer(maxStaged = 1000),
    )

    @Test fun stagedBytesAtBudgetAreAccepted() {
        val bytes = backup(
            listOf(alarmJson("A", ringtone = file(rPath), wallpaper = wall(wPath))),
            media = arrayOf(rPath to ByteArray(600), wPath to ByteArray(400)),
        )
        importer(maxStaged = 1000).read(ByteArrayInputStream(bytes)).discard()
    }

    @Test fun copiesOverImportBudgetAreRejectedBeforeApply() = rejects(
        Reason.TOO_LARGE,
        // One 600-byte file, referenced twice: apply would write 1200.
        backup(
            listOf(alarmJson("A", ringtone = file(rPath)), alarmJson("B", ringtone = file(rPath))),
            media = arrayOf(rPath to ByteArray(600)),
        ),
        importer(maxImport = 1000),
    )

    @Test fun droppedFilesCountZeroTowardImportBudget() {
        val bytes = backup(
            listOf(alarmJson("A", ringtone = file(rPath)), alarmJson("B", ringtone = file(rPath))),
            media = arrayOf(rPath to ByteArray(2000)),
        )
        importer(maxMedia = 1000, maxImport = 10).read(ByteArrayInputStream(bytes)).discard()
    }

    @Test fun copiesOverFreeSpaceAreRejected() {
        val bytes = backup(listOf(alarmJson(ringtone = file(rPath))), media = arrayOf(rPath to ByteArray(600)))
        val margin = 100L * 1024 * 1024
        rejects(Reason.TOO_LARGE, bytes, importer(usableSpace = { margin + 599 }))
        importer(usableSpace = { margin + 600 }).read(ByteArrayInputStream(bytes)).discard()
    }

    // --- Safety ---

    // On API 34+ the platform's ZipPathValidator makes getNextEntry() throw ZipException for names with ".."
    // segments or a leading "/", and read() maps that to INCOMPLETE. Robolectric uses the JVM zip code, so
    // here the entries are skipped instead. Either outcome is safe: entry names are never used as paths.
    @Test fun traversalEntriesAreIgnored() = runTest {
        val bytes = backup(
            listOf(alarmJson(ringtone = file(rPath))),
            media = arrayOf(rPath to rBytes, "../../evil.txt" to ByteArray(4), "/abs/evil" to ByteArray(4)),
        )
        val r = applyTo(importer(), bytes)
        assertEquals(ImportResult(1, 0), r)
        val names = tmp.root.walkTopDown().map { it.name }.toList()
        assertFalse(names.contains("evil.txt"))
        assertFalse(File("/abs/evil").exists())
        assertFalse(File(tmp.root.parentFile, "evil.txt").exists())
    }

    @Test fun overCapEntryIsDroppedAndCounted() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val bytes = backup(listOf(alarmJson(ringtone = file(rPath))), media = arrayOf(rPath to ByteArray(2000)))
        val r = applyTo(importer(maxMedia = 1000), bytes, repo)
        assertEquals(ImportResult(1, 1, listOf(LostSound("A", 7, 5))), r)
        assertNull(repo.alarms.first().single().ringtoneUri)
        assertTrue(ringtones.listFiles().orEmpty().isEmpty())
        stagingEmpty()
    }

    // --- Apply ---

    @Test fun freshIdsAndExistingAlarmsKept() = runTest {
        val existing = Alarm("keep", "Keep", 1, 1, emptySet(), true, null, null, null)
        val repo = FakeAlarmRepository(listOf(existing))
        applyTo(importer(), backup(listOf(alarmJson("X"), alarmJson("Y"))), repo)
        val all = repo.alarms.first()
        assertEquals(listOf("keep", "new-1", "new-2"), all.map { it.id })
        assertEquals(existing, all[0])
        assertNull(all[1].link)
    }

    @Test fun mediaIsCopiedPerReferenceByteEqual() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val bytes = backup(
            listOf(
                alarmJson("A", ringtone = file(rPath, "Morning birds.wav"), wallpaper = wall(wPath)),
                alarmJson("B", ringtone = file(rPath, "Morning birds.wav"), wallpaper = wall(wPath)),
            ),
            media = arrayOf(rPath to rBytes, wPath to wBytes),
        )
        val r = applyTo(importer(), bytes, repo)
        assertEquals(ImportResult(2, 0), r)
        val (a, b) = repo.alarms.first()
        assertNotEquals(a.ringtoneUri, b.ringtoneUri)
        assertNotEquals(a.wallpaperPath, b.wallpaperPath)
        for (al in listOf(a, b)) {
            val rf = File(Uri.parse(al.ringtoneUri).path!!)
            assertEquals(ringtones.canonicalFile, rf.canonicalFile.parentFile)
            assertTrue(rBytes.contentEquals(rf.readBytes()))
            assertEquals("Morning birds.wav", RingtoneImporter.titleOf(filesDir, Uri.parse(al.ringtoneUri)))
            val wf = File(al.wallpaperPath!!)
            assertEquals(wallpapers.canonicalFile, wf.canonicalFile.parentFile)
            assertTrue(wf.name.endsWith(".jpg"))
            assertTrue(wBytes.contentEquals(wf.readBytes()))
            assertEquals(WallpaperFrame(1.5f, 0.25f, 0.75f), al.wallpaperFrame)
        }
        assertEquals(4, ringtones.listFiles().orEmpty().size + wallpapers.listFiles().orEmpty().size)
        stagingEmpty()
        assertFalse(stagingRoot.listFiles().orEmpty().any())
    }

    @Test fun titleFallsBackToZipFileName() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        applyTo(importer(), backup(listOf(alarmJson(ringtone = file(rPath))), media = arrayOf(rPath to rBytes)), repo)
        assertEquals("Morning birds.wav", RingtoneImporter.titleOf(filesDir, Uri.parse(repo.alarms.first().single().ringtoneUri)))
    }

    @Test fun contentUrisAreKeptOnlyIfOpenable() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val ok = "content://media/1"
        val bad = "content://media/2"
        val bytes = backup(listOf(
            alarmJson("ok", ringtone = JSONObject().put("uri", ok)),
            alarmJson("bad", ringtone = JSONObject().put("uri", bad)),
        ))
        val r = applyTo(importer(canOpen = { it == ok }), bytes, repo)
        assertEquals(ImportResult(2, 1, listOf(LostSound("bad", 7, 5))), r)
        val (a, b) = repo.alarms.first()
        assertEquals(ok, a.ringtoneUri)
        assertNull(b.ringtoneUri)
    }

    @Test fun settingsAreReplacedAndOldDefaultsDeleted() = runTest {
        ringtones.mkdirs(); wallpapers.mkdirs()
        val oldR = File(ringtones, "0b7c1a52-4f1e-4c55-9d1e-2a6b3c4d5eff-old.wav").also { it.writeText("o") }
        val oldW = File(wallpapers, "old.jpg").also { it.writeText("o") }
        val outside = File(tmp.root, "keep.jpg").also { it.writeText("o") }
        val settings = FakeSettingsRepository()
        settings.setDefaultRingtoneUri(Uri.fromFile(oldR).toString())
        settings.setDefaultWallpaper(oldW.absolutePath, null)
        val names = mutableListOf<String?>()
        val s = JSONObject().put("snoozeMinutes", 15).put("displayName", "Shawn")
            .put("ringtone", file(rPath2, "Gentle chime.wav")).put("wallpaper", wall(wPath2))
        val r = applyTo(importer(), backup(emptyList(), s, rPath2 to rBytes, wPath2 to wBytes), settings = settings, name = names)
        assertEquals(ImportResult(0, 0), r)
        assertEquals(15, settings.snoozeMinutes.first())
        assertEquals(listOf<String?>("Shawn"), names)
        assertEquals(WallpaperFrame(1.5f, 0.25f, 0.75f), settings.defaultWallpaperFrame.first())
        val newR = Uri.parse(settings.defaultRingtoneUri.first()).path!!
        assertTrue(rBytes.contentEquals(File(newR).readBytes()))
        assertTrue(wBytes.contentEquals(File(settings.defaultWallpaperPath.first()!!).readBytes()))
        assertFalse(oldR.exists())
        assertFalse(oldW.exists())
        assertTrue(outside.exists())
    }

    @Test fun nullDisplayNameAndMissingDefaultsReset() = runTest {
        val settings = FakeSettingsRepository()
        settings.setDefaultRingtoneUri("content://x/1")
        val names = mutableListOf<String?>("Old")
        applyTo(importer(), backup(emptyList()), settings = settings, name = names)
        assertEquals("Old", names[0])
        assertNull(names[1])
        assertNull(settings.defaultRingtoneUri.first())
        assertNull(settings.defaultWallpaperPath.first())
        assertEquals(12, settings.snoozeMinutes.first())
    }

    @Test fun rollbackWhenSecondAddFails() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val bytes = backup(
            listOf(
                alarmJson("A", ringtone = file(rPath), wallpaper = wall(wPath)),
                alarmJson("B", ringtone = file(rPath), wallpaper = wall(wPath)),
            ),
            media = arrayOf(rPath to rBytes, wPath to wBytes),
        )
        var n = 0
        try {
            applyTo(importer(), bytes, repo, add = { if (++n == 2) throw IllegalStateException("boom"); repo.upsert(it) })
            fail("expected throw")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertTrue(repo.alarms.first().isEmpty())
        assertTrue(ringtones.listFiles().orEmpty().isEmpty())
        assertTrue(wallpapers.listFiles().orEmpty().isEmpty())
        stagingEmpty()
    }

    @Test fun rollbackKeepsFilesOfAnAlarmThatCouldNotBeDeleted() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val bytes = backup(
            listOf(
                alarmJson("A", ringtone = file(rPath), wallpaper = wall(wPath)),
                alarmJson("B", ringtone = file(rPath), wallpaper = wall(wPath)),
            ),
            media = arrayOf(rPath to rBytes, wPath to wBytes),
        )
        val imp = importer()
        val staged = imp.read(ByteArrayInputStream(bytes))
        var n = 0
        try {
            imp.apply(
                staged,
                { if (++n == 2) throw IllegalStateException("boom"); repo.upsert(it) },
                { if (it == "new-1") throw IllegalStateException("cannot delete") else repo.delete(it) },
                FakeSettingsRepository(), { null }, {},
            )
            fail("expected throw")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        // A is still stored, so its two files stay; B's were removed.
        assertEquals(listOf("new-1"), repo.alarms.first().map { it.id })
        val kept = repo.alarms.first().single()
        assertEquals(listOf(File(Uri.parse(kept.ringtoneUri).path!!).canonicalFile), ringtones.listFiles().orEmpty().map { it.canonicalFile })
        assertEquals(listOf(File(kept.wallpaperPath!!).canonicalFile), wallpapers.listFiles().orEmpty().map { it.canonicalFile })
        stagingEmpty()
    }

    @Test fun rollbackWhenSettingsFail() = runTest {
        ringtones.mkdirs(); wallpapers.mkdirs()
        val oldR = File(ringtones, "0b7c1a52-4f1e-4c55-9d1e-2a6b3c4d5eff-old.wav").also { it.writeText("o") }
        val oldW = File(wallpapers, "old.jpg").also { it.writeText("o") }
        val oldRUri = Uri.fromFile(oldR).toString()
        val oldFrame = WallpaperFrame(2f, 0.5f, 0.5f)
        val settings = FakeSettingsRepository()
        settings.setSnoozeMinutes(7)
        settings.setDefaultRingtoneUri(oldRUri)
        settings.setDefaultWallpaper(oldW.absolutePath, oldFrame)
        val names = mutableListOf<String?>()

        val repo = FakeAlarmRepository(emptyList())
        val s = JSONObject().put("snoozeMinutes", 15).put("displayName", "Shawn")
            .put("ringtone", file(rPath2)).put("wallpaper", wall(wPath2))
        val imp = importer()
        val staged = imp.read(ByteArrayInputStream(backup(listOf(alarmJson()), s, rPath2 to rBytes, wPath2 to wBytes)))
        try {
            imp.apply(staged, { repo.upsert(it) }, { repo.delete(it) }, settings, { "Old" }) {
                names += it
                if (it == "Shawn") throw IllegalStateException("x")
            }
            fail("expected throw")
        } catch (_: IllegalStateException) {
        }
        assertEquals(7, settings.snoozeMinutes.first())
        assertEquals(oldRUri, settings.defaultRingtoneUri.first())
        assertEquals(oldW.absolutePath, settings.defaultWallpaperPath.first())
        assertEquals(oldFrame, settings.defaultWallpaperFrame.first())
        assertEquals(listOf<String?>("Shawn", "Old"), names)
        assertTrue(repo.alarms.first().isEmpty())
        // Only the old defaults remain; the new copies are gone.
        assertEquals(listOf(oldR.canonicalFile), ringtones.listFiles().orEmpty().map { it.canonicalFile })
        assertEquals(listOf(oldW.canonicalFile), wallpapers.listFiles().orEmpty().map { it.canonicalFile })
        stagingEmpty()
    }

    @Test fun settingsRollbackRestoresWhatItCanWhenOneRestoreFails() = runTest {
        val settings = FakeSettingsRepository()
        settings.setSnoozeMinutes(7)
        settings.setDefaultRingtoneUri("content://x/1")
        val imp = importer()
        val staged = imp.read(ByteArrayInputStream(backup(emptyList(), JSONObject().put("snoozeMinutes", 15).put("displayName", "Shawn"))))
        try {
            // Fails on the write and on the restore alike.
            imp.apply(staged, {}, {}, settings, { "Old" }) { throw IllegalStateException("x") }
            fail("expected throw")
        } catch (_: IllegalStateException) {
        }
        assertEquals(7, settings.snoozeMinutes.first())
        assertEquals("content://x/1", settings.defaultRingtoneUri.first())
    }

    @Test fun bothSwitchesOffTurnBothOn() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        applyTo(importer(), backup(listOf(alarmJson(vibrationOn = false, soundOn = false))), repo)
        val a = repo.alarms.first().single()
        assertTrue(a.soundOn && a.vibrationOn)
    }

    @Test fun oneShotAlarmImportsWithEmptyDaysAndIsScheduled() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val sched = FakeAlarmScheduler()
        val edits = AlarmEdits(repo, sched)
        applyTo(importer(), backup(listOf(alarmJson(days = emptyList()))), repo, add = { edits.upsert(it) })
        assertEquals(emptySet<DayOfWeek>(), repo.alarms.first().single().days)
        assertEquals(1, sched.rescheduleCount)
    }

    @Test fun alarmWithoutRingtoneUsesDefaultAndIsNotMissing() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val r = applyTo(importer(), backup(listOf(alarmJson())), repo)
        assertNull(repo.alarms.first().single().ringtoneUri)
        assertEquals(0, r.missingMedia)
        assertTrue(r.soundsLost.isEmpty())
        assertFalse(r.defaultSoundLost)
    }

    @Test fun lostWallpaperIsMissingMediaButNotALostSound() = runTest {
        val bytes = backup(listOf(alarmJson(wallpaper = wall(wPath))), media = arrayOf(wPath to ByteArray(2000)))
        val r = applyTo(importer(maxMedia = 1000), bytes)
        assertEquals(ImportResult(1, 1), r)
    }

    @Test fun lostDefaultSoundIsReported() = runTest {
        val uri = { s: String -> JSONObject().put("snoozeMinutes", 12).put("ringtone", JSONObject().put("uri", s)) }
        val bad = applyTo(importer(canOpen = { false }), backup(emptyList(), uri("content://media/9")))
        assertEquals(ImportResult(0, 1, emptyList(), true), bad)
        val ok = applyTo(importer(canOpen = { true }), backup(emptyList(), uri("content://media/9")))
        assertEquals(ImportResult(0, 0), ok)
        val dropped = applyTo(
            importer(maxMedia = 1000),
            backup(emptyList(), JSONObject().put("snoozeMinutes", 12).put("ringtone", file(rPath2)), rPath2 to ByteArray(2000)),
        )
        assertEquals(ImportResult(0, 1, emptyList(), true), dropped)
        assertEquals(ImportResult(0, 0), applyTo(importer(), backup(emptyList())))
    }

    @Test fun importedAlarmIsArmedInAlarmManager() = runTest {
        awaitTandemStartup()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val context: Context = RuntimeEnvironment.getApplication()
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 2, 5, 0, 0, 0, zone).toInstant() // a Monday, 05:00
        val repo = FakeAlarmRepository(emptyList())
        val scheduler = AlarmSchedulerImpl(
            context, repo, FakeRingStateStore(), InMemorySnoozeLedger(),
            now = { now }, zone = { zone }, io = UnconfinedTestDispatcher(),
        )
        val edits = AlarmEdits(repo, scheduler)
        val bytes = backup(listOf(alarmJson(days = listOf("MONDAY")).put("hour", 6).put("minute", 30)))
        applyTo(importer(), bytes, repo, add = { edits.upsert(it) })
        val scheduled = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertEquals(1, scheduled.size)
        val s = scheduled.single()
        assertEquals(ZonedDateTime.of(2026, 3, 2, 6, 30, 0, 0, zone).toInstant().toEpochMilli(), s.triggerAtMs)
        assertEquals("new-1", shadowOf(s.operation!!).savedIntent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID))
        assertNotNull(s.showIntent)
    }

    // --- Golden fixture ---

    @Test fun goldenFixtureEndToEnd() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val settings = FakeSettingsRepository()
        val names = mutableListOf<String?>()
        val zipEntries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(fixture())).use { z ->
            generateSequence { z.nextEntry }.forEach { zipEntries[it.name] = z.readBytes() }
        }
        val r = applyTo(importer(canOpen = { true }), fixture(), repo, settings, names)
        assertEquals(ImportResult(2, 0), r)
        val (work, weekend) = repo.alarms.first()
        assertEquals("Work", work.label)
        assertEquals(6, work.hour); assertEquals(30, work.minute)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), work.days)
        assertTrue(work.enabled)
        assertEquals(VibrationPattern.HEARTBEAT, work.vibration)
        assertEquals(TextPosition.TOP, work.textPosition)
        assertEquals(WallpaperFrame(1.5f, 0.25f, 0.75f), work.wallpaperFrame)
        val wr = Uri.parse(work.ringtoneUri)
        assertEquals("Morning birds.wav", RingtoneImporter.titleOf(filesDir, wr))
        assertTrue(zipEntries.getValue("media/ringtones/0b7c1a52-4f1e-4c55-9d1e-2a6b3c4d5e01-Morning birds.wav").contentEquals(File(wr.path!!).readBytes()))
        assertTrue(zipEntries.getValue(wPath).contentEquals(File(work.wallpaperPath!!).readBytes()))

        assertEquals("Weekend", weekend.label)
        assertEquals(8, weekend.hour); assertEquals(15, weekend.minute)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), weekend.days)
        assertFalse(weekend.enabled)
        assertEquals("content://media/internal/audio/media/42", weekend.ringtoneUri)
        assertNull(weekend.wallpaperPath); assertNull(weekend.wallpaperFrame)
        assertEquals(VibrationPattern.SLOW_WAVE, weekend.vibration)
        assertFalse(weekend.vibrationOn); assertTrue(weekend.soundOn)
        assertEquals(TextPosition.BOTTOM, weekend.textPosition)

        assertEquals(15, settings.snoozeMinutes.first())
        assertEquals(listOf<String?>("Shawn"), names)
        assertEquals(WallpaperFrame(2f, 0.5f, 0.4f), settings.defaultWallpaperFrame.first())
        val dr = Uri.parse(settings.defaultRingtoneUri.first())
        assertEquals("Gentle chime.wav", RingtoneImporter.titleOf(filesDir, dr))
        assertTrue(zipEntries.getValue(rPath2).contentEquals(File(dr.path!!).readBytes()))
        assertTrue(zipEntries.getValue(wPath2).contentEquals(File(settings.defaultWallpaperPath.first()!!).readBytes()))
        stagingEmpty()
    }
}
