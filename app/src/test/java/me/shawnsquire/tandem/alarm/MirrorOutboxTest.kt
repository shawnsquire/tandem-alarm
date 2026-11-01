package me.shawnsquire.tandem.alarm

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import me.shawnsquire.tandem.awaitTandemStartup
import me.shawnsquire.tandem.schedule.RingMember
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class MirrorOutboxTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun awaitStartup() = awaitTandemStartup()

    private val t = Instant.parse("2026-03-02T06:30:00Z")
    private val until = Instant.parse("2026-03-02T06:40:00Z")
    private val members = listOf(RingMember("a", t), RingMember("b", t.plusSeconds(60)))

    private class Recorder : RingMirrorPort {
        val calls = mutableListOf<MirrorCall>()
        override fun onLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?) {
            calls += MirrorCall(kind, members, snoozedUntil)
        }
    }

    private fun prefs() = context.createDeviceProtectedStorageContext().getSharedPreferences("alarm_mirror_outbox", Context.MODE_PRIVATE)

    @Before fun clean() { prefs().edit().clear().commit() }

    @Test fun queuedWhileDetachedThenDrainedInOrderOnAttach() {
        val port = SwitchableRingMirrorPort(InMemoryMirrorOutbox())
        port.onLocal(MirrorKind.SNOOZE, members, until)
        port.onLocal(MirrorKind.DISMISS, members.take(1), null)
        val rec = Recorder()
        assertTrue(rec.calls.isEmpty())
        port.delegate = rec
        assertEquals(
            listOf(MirrorCall(MirrorKind.SNOOZE, members, until), MirrorCall(MirrorKind.DISMISS, members.take(1), null)),
            rec.calls,
        )
        assertEquals(rec, port.delegate)
    }

    @Test fun forwardedDirectlyAfterAttachAndNotRequeued() {
        val outbox = InMemoryMirrorOutbox()
        val port = SwitchableRingMirrorPort(outbox)
        val rec = Recorder()
        port.delegate = rec
        port.onLocal(MirrorKind.DISMISS, members, null)
        assertEquals(listOf(MirrorCall(MirrorKind.DISMISS, members, null)), rec.calls)
        assertTrue(outbox.entries().isEmpty())
    }

    @Test fun drainedCallsLeaveTheOutboxSoTheyReplayOnlyOnce() {
        val outbox = InMemoryMirrorOutbox()
        val port = SwitchableRingMirrorPort(outbox)
        port.onLocal(MirrorKind.DISMISS, members, null)
        port.delegate = Recorder()
        assertTrue(outbox.entries().isEmpty())
    }

    @Test fun aThrowingDelegateIsRetriedOnceThenTheCallIsDroppedAndTheDrainContinues() {
        val outbox = InMemoryMirrorOutbox()
        val port = SwitchableRingMirrorPort(outbox)
        port.onLocal(MirrorKind.SNOOZE, members, until)
        port.onLocal(MirrorKind.DISMISS, members, null)
        var attempts = 0
        val seen = mutableListOf<MirrorKind>()
        port.delegate = object : RingMirrorPort {
            override fun onLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?) {
                if (kind == MirrorKind.SNOOZE) { attempts++; error("boom") }
                seen += kind
            }
        }
        assertEquals(2, attempts)
        assertEquals(listOf(MirrorKind.DISMISS), seen)
        assertTrue(outbox.entries().isEmpty())
    }

    @Test fun survivesANewInstance() {
        SwitchableRingMirrorPort(SharedPrefsMirrorOutbox(context)).apply {
            onLocal(MirrorKind.SNOOZE, members, until)
            onLocal(MirrorKind.DISMISS, members, null)
        }
        val rec = Recorder()
        SwitchableRingMirrorPort(SharedPrefsMirrorOutbox(context)).delegate = rec
        assertEquals(
            listOf(MirrorCall(MirrorKind.SNOOZE, members, until), MirrorCall(MirrorKind.DISMISS, members, null)),
            rec.calls,
        )
        assertTrue(SharedPrefsMirrorOutbox(context).entries().isEmpty())
    }

    @Test fun aCorruptEntryIsSkippedAndTheRestSurvive() {
        val outbox = SharedPrefsMirrorOutbox(context)
        outbox.append(MirrorCall(MirrorKind.SNOOZE, members, until))
        val good = prefs().getString("calls", null)!!
        // Splice a garbage object and a bad-kind object around the good entry.
        val spliced = "[{\"seq\":90,\"kind\":\"NOPE\",\"members\":[]},{\"junk\":1}," + good.removePrefix("[")
        assertTrue(prefs().edit().putString("calls", spliced).commit())
        assertEquals(listOf(MirrorCall(MirrorKind.SNOOZE, members, until)), outbox.entries().map { it.call })
        outbox.append(MirrorCall(MirrorKind.DISMISS, members, null))
        assertEquals(2, outbox.entries().size)
    }

    @Test fun anUnparseableBlobReadsEmptyAndDoesNotCrash() {
        prefs().edit().putString("calls", "not json").commit()
        val outbox = SharedPrefsMirrorOutbox(context)
        assertTrue(outbox.entries().isEmpty())
        outbox.append(MirrorCall(MirrorKind.DISMISS, members, null))
        assertEquals(1, outbox.entries().size)
    }
}
