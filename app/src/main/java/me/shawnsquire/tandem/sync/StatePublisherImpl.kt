package me.shawnsquire.tandem.sync

import android.util.Log
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.withoutExpiredSkip
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.FIRE_GRACE
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
    /** This phone's links (LinkRepository.links), published as the device doc's `links`. */
    private val links: Flow<List<PublishedLink>>,
    /**
     * The uploaded media refs (MediaPublisher.published), published as `alarms[].media` and the default
     * wallpaper; null means none published yet. May emit one transient value with a previous household's
     * refs right after a re-pair: the 1 s debounce below collapses it into the following value, so it is
     * never written (see StatePublisherImplTest).
     */
    private val media: Flow<PublishedMedia?>,
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
        val links: List<PublishedLink>,
        val media: PublishedMedia?,
    )

    private class Publish(val household: Household, val data: Map<String, Any?>)

    private var started = false

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun start() {
        if (started) return
        started = true
        scope.launch {
            // Own alarms only: linked rows reach the partner through `links`, never as alarms[].
            combine(
                listOf(alarms.ownAlarms, ringState.state, household, displayName, fcmToken, links, media),
            ) { v ->
                @Suppress("UNCHECKED_CAST")
                Inputs(
                    v[0] as List<Alarm>, v[1] as RingState?, v[2] as Household?, v[3] as String,
                    v[4] as String?, v[5] as List<PublishedLink>, v[6] as PublishedMedia?,
                )
            }
                .transformLatest { i ->
                    // Unpaired emits null so it replaces (cancels) any write pending in the debounce.
                    val h = i.household ?: return@transformLatest emit(null)
                    while (true) {
                        val now = clock.instant()
                        // Room applies skip expiry only when it emits, so apply it here on the clock.
                        val current = i.alarms.map { it.withoutExpiredSkip(now) }
                        emit(
                            Publish(
                                h,
                                deviceDocMap(i.displayName, i.fcmToken, current, i.ring, now, clock.zone, i.links, i.media),
                            ),
                        )
                        val boundary = nextPublishBoundary(current, now, clock.zone) ?: awaitCancellation()
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

    /**
     * Heartbeat: rewrites the device doc from the current inputs (fresh `updatedAt`, `nextOccurrenceAt`
     * in the live zone, current token) with one merge write, bypassing the pipeline's de-duplication.
     * True when the server acknowledged the write; false when unpaired or the write failed. Only the
     * caller's own cancellation propagates.
     */
    suspend fun publishNow(): Boolean = try {
        val h = household.first()
        if (h == null) {
            false
        } else {
            val now = clock.instant()
            // links/media must ride along: the merge write replaces `alarms` whole and would otherwise
            // drop alarms[].media. Bounded wait; on timeout null leaves the stored top-level field alone.
            val publishedLinks = withTimeoutOrNull(INPUT_WAIT_MS) { links.firstOrNull() }
            val publishedMedia = withTimeoutOrNull(INPUT_WAIT_MS) { media.firstOrNull() }
            val data = deviceDocMap(
                displayName.first(), fcmToken.first(),
                alarms.ownAlarms.first().map { it.withoutExpiredSkip(now) },
                ringState.state.first(), now, clock.zone, publishedLinks, publishedMedia,
            )
            writer.writeAndAwait(h, data)
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "device doc heartbeat failed", e)
        false
    }

    private companion object {
        const val TAG = "StatePublisher"
        const val UPDATED_AT = "updatedAt"
        const val INPUT_WAIT_MS = 2_000L
    }
}

/**
 * Earliest moment the published state changes by itself: the soonest next occurrence over [alarms],
 * a skip still in the future, or a solo skip's X (the published field drops) and X + [FIRE_GRACE]
 * (an own one-shot reads as off). Null when nothing is upcoming.
 */
internal fun nextPublishBoundary(alarms: List<Alarm>, now: Instant, zone: ZoneId): Instant? =
    alarms.flatMap { a ->
        val solo = a.soloSkippedOccurrenceAt
        listOfNotNull(
            nextOccurrence(a, now, zone),
            a.skippedOccurrenceAt?.takeIf { it > now },
            solo?.takeIf { it > now },
            solo?.plus(FIRE_GRACE.toJavaDuration())?.takeIf { it > now },
        )
    }.minOrNull()
