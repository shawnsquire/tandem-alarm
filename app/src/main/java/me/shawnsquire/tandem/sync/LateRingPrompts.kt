package me.shawnsquire.tandem.sync

import android.util.Log
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.LateRing
import me.shawnsquire.tandem.schedule.earlierOccurrenceThatDay
import me.shawnsquire.tandem.schedule.nextOccurrence

/**
 * The question put to the owner: "[partnerName] may still ring at [occurrenceAt]; you moved the alarm
 * to [movedTo]. Ring or skip?" [occurrenceAt] is also when the prompt stops mattering, so the
 * notifier uses it as the timeout deadline.
 */
data class LateRingPrompt(
    val alarmId: String,
    val partnerName: String,
    /** The owner's new time that day (6:00). */
    val movedTo: Instant,
    /** The joiner's old occurrence (6:30). */
    val occurrenceAt: Instant,
)

/** Shows the owner's late-ring question. */
interface LateRingNotifier {
    /** Shows [prompt], replacing any prompt already showing for its alarm. */
    fun post(prompt: LateRingPrompt)

    /** Removes the prompt for [alarmId], if any. */
    fun cancel(alarmId: String)

    /** Ids of the alarms with a prompt showing right now. */
    fun posted(): Set<String>
}

/**
 * Owner side of the late-ring question. When the owner moves a joined alarm earlier and the joiner's
 * phone has not picked the change up, the joiner would ring late at the old time; this asks the owner
 * whether that is wanted. The answer is stored on the owner's own row ([AlarmRepository.setLateRing],
 * I2) and published for the joiner.
 *
 * The prompt's occurrence is pinned to the joiner's copy (what the joiner will actually fire), read
 * from the server at [check] time, not to the pre-edit schedule, so a second edit before the joiner
 * syncs still asks about the right occurrence. Nothing is persisted: showing prompts are found through
 * [LateRingNotifier.posted], and the notifier removes a prompt at its occurrence.
 */
class LateRingPrompts(
    private val alarms: AlarmRepository,
    private val partner: PartnerRepository,
    private val notifier: LateRingNotifier,
    /** Schedules a [check] for the alarm (4b: unique per alarm, replacing, delayed). */
    private val enqueueCheck: (alarmId: String) -> Unit,
    private val cancelCheck: (alarmId: String) -> Unit,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    enum class CheckResult { DONE, RETRY }

    /**
     * Called by the editor after a save commits. The newest edit wins: any prompt showing is removed,
     * and a check is queued only when the edit moved an upcoming occurrence earlier. A new alarm, a
     * linked row or a save that leaves hour, minute and days alone changes nothing.
     */
    fun onEdited(previous: Alarm?, saved: Alarm) {
        try {
            if (previous == null || previous.link != null || saved.link != null) return
            if (previous.hour == saved.hour && previous.minute == saved.minute && previous.days == saved.days) return
            notifier.cancel(saved.id)
            val x = nextOccurrence(previous, clock.instant(), clock.zone)
            if (x != null && saved.enabled && earlierOccurrenceThatDay(saved, x, clock.zone) != null) {
                enqueueCheck(saved.id)
            } else {
                cancelCheck(saved.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "late-ring edit hook for ${saved.id} failed", e)
        }
    }

    /** Asks the server what the joiner still has and posts the prompt when it would ring late. */
    suspend fun check(alarmId: String): CheckResult {
        val alarm = alarms.get(alarmId)
        if (alarm == null || alarm.link != null || !alarm.enabled) return CheckResult.DONE
        val state = partner.readFromServer() ?: return CheckResult.RETRY
        val link = state.links.firstOrNull { it.ownerAlarmId == alarmId } ?: return CheckResult.DONE
        val hour = link.hour
        val minute = link.minute
        val days = link.days
        // An older partner build does not publish its copy; never prompt on unknown.
        if (hour == null || minute == null || days == null) return CheckResult.DONE
        if (matches(alarm, hour, minute, days)) return CheckResult.DONE
        val joiner = alarm.copy(
            hour = hour, minute = minute, days = days, enabled = true,
            skippedOccurrenceAt = null, soloSkippedOccurrenceAt = null, lateRing = null,
        )
        val xj = nextOccurrence(joiner, clock.instant(), clock.zone) ?: return CheckResult.DONE
        // Moved later, or that day removed: the joiner suppresses its late ring anyway.
        val movedTo = earlierOccurrenceThatDay(alarm, xj, clock.zone) ?: return CheckResult.DONE
        if (alarm.lateRing?.occurrenceAt == xj) return CheckResult.DONE
        notifier.post(LateRingPrompt(alarmId, state.displayName.ifBlank { FALLBACK_NAME }, movedTo, xj))
        return CheckResult.DONE
    }

    /** The owner's tap on the prompt's Ring or Skip action for [occurrenceAt]. */
    suspend fun answer(alarmId: String, occurrenceAt: Instant, ring: Boolean) {
        notifier.cancel(alarmId)
        if (!clock.instant().isBefore(occurrenceAt)) return
        val alarm = alarms.get(alarmId)
        if (alarm == null || alarm.link != null) return
        alarms.setLateRing(alarmId, LateRing(occurrenceAt, ring))
    }

    /** Removes showing prompts once the partner confirms the new schedule or the alarm or link is gone. */
    fun start() {
        scope.launch {
            partner.partner
                .catch { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "late-ring watcher input failed", e)
                }
                .collect { state ->
                    // Null is unknown (unpaired, not received yet, listener error): cancel nothing.
                    if (state == null) return@collect
                    try {
                        sweep(state)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "late-ring sweep failed", e)
                    }
                }
        }
    }

    private suspend fun sweep(state: PartnerState) {
        for (id in notifier.posted()) {
            val alarm = alarms.get(id)
            val link = state.links.firstOrNull { it.ownerAlarmId == id }
            val hour = link?.hour
            val minute = link?.minute
            val days = link?.days
            val gone = alarm == null || alarm.link != null || link == null
            val confirmed = alarm != null && hour != null && minute != null && days != null &&
                matches(alarm, hour, minute, days)
            if (gone || confirmed) notifier.cancel(id)
        }
    }

    private fun matches(alarm: Alarm, hour: Int, minute: Int, days: Set<DayOfWeek>) =
        alarm.hour == hour && alarm.minute == minute && alarm.days == days

    private companion object {
        const val TAG = "LateRingPrompts"
        const val FALLBACK_NAME = "Your partner"
    }
}
