package me.shawnsquire.tandem.sync

import android.util.Log
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.nextOccurrence

/**
 * Publishes this phone's alarms and ring state to its own device doc while paired (I2: the owner is
 * the only writer). Unpaired it never writes. Republishes shortly after every input change and
 * again just after an occurrence passes, since that changes `nextOccurrenceAt` with no input change.
 */
class StatePublisherImpl(
    private val alarms: AlarmRepository,
    private val ringState: RingStateStore,
    private val household: Flow<Household?>,
    private val displayName: Flow<String>,
    private val fcmToken: Flow<String?>,
    private val writer: DeviceDocWriter,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : StatePublisher {
    private class Inputs(
        val alarms: List<Alarm>,
        val ring: RingState?,
        val household: Household?,
        val displayName: String,
        val fcmToken: String?,
    )

    private class Publish(val household: Household, val data: Map<String, Any?>)

    private var started = false

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(alarms.alarms, ringState.state, household, displayName, fcmToken, ::Inputs)
                .transformLatest { i ->
                    // Unpaired emits null so it replaces (cancels) any write pending in the debounce.
                    val h = i.household ?: return@transformLatest emit(null)
                    while (true) {
                        val now = clock.instant()
                        emit(Publish(h, deviceDocMap(i.displayName, i.fcmToken, i.alarms, i.ring, now, clock.zone)))
                        val boundary = nextPublishBoundary(i.alarms, now, clock.zone) ?: awaitCancellation()
                        delay(Duration.between(now, boundary.plusSeconds(1)).toMillis().coerceAtLeast(0))
                    }
                }
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) {
                        false
                    } else {
                        Log.w(TAG, "publisher input failed, retrying", cause)
                        delay(1.seconds)
                        true
                    }
                }
                .distinctUntilChanged { a, b ->
                    a?.household == b?.household && a?.data?.minus(UPDATED_AT) == b?.data?.minus(UPDATED_AT)
                }
                .debounce(1.seconds)
                .filterNotNull()
                .collect { p ->
                    try {
                        writer.write(p.household, p.data)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "device doc write failed", e)
                    }
                }
        }
    }

    private companion object {
        const val TAG = "StatePublisher"
        const val UPDATED_AT = "updatedAt"
    }
}

/**
 * Earliest moment the published state changes by itself: the soonest next occurrence over [alarms]
 * or a skip still in the future. Null when nothing is upcoming.
 */
internal fun nextPublishBoundary(alarms: List<Alarm>, now: Instant, zone: ZoneId): Instant? =
    alarms.flatMap { a -> listOfNotNull(nextOccurrence(a, now, zone), a.skippedOccurrenceAt?.takeIf { it > now }) }
        .minOrNull()
