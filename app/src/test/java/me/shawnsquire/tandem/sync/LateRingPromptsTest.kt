package me.shawnsquire.tandem.sync

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeLateRingNotifier
import me.shawnsquire.tandem.fakes.FakePartnerRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LateRing
import me.shawnsquire.tandem.schedule.LinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LateRingPromptsTest {
    private val weekdays = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    /** 2026-03-02 is a Monday. */
    private fun at(time: String, day: String = "2026-03-02") = Instant.parse("${day}T$time:00Z")

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private fun alarm(hour: Int = 6, minute: Int = 0, days: Set<DayOfWeek> = weekdays) = Alarm(
        id = "a1", label = "Wake", hour = hour, minute = minute, days = days, enabled = true,
        skippedOccurrenceAt = null, ringtoneUri = null, wallpaperPath = null,
    )

    private fun linkedRow() = alarm().copy(
        id = "row", link = AlarmLink("owner", "a1", LinkMode.RING, null, true, null, null, null, null),
    )

    private fun published(hour: Int? = 6, minute: Int? = 30, days: Set<DayOfWeek>? = weekdays) =
        PublishedLink("a1", LinkMode.RING, hour, minute, days)

    private fun state(vararg links: PublishedLink, name: String = "Alex") =
        PartnerState(displayName = name, alarms = emptyList(), ringing = null, lastSeen = null, links = links.toList())

    private inner class Env(
        owner: Alarm = alarm(),
        now: Instant = at("05:50"),
        partnerState: PartnerState? = state(published()),
        paired: Boolean = true,
    ) {
        val clock = MutableClock(now)
        val alarms = FakeAlarmRepository(listOf(owner))
        val partner = FakePartnerRepository(null).also { it.serverState = partnerState }
        val notifier = FakeLateRingNotifier()
        var household: Flow<Household?> = flowOf(if (paired) Household("h", "me", "them") else null)
        /** What the server confirms when the household flow says null. */
        var confirm: HouseholdCheck = HouseholdCheck.Unpaired
        var confirms = 0
        val enqueued = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        fun prompts(scope: kotlinx.coroutines.CoroutineScope) = LateRingPrompts(
            alarms, partner, household, { confirms++; confirm }, notifier, { enqueued += it }, { cancelled += it }, clock, scope,
        )
    }

    // ---- onEdited ----

    @Test fun `earlier move enqueues a check and clears any showing prompt`() = runTest {
        val env = Env()
        env.notifier.post(LateRingPrompt("a1", "Alex", at("05:30"), at("06:30")))
        env.prompts(this).onEdited(alarm(6, 30), alarm(6, 0))
        assertEquals(listOf("a1"), env.enqueued)
        assertTrue(env.cancelled.isEmpty())
        assertTrue(env.notifier.posted().isEmpty())
    }

    @Test fun `later move enqueues and the check posts nothing`() = runTest {
        val env = Env(owner = alarm(7, 0))
        val prompts = env.prompts(this)
        prompts.onEdited(alarm(6, 30), alarm(7, 0))
        assertEquals(listOf("a1"), env.enqueued)
        assertTrue(env.cancelled.isEmpty())
        assertEquals(LateRingPrompts.CheckResult.DONE, prompts.check("a1"))
        assertTrue(env.notifier.posts.isEmpty())
    }

    @Test fun `every schedule edit of an enabled alarm enqueues and clears the prompt`() = runTest {
        val env = Env()
        val prompts = env.prompts(this)
        prompts.onEdited(alarm(6, 30), alarm(6, 0))
        env.notifier.post(LateRingPrompt("a1", "Alex", at("06:00"), at("06:30")))
        prompts.onEdited(alarm(6, 0), alarm(5, 30))
        assertEquals(listOf("a1", "a1"), env.enqueued)
        assertTrue(env.notifier.posted().isEmpty())
        prompts.onEdited(alarm(5, 30), alarm(7, 0))
        assertEquals(listOf("a1", "a1", "a1"), env.enqueued)
        assertTrue(env.cancelled.isEmpty())
    }

    @Test fun `skip then a second edit toward the partner's copy asks again`() = runTest {
        val env = Env(owner = alarm(6, 0))
        val prompts = env.prompts(this)
        prompts.onEdited(alarm(6, 30), alarm(6, 0))
        assertEquals(listOf("a1"), env.enqueued)
        prompts.check("a1")
        assertEquals(listOf(LateRingPrompt("a1", "Alex", at("06:00"), at("06:30"))), env.notifier.posts)
        prompts.answer("a1", at("06:30"), ring = false)
        assertEquals(LateRing(at("06:30"), false), env.alarms.get("a1")!!.lateRing)
        // Second edit 6:00 -> 6:15; the editor clears the answer.
        env.alarms.upsert(alarm(6, 15))
        prompts.onEdited(alarm(6, 0).copy(lateRing = LateRing(at("06:30"), false)), alarm(6, 15))
        assertEquals(listOf("a1", "a1"), env.enqueued)
        assertTrue(env.cancelled.isEmpty())
        assertTrue(env.notifier.posted().isEmpty())
        prompts.check("a1")
        val prompt = env.notifier.posts.last()
        assertEquals(at("06:30"), prompt.occurrenceAt)
        assertEquals(at("06:15"), prompt.movedTo)
        assertEquals(2, env.notifier.posts.size)
    }

    @Test fun `edit after the new time passed still enqueues`() = runTest {
        val env = Env(now = at("06:15"))
        env.prompts(this).onEdited(alarm(6, 30), alarm(6, 0))
        assertEquals(listOf("a1"), env.enqueued)
    }

    @Test fun `label-only save leaves the check and prompt alone`() = runTest {
        val env = Env()
        env.notifier.post(LateRingPrompt("a1", "Alex", at("06:00"), at("06:30")))
        env.prompts(this).onEdited(alarm(6, 0), alarm(6, 0).copy(label = "New"))
        assertTrue(env.enqueued.isEmpty())
        assertTrue(env.cancelled.isEmpty())
        assertEquals(setOf("a1"), env.notifier.posted())
    }

    @Test fun `new alarm and linked rows do nothing`() = runTest {
        val env = Env()
        val prompts = env.prompts(this)
        prompts.onEdited(null, alarm(6, 0))
        prompts.onEdited(linkedRow(), linkedRow().copy(hour = 5))
        assertTrue(env.enqueued.isEmpty())
        assertTrue(env.cancelled.isEmpty())
    }

    @Test fun `a save that turns the alarm off cancels the check`() = runTest {
        val env = Env()
        env.prompts(this).onEdited(alarm(6, 30), alarm(6, 0).copy(enabled = false))
        assertTrue(env.enqueued.isEmpty())
        assertEquals(listOf("a1"), env.cancelled)
    }

    // ---- check ----

    @Test fun `earlier move with the partner still on the old time posts one prompt`() = runTest {
        val env = Env()
        assertEquals(LateRingPrompts.CheckResult.DONE, env.prompts(this).check("a1"))
        assertEquals(listOf(LateRingPrompt("a1", "Alex", at("06:00"), at("06:30"))), env.notifier.posts)
    }

    @Test fun `no household is done without reading the server or posting`() = runTest {
        val env = Env(paired = false)
        assertEquals(LateRingPrompts.CheckResult.DONE, env.prompts(this).check("a1"))
        assertTrue(env.notifier.posts.isEmpty())
        assertEquals(0, env.partner.serverReads)
    }

    @Test fun `unknown household that the server says is unpaired is done without a read`() = runTest {
        val env = Env(paired = false)
        env.confirm = HouseholdCheck.Unpaired
        assertEquals(LateRingPrompts.CheckResult.DONE, env.prompts(this).check("a1"))
        assertEquals(1, env.confirms)
        assertEquals(0, env.partner.serverReads)
        assertTrue(env.notifier.posts.isEmpty())
    }

    @Test fun `unknown household that the server cannot confirm retries`() = runTest {
        val env = Env(paired = false)
        env.confirm = HouseholdCheck.Unknown
        assertEquals(LateRingPrompts.CheckResult.RETRY, env.prompts(this).check("a1"))
        assertEquals(0, env.partner.serverReads)
        assertTrue(env.notifier.posts.isEmpty())
        assertTrue(env.notifier.cancels.isEmpty())
    }

    @Test fun `household that never emits retries`() = runTest {
        val env = Env()
        env.household = flow { awaitCancellation() }
        assertEquals(LateRingPrompts.CheckResult.RETRY, env.prompts(this).check("a1"))
        assertEquals(0, env.confirms)
        assertEquals(0, env.partner.serverReads)
        assertTrue(env.notifier.posts.isEmpty())
    }

    @Test fun `unknown household that the server says is paired goes on to the read`() = runTest {
        val env = Env(paired = false)
        env.confirm = HouseholdCheck.Paired(Household("h", "me", "them"))
        assertEquals(LateRingPrompts.CheckResult.DONE, env.prompts(this).check("a1"))
        assertEquals(1, env.partner.serverReads)
        assertEquals(1, env.notifier.posts.size)
    }

    @Test fun `a done check cancels a prompt posted earlier`() = runTest {
        val env = Env(partnerState = state(published(6, 0)))
        env.notifier.post(LateRingPrompt("a1", "Alex", at("06:00"), at("06:30")))
        assertEquals(LateRingPrompts.CheckResult.DONE, env.prompts(this).check("a1"))
        assertTrue(env.notifier.posted().isEmpty())
    }

    @Test fun `a done check cancels for a missing, disabled or unpaired alarm`() = runTest {
        val disabled = Env(owner = alarm().copy(enabled = false))
        disabled.notifier.post(LateRingPrompt("a1", "Alex", at("06:00"), at("06:30")))
        disabled.prompts(this).check("a1")
        assertTrue(disabled.notifier.posted().isEmpty())
        val unpaired = Env(paired = false)
        unpaired.notifier.post(LateRingPrompt("a1", "Alex", at("06:00"), at("06:30")))
        unpaired.prompts(this).check("a1")
        assertTrue(unpaired.notifier.posted().isEmpty())
    }

    @Test fun `a retry leaves a showing prompt alone`() = runTest {
        val env = Env(partnerState = null)
        env.notifier.post(LateRingPrompt("a1", "Alex", at("06:00"), at("06:30")))
        assertEquals(LateRingPrompts.CheckResult.RETRY, env.prompts(this).check("a1"))
        assertEquals(setOf("a1"), env.notifier.posted())
    }

    @Test fun `an edit that lands during the server read is judged on the fresh row`() = runTest {
        val env = Env()
        val slowPartner = object : PartnerRepository by env.partner {
            override suspend fun readFromServer(): PartnerState? {
                val state = env.partner.readFromServer()
                env.alarms.upsert(alarm().copy(enabled = false))
                return state
            }
        }
        val prompts = LateRingPrompts(
            env.alarms, slowPartner, env.household, { env.confirm }, env.notifier, {}, {}, env.clock, this,
        )
        env.notifier.post(LateRingPrompt("a1", "Alex", at("06:00"), at("06:30")))
        assertEquals(LateRingPrompts.CheckResult.DONE, prompts.check("a1"))
        assertEquals(1, env.notifier.posts.size)
        assertTrue(env.notifier.posted().isEmpty())
    }

    @Test fun `a fresh row that moved again is the one the prompt describes`() = runTest {
        val env = Env()
        val slowPartner = object : PartnerRepository by env.partner {
            override suspend fun readFromServer(): PartnerState? {
                val state = env.partner.readFromServer()
                env.alarms.upsert(alarm(5, 30))
                return state
            }
        }
        val prompts = LateRingPrompts(
            env.alarms, slowPartner, env.household, { env.confirm }, env.notifier, {}, {}, env.clock, this,
        )
        prompts.check("a1")
        assertEquals(at("05:30"), env.notifier.posts.single().movedTo)
    }

    @Test fun `a watcher that already saw the confirming state clears the prompt right after the post`() = runTest {
        val env = Env()
        env.partner.set(state(published(6, 0)))
        val prompts = LateRingPrompts(
            env.alarms, env.partner, env.household, { env.confirm }, env.notifier, {}, {}, env.clock,
            kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        prompts.start()
        runCurrent()
        assertEquals(LateRingPrompts.CheckResult.DONE, prompts.check("a1"))
        assertEquals(1, env.notifier.posts.size)
        assertTrue(env.notifier.posted().isEmpty())
    }

    @Test fun `a watcher that saw only the old state leaves the prompt`() = runTest {
        val env = Env()
        env.partner.set(state(published()))
        val prompts = LateRingPrompts(
            env.alarms, env.partner, env.household, { env.confirm }, env.notifier, {}, {}, env.clock,
            kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        prompts.start()
        runCurrent()
        prompts.check("a1")
        assertEquals(setOf("a1"), env.notifier.posted())
    }

    @Test fun `blank partner name falls back`() = runTest {
        val env = Env(partnerState = state(published(), name = " "))
        env.prompts(this).check("a1")
        assertEquals("Your partner", env.notifier.posts.single().partnerName)
    }

    @Test fun `partner already on the new time posts nothing`() = runTest {
        val env = Env(partnerState = state(published(6, 0)))
        env.prompts(this).check("a1")
        assertTrue(env.notifier.posts.isEmpty())
    }

    @Test fun `later move posts nothing`() = runTest {
        val env = Env(owner = alarm(7, 0))
        assertEquals(LateRingPrompts.CheckResult.DONE, env.prompts(this).check("a1"))
        assertTrue(env.notifier.posts.isEmpty())
    }

    @Test fun `edit after the new time passed still posts`() = runTest {
        val env = Env(now = at("06:15"))
        env.prompts(this).check("a1")
        assertEquals(at("06:30"), env.notifier.posts.single().occurrenceAt)
    }

    @Test fun `unknown schedule posts nothing`() = runTest {
        val env = Env(partnerState = state(published(null, null, null)))
        assertEquals(LateRingPrompts.CheckResult.DONE, env.prompts(this).check("a1"))
        assertTrue(env.notifier.posts.isEmpty())
    }

    @Test fun `absent link posts nothing`() = runTest {
        val env = Env(partnerState = state())
        assertEquals(LateRingPrompts.CheckResult.DONE, env.prompts(this).check("a1"))
        assertTrue(env.notifier.posts.isEmpty())
    }

    @Test fun `failed server read retries and posts nothing`() = runTest {
        val env = Env(partnerState = null)
        assertEquals(LateRingPrompts.CheckResult.RETRY, env.prompts(this).check("a1"))
        assertTrue(env.notifier.posts.isEmpty())
    }

    @Test fun `missing, linked and disabled alarms are done`() = runTest {
        val missing = Env()
        assertEquals(LateRingPrompts.CheckResult.DONE, missing.prompts(this).check("nope"))
        val disabled = Env(owner = alarm().copy(enabled = false))
        assertEquals(LateRingPrompts.CheckResult.DONE, disabled.prompts(this).check("a1"))
        val linked = Env(owner = linkedRow().copy(id = "a1"))
        assertEquals(LateRingPrompts.CheckResult.DONE, linked.prompts(this).check("a1"))
        assertTrue(missing.notifier.posts.isEmpty() && disabled.notifier.posts.isEmpty() && linked.notifier.posts.isEmpty())
    }

    @Test fun `already answered for that occurrence posts nothing`() = runTest {
        val env = Env(owner = alarm().copy(lateRing = LateRing(at("06:30"), false)))
        env.prompts(this).check("a1")
        assertTrue(env.notifier.posts.isEmpty())
    }

    @Test fun `an answer for another occurrence does not suppress the prompt`() = runTest {
        val env = Env(owner = alarm().copy(lateRing = LateRing(at("06:30", "2026-03-01"), false)))
        env.prompts(this).check("a1")
        assertEquals(1, env.notifier.posts.size)
    }

    @Test fun `after the old time passed the prompt is never about a past occurrence`() = runTest {
        val env = Env(now = at("06:40"))
        env.prompts(this).check("a1")
        assertTrue(env.notifier.posts.all { it.occurrenceAt > env.clock.now })
    }

    @Test fun `prompt pins to the partner's copy after a second edit`() = runTest {
        // Edited 6:30 -> 6:00 -> 5:30; the partner still has 6:30.
        val env = Env(owner = alarm(5, 30))
        env.prompts(this).check("a1")
        val prompt = env.notifier.posts.single()
        assertEquals(at("05:30"), prompt.movedTo)
        assertEquals(at("06:30"), prompt.occurrenceAt)
    }

    // ---- answer ----

    @Test fun `skip stores a declined late ring and cancels the prompt`() = runTest {
        val env = Env()
        env.notifier.post(LateRingPrompt("a1", "Alex", at("06:00"), at("06:30")))
        env.prompts(this).answer("a1", at("06:30"), ring = false)
        assertEquals(LateRing(at("06:30"), false), env.alarms.get("a1")!!.lateRing)
        assertTrue(env.notifier.posted().isEmpty())
    }

    @Test fun `ring stores an explicit ring`() = runTest {
        val env = Env()
        env.prompts(this).answer("a1", at("06:30"), ring = true)
        assertEquals(LateRing(at("06:30"), true), env.alarms.get("a1")!!.lateRing)
    }

    @Test fun `answer at or after the occurrence stores nothing but cancels`() = runTest {
        val env = Env(now = at("06:30"))
        env.notifier.post(LateRingPrompt("a1", "Alex", at("06:00"), at("06:30")))
        env.prompts(this).answer("a1", at("06:30"), ring = false)
        assertNull(env.alarms.get("a1")!!.lateRing)
        assertTrue(env.notifier.posted().isEmpty())
    }

    @Test fun `answer for a missing alarm stores nothing`() = runTest {
        val env = Env()
        env.prompts(this).answer("nope", at("06:30"), ring = false)
        assertNull(env.alarms.get("nope"))
        assertEquals(listOf("nope"), env.notifier.cancels)
    }

    // ---- watcher ----

    private fun TestScope.watched(env: Env): LateRingPrompts {
        val prompts = LateRingPrompts(
            env.alarms, env.partner, env.household, { env.confirm }, env.notifier, { env.enqueued += it }, { env.cancelled += it },
            env.clock, kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        env.notifier.post(LateRingPrompt("a1", "Alex", at("06:00"), at("06:30")))
        prompts.start()
        runCurrent()
        return prompts
    }

    @Test fun `partner showing the new schedule cancels the prompt`() = runTest {
        val env = Env()
        watched(env)
        assertEquals(setOf("a1"), env.notifier.posted())
        env.partner.set(state(published()))
        runCurrent()
        assertEquals(setOf("a1"), env.notifier.posted())
        env.partner.set(state(published(6, 0)))
        runCurrent()
        assertTrue(env.notifier.posted().isEmpty())
    }

    @Test fun `link disappearing cancels the prompt`() = runTest {
        val env = Env()
        watched(env)
        env.partner.set(state())
        runCurrent()
        assertTrue(env.notifier.posted().isEmpty())
    }

    @Test fun `alarm deleted cancels the prompt`() = runTest {
        val env = Env()
        watched(env)
        env.alarms.delete("a1")
        env.partner.set(state(published()))
        runCurrent()
        assertTrue(env.notifier.posted().isEmpty())
    }

    @Test fun `null state and unknown schedule cancel nothing`() = runTest {
        val env = Env()
        watched(env)
        env.partner.set(null)
        runCurrent()
        env.partner.set(state(published(null, null, null)))
        runCurrent()
        assertEquals(setOf("a1"), env.notifier.posted())
        assertTrue(env.notifier.cancels.isEmpty())
    }
}
