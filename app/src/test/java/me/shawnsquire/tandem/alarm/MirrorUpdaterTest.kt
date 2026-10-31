package me.shawnsquire.tandem.alarm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

private class BrokenSettings(ringtone: String?) : SettingsRepository by FakeSettingsRepository() {
    override val snoozeMinutes: Flow<Int> = flow { throw IllegalStateException("datastore gone") }
    override val defaultRingtoneUri: Flow<String?> = flowOf(ringtone)
}

private class FailingMirror : AlarmMirror by InMemoryAlarmMirror() {
    override fun write(snapshot: MirrorSnapshot) = throw IllegalStateException("commit failed")
    override fun update(transform: (MirrorSnapshot) -> MirrorSnapshot) = throw IllegalStateException("commit failed")
}

class MirrorUpdaterTest {
    private val trigger = Instant.parse("2026-03-02T11:30:00Z")
    private val a = Alarm("a", "Work", 6, 30, emptySet(), true, null, "content://a", "/files/w.jpg")
    private val b = Alarm("b", "Off", 7, 0, emptySet(), false, null, null, null)
    private val old = MirrorSnapshot(listOf(MirroredAlarm(b, null)), snoozeMinutes = 7, defaultRingtoneUri = "content://old")

    @Test
    fun writesTheScheduledAlarmsWhenAttached() = runTest {
        val mirror = InMemoryAlarmMirror(old)
        val settings = FakeSettingsRepository().apply {
            setSnoozeMinutes(15)
            setDefaultRingtoneUri("content://r")
        }
        MirrorUpdater(mirror, isAttached = { true }, settings).onScheduled(listOf(a, b), mapOf("a" to trigger.toEpochMilli()))
        assertEquals(
            MirrorSnapshot(
                listOf(MirroredAlarm(a.copy(wallpaperPath = null), trigger), MirroredAlarm(b, null)),
                snoozeMinutes = 15,
                defaultRingtoneUri = "content://r",
            ),
            mirror.read(),
        )
    }

    @Test
    fun skipsWhenNotAttached() = runTest {
        val mirror = InMemoryAlarmMirror(old)
        MirrorUpdater(mirror, isAttached = { false }, FakeSettingsRepository()).onScheduled(listOf(a), emptyMap())
        assertEquals(old, mirror.read())
    }

    @Test
    fun aFailedSettingsReadKeepsTheMirroredValue() = runTest {
        val mirror = InMemoryAlarmMirror(old)
        MirrorUpdater(mirror, isAttached = { true }, BrokenSettings(ringtone = null)).onScheduled(listOf(a), emptyMap())
        val snapshot = mirror.read()
        assertEquals(7, snapshot.snoozeMinutes)
        assertEquals(null, snapshot.defaultRingtoneUri) // a null ringtone is a value, not a failure
        assertEquals(listOf(MirroredAlarm(a.copy(wallpaperPath = null), null)), snapshot.alarms)
    }

    @Test
    fun anUnreadableMirrorIsReplaced() = runTest {
        // Room is the source once attached, so a corrupt or missing mirror is repaired, not kept.
        val mirror = InMemoryAlarmMirror(initial = null)
        MirrorUpdater(mirror, isAttached = { true }, FakeSettingsRepository()).onScheduled(listOf(b), emptyMap())
        assertEquals(MirrorSnapshot(listOf(MirroredAlarm(b, null)), 10, null), mirror.readOrNull())
    }

    @Test
    fun neverThrows() = runTest {
        MirrorUpdater(FailingMirror(), isAttached = { true }, FakeSettingsRepository()).onScheduled(listOf(a), emptyMap())
    }
}
