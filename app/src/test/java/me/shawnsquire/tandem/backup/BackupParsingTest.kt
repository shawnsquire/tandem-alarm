package me.shawnsquire.tandem.backup

import me.shawnsquire.tandem.backup.BackupRejected.Reason
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.schedule.WallpaperFrame
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.Instant
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
class BackupParsingTest {
    private fun alarmJson(vararg extra: Pair<String, Any?>) = JSONObject()
        .put("label", "A").put("hour", 7).put("minute", 5)
        .also { o -> extra.forEach { (k, v) -> if (v == null) o.remove(k) else o.put(k, v) } }

    private fun manifestJson(
        alarms: JSONArray = JSONArray(),
        settings: Any? = JSONObject(),
        vararg extra: Pair<String, Any?>,
    ) = JSONObject().put("format", "tandem-backup").put("version", 1)
        .put("settings", settings).put("alarms", alarms)
        .also { o -> extra.forEach { (k, v) -> if (v == null) o.remove(k) else o.put(k, v) } }

    private fun parse(o: JSONObject) = parseManifest(o.toString())

    private fun rejects(reason: Reason, json: String) {
        try {
            parseManifest(json)
            fail("expected $reason")
        } catch (e: BackupRejected) {
            assertEquals(reason, e.reason)
        }
    }

    private fun rejects(reason: Reason, o: JSONObject) = rejects(reason, o.toString())

    private fun oneAlarm(vararg extra: Pair<String, Any?>) =
        parse(manifestJson(JSONArray().put(alarmJson(*extra)))).alarms.single()

    // --- Rejections ---

    @Test fun badJsonIsNotABackup() = rejects(Reason.NOT_A_BACKUP, "{nope")

    @Test fun nonObjectIsNotABackup() = rejects(Reason.NOT_A_BACKUP, "[1,2]")

    @Test fun emptyInputIsNotABackup() = rejects(Reason.NOT_A_BACKUP, "")

    @Test fun wrongFormatIsNotABackup() = rejects(Reason.NOT_A_BACKUP, manifestJson().put("format", "other"))

    @Test fun missingFormatIsNotABackup() = rejects(Reason.NOT_A_BACKUP, manifestJson().also { it.remove("format") })

    @Test fun newerVersionIsRejected() = rejects(Reason.NEWER_VERSION, manifestJson().put("version", 2))

    @Test fun versionZeroIsDamaged() = rejects(Reason.DAMAGED, manifestJson().put("version", 0))

    @Test fun missingVersionIsDamaged() = rejects(Reason.DAMAGED, manifestJson().also { it.remove("version") })

    @Test fun nonIntegerVersionIsDamaged() {
        rejects(Reason.DAMAGED, manifestJson().put("version", "1"))
        rejects(Reason.DAMAGED, manifestJson().put("version", 1.5))
    }

    @Test fun missingSettingsIsDamaged() = rejects(Reason.DAMAGED, manifestJson().also { it.remove("settings") })

    @Test fun wrongTypedSettingsIsDamaged() = rejects(Reason.DAMAGED, manifestJson(settings = "x"))

    @Test fun missingAlarmsIsDamaged() = rejects(Reason.DAMAGED, manifestJson().also { it.remove("alarms") })

    @Test fun wrongTypedAlarmsIsDamaged() = rejects(Reason.DAMAGED, manifestJson().put("alarms", JSONObject()))

    @Test fun alarmMissingHourIsDamaged() =
        rejects(Reason.DAMAGED, manifestJson(JSONArray().put(alarmJson("hour" to null))))

    @Test fun alarmMissingMinuteLabelIsDamaged() {
        rejects(Reason.DAMAGED, manifestJson(JSONArray().put(alarmJson("minute" to null))))
        rejects(Reason.DAMAGED, manifestJson(JSONArray().put(alarmJson("label" to null))))
    }

    @Test fun alarmWrongTypedRequiredIsDamaged() {
        rejects(Reason.DAMAGED, manifestJson(JSONArray().put(alarmJson("hour" to "7"))))
        rejects(Reason.DAMAGED, manifestJson(JSONArray().put(alarmJson("label" to 5))))
    }

    @Test fun nonObjectAlarmIsDamaged() = rejects(Reason.DAMAGED, manifestJson(JSONArray().put("x")))

    @Test fun ringtoneWithBothFileAndUriIsDamaged() = rejects(
        Reason.DAMAGED,
        manifestJson(JSONArray().put(alarmJson("ringtone" to JSONObject().put("file", "media/ringtones/a").put("uri", "content://x/1")))),
    )

    @Test fun ringtoneWithNeitherIsDamaged() =
        rejects(Reason.DAMAGED, manifestJson(JSONArray().put(alarmJson("ringtone" to JSONObject().put("title", "t")))))

    @Test fun ringtoneNotAnObjectIsDamaged() =
        rejects(Reason.DAMAGED, manifestJson(JSONArray().put(alarmJson("ringtone" to "x"))))

    @Test fun badRingtonePathsAreDamaged() {
        val bad = listOf(
            "../x", "media/ringtones/a/b", "media/ringtones/", "media/other/x", "media/wallpapers/x",
            "..", "media/ringtones/..", "media/ringtones/.", "media/ringtones/a\\b", "media/ringtones/a\u0000b",
            "media/ringtones/" + "a".repeat(129), "/media/ringtones/a", "",
        )
        for (p in bad) {
            rejects(Reason.DAMAGED, manifestJson(JSONArray().put(alarmJson("ringtone" to JSONObject().put("file", p)))))
            rejects(Reason.DAMAGED, manifestJson(settings = JSONObject().put("ringtone", JSONObject().put("file", p))))
        }
    }

    @Test fun badWallpaperPathsAreDamaged() {
        val bad = listOf("../x", "media/wallpapers/a/b", "media/wallpapers/", "media/ringtones/x", "..", "media/wallpapers/..")
        for (p in bad) {
            rejects(Reason.DAMAGED, manifestJson(JSONArray().put(alarmJson("wallpaper" to JSONObject().put("file", p)))))
            rejects(Reason.DAMAGED, manifestJson(settings = JSONObject().put("wallpaper", JSONObject().put("file", p))))
        }
    }

    @Test fun wallpaperWithoutFileIsDamaged() =
        rejects(Reason.DAMAGED, manifestJson(JSONArray().put(alarmJson("wallpaper" to JSONObject()))))

    @Test fun maxLengthNameIsAccepted() {
        val p = "media/ringtones/" + "a".repeat(128)
        assertEquals(BackupSound.File(p, null), oneAlarm("ringtone" to JSONObject().put("file", p)).ringtone)
    }

    @Test fun tooManyAlarmsIsTooLarge() {
        val many = JSONArray().also { a -> repeat(201) { a.put(alarmJson()) } }
        rejects(Reason.TOO_LARGE, manifestJson(many))
        val ok = JSONArray().also { a -> repeat(200) { a.put(alarmJson()) } }
        assertEquals(200, parse(manifestJson(ok)).alarms.size)
    }

    // --- Coercion and defaults ---

    @Test fun hourAndMinuteAreClamped() {
        val a = oneAlarm("hour" to 25, "minute" to -3)
        assertEquals(23, a.hour)
        assertEquals(0, a.minute)
        val b = oneAlarm("hour" to -1, "minute" to 99)
        assertEquals(0, b.hour)
        assertEquals(59, b.minute)
    }

    @Test fun fractionalHourIsAccepted() {
        assertEquals(7, oneAlarm("hour" to 7.0).hour)
    }

    @Test fun missingOptionalsTakeDefaults() {
        val a = oneAlarm()
        assertEquals(emptyList<DayOfWeek>(), a.days)
        assertTrue(a.enabled)
        assertEquals(VibrationPattern.PULSE, a.vibration)
        assertTrue(a.vibrationOn)
        assertTrue(a.soundOn)
        assertEquals(TextPosition.MIDDLE, a.textPosition)
        assertNull(a.ringtone)
        assertNull(a.wallpaper)
    }

    @Test fun unknownEnumsAndDaysFallBack() {
        val a = oneAlarm(
            "vibration" to "BUZZ", "textPosition" to "LEFT",
            "days" to JSONArray().put("MONDAY").put("FUNDAY").put(3).put("MONDAY").put("SUNDAY"),
        )
        assertEquals(VibrationPattern.PULSE, a.vibration)
        assertEquals(TextPosition.MIDDLE, a.textPosition)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), a.days)
    }

    @Test fun labelIsTrimmedAndCapped() {
        assertEquals("Hi", oneAlarm("label" to "  Hi \n").label)
        assertEquals("x".repeat(200), oneAlarm("label" to "x".repeat(500)).label)
    }

    @Test fun fileSchemeUriRingtoneIsDropped() {
        assertNull(oneAlarm("ringtone" to JSONObject().put("uri", "file:///sdcard/a.mp3")).ringtone)
        assertNull(oneAlarm("ringtone" to JSONObject().put("uri", "http://x/a.mp3")).ringtone)
        assertEquals(
            BackupSound.Uri("content://media/1"),
            oneAlarm("ringtone" to JSONObject().put("uri", "content://media/1")).ringtone,
        )
    }

    @Test fun outOfRangeFrameIsSanitized() {
        val w = JSONObject().put("file", "media/wallpapers/a.jpg")
            .put("frame", JSONObject().put("zoom", 99).put("focusX", -4).put("focusY", 2))
        val f = oneAlarm("wallpaper" to w).wallpaper!!.frame!!
        assertEquals(WallpaperFrame(99f, -4f, 2f).sanitized(), f)
        assertEquals(0f, f.focusX, 0f)
        assertEquals(1f, f.focusY, 0f)
        assertTrue(f.zoom < 99f)
    }

    // 1e39 is a finite double but overflows a float, the only way a non-finite part reaches sanitized().
    @Test fun nonFiniteFramePartBecomesCentered() {
        val json = """{"format":"tandem-backup","version":1,"settings":{"wallpaper":{"file":"media/wallpapers/a.jpg","frame":{"zoom":1e39,"focusX":0.25,"focusY":0.75}}},"alarms":[]}"""
        assertEquals(WallpaperFrame(1f, 0.25f, 0.75f), parseManifest(json).settings.wallpaper!!.frame)
    }

    @Test fun frameMissingPartIsDropped() {
        val w = JSONObject().put("file", "media/wallpapers/a.jpg")
            .put("frame", JSONObject().put("zoom", 2).put("focusX", 0.5))
        assertNull(oneAlarm("wallpaper" to w).wallpaper!!.frame)
    }

    @Test fun snoozeIsClampedOrDefaulted() {
        fun snooze(s: JSONObject) = parse(manifestJson(settings = s)).settings.snoozeMinutes
        assertEquals(60, snooze(JSONObject().put("snoozeMinutes", 999)))
        assertEquals(1, snooze(JSONObject().put("snoozeMinutes", 0)))
        assertEquals(10, snooze(JSONObject()))
        assertEquals(15, snooze(JSONObject().put("snoozeMinutes", 15)))
    }

    @Test fun displayNameIsTrimmedBlankToNullAndCapped() {
        fun name(v: String) = parse(manifestJson(settings = JSONObject().put("displayName", v))).settings.displayName
        assertEquals("Sam", name("  Sam "))
        assertNull(name("   "))
        assertEquals("n".repeat(60), name("n".repeat(100)))
        assertNull(parse(manifestJson()).settings.displayName)
    }

    @Test fun ringtoneTitleIsTrimmedAndCapped() {
        fun title(t: String) = (oneAlarm("ringtone" to JSONObject().put("file", "media/ringtones/a").put("title", t)).ringtone as BackupSound.File).title
        assertEquals("T", title(" T "))
        assertEquals("t".repeat(128), title("t".repeat(300)))
    }

    @Test fun exportedAtAndAppVersionFallBack() {
        val m = parse(manifestJson())
        assertEquals(Instant.EPOCH, m.exportedAt)
        assertEquals("", m.appVersion)
        assertEquals(Instant.EPOCH, parse(manifestJson(extra = arrayOf("exportedAt" to "yesterday"))).exportedAt)
        val ok = parse(manifestJson(extra = arrayOf("exportedAt" to "2026-11-03T12:00:00Z", "appVersion" to "1.2")))
        assertEquals(Instant.parse("2026-11-03T12:00:00Z"), ok.exportedAt)
        assertEquals("1.2", ok.appVersion)
    }

    // --- Round trip and golden fixture ---

    @Test fun exporterShapedManifestRoundTrips() {
        val manifest = BackupManifest(
            exportedAt = Instant.parse("2026-11-03T12:00:00Z"),
            appVersion = "0.4.1",
            settings = BackupSettings(
                snoozeMinutes = 12,
                ringtone = BackupSound.File("media/ringtones/r1-Chime.wav", "Chime.wav"),
                wallpaper = BackupWallpaper("media/wallpapers/w1.jpg", WallpaperFrame(1.3f, 0.3f, 0.7f)),
                displayName = "Shawn",
            ),
            alarms = listOf(
                BackupAlarm(
                    "Work", 6, 30, listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), true,
                    BackupSound.File("media/ringtones/r2.wav", null),
                    BackupWallpaper("media/wallpapers/w2.jpg", null),
                    VibrationPattern.HEARTBEAT, true, false, TextPosition.TOP,
                ),
                BackupAlarm(
                    "", 23, 59, emptyList(), false, BackupSound.Uri("content://media/internal/audio/media/42"), null,
                    VibrationPattern.PULSE, false, true, TextPosition.BOTTOM,
                ),
            ),
        )
        assertEquals(manifest, parseManifest(manifest.toJson().toString()))
    }

    @Test fun goldenFixtureV1Parses() {
        val stream = javaClass.classLoader!!.getResourceAsStream("backup/fixture-v1.zip")!!
        val json = ZipInputStream(stream).use { zip ->
            val entry = generateSequence { zip.nextEntry }.first { it.name == MANIFEST_ENTRY }
            assertEquals(MANIFEST_ENTRY, entry.name)
            zip.readBytes().toString(Charsets.UTF_8)
        }
        val m = parseManifest(json)
        assertEquals(Instant.parse("2026-11-03T12:00:00Z"), m.exportedAt)
        assertEquals("0.4.1", m.appVersion)

        assertEquals(15, m.settings.snoozeMinutes)
        assertEquals("Shawn", m.settings.displayName)
        assertEquals(
            BackupSound.File("media/ringtones/0b7c1a52-4f1e-4c55-9d1e-2a6b3c4d5e02-Gentle chime.wav", "Gentle chime.wav"),
            m.settings.ringtone,
        )
        assertEquals(
            BackupWallpaper("media/wallpapers/5d2e8f10-3a4b-4c5d-8e9f-0a1b2c3d4e02.jpg", WallpaperFrame(2f, 0.5f, 0.4f)),
            m.settings.wallpaper,
        )

        assertEquals(2, m.alarms.size)
        val work = m.alarms[0]
        assertEquals("Work", work.label)
        assertEquals(6, work.hour)
        assertEquals(30, work.minute)
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            work.days,
        )
        assertTrue(work.enabled)
        assertEquals(
            BackupSound.File("media/ringtones/0b7c1a52-4f1e-4c55-9d1e-2a6b3c4d5e01-Morning birds.wav", "Morning birds.wav"),
            work.ringtone,
        )
        assertEquals(
            BackupWallpaper("media/wallpapers/5d2e8f10-3a4b-4c5d-8e9f-0a1b2c3d4e01.jpg", WallpaperFrame(1.5f, 0.25f, 0.75f)),
            work.wallpaper,
        )
        assertEquals(VibrationPattern.HEARTBEAT, work.vibration)
        assertTrue(work.vibrationOn)
        assertTrue(work.soundOn)
        assertEquals(TextPosition.TOP, work.textPosition)

        val weekend = m.alarms[1]
        assertEquals("Weekend", weekend.label)
        assertEquals(8, weekend.hour)
        assertEquals(15, weekend.minute)
        assertEquals(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), weekend.days)
        assertEquals(false, weekend.enabled)
        assertEquals(BackupSound.Uri("content://media/internal/audio/media/42"), weekend.ringtone)
        assertNull(weekend.wallpaper)
        assertEquals(VibrationPattern.SLOW_WAVE, weekend.vibration)
        assertEquals(false, weekend.vibrationOn)
        assertTrue(weekend.soundOn)
        assertEquals(TextPosition.BOTTOM, weekend.textPosition)
    }
}
