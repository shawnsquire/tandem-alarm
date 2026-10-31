package me.shawnsquire.tandem.ui.settings.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import me.shawnsquire.tandem.sync.HealthSignals
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.ui.partner.ASLEEP_AFTER
import me.shawnsquire.tandem.ui.partner.formatLastSeen
import java.time.Clock
import java.time.Duration
import java.time.Instant

enum class HealthItem { EXACT_ALARMS, FULL_SCREEN_INTENT, NOTIFICATIONS, BATTERY, FCM_TOKEN, LAST_FCM, PARTNER_SEEN }

/** WARNING is calm (not an error): something worth knowing that the user cannot fix, like a sleeping partner phone. */
enum class HealthStatus { OK, PROBLEM, INFO, WARNING }

enum class HealthFix { EXACT_ALARMS, FULL_SCREEN_INTENT, NOTIFICATIONS, BATTERY }

data class HealthRow(
    val item: HealthItem,
    val status: HealthStatus,
    /** Marked "Required" in the UI: without it the alarm can't be stopped from its notification. */
    val important: Boolean = false,
    /** Relative time for the two timestamp rows ("3 min ago"); null when there is none. */
    val detail: String? = null,
    val fix: HealthFix? = null,
)

data class HealthUiState(val rows: List<HealthRow> = emptyList())

private data class Snapshot(
    val exact: Boolean,
    val fullScreen: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
)

class HealthViewModel(
    private val probe: HealthProbe,
    signals: HealthSignals,
    partnerRepository: PartnerRepository,
    private val clock: Clock,
    /** Must emit once immediately, then on each refresh (see tickerFlow). */
    ticker: Flow<Unit>,
) : ViewModel() {

    private val snapshot = MutableStateFlow(read())

    /** Re-reads the system switches; the screen calls this on every resume so returning from Settings updates rows. */
    fun refresh() {
        snapshot.value = read()
    }

    private fun read() = Snapshot(
        probe.exactAlarms(), probe.fullScreenIntent(), probe.notifications(), probe.batteryUnrestricted(),
    )

    val state: StateFlow<HealthUiState> = combine(
        snapshot,
        signals.lastFcmAt,
        signals.fcmTokenPresent,
        partnerRepository.partner,
        ticker,
    ) { s, lastFcm, tokenPresent, partner, _ ->
        val now = clock.instant()
        HealthUiState(
            listOf(
                flag(HealthItem.EXACT_ALARMS, s.exact, HealthFix.EXACT_ALARMS),
                flag(HealthItem.FULL_SCREEN_INTENT, s.fullScreen, HealthFix.FULL_SCREEN_INTENT),
                flag(HealthItem.NOTIFICATIONS, s.notifications, HealthFix.NOTIFICATIONS, important = true),
                flag(HealthItem.BATTERY, s.battery, HealthFix.BATTERY),
                flag(HealthItem.FCM_TOKEN, tokenPresent, null),
                HealthRow(HealthItem.LAST_FCM, HealthStatus.INFO, detail = lastFcm?.let { age(it, now) }),
                partnerRow(partner?.lastSeen, now),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthUiState())

    private fun flag(item: HealthItem, ok: Boolean, fix: HealthFix?, important: Boolean = false) =
        if (ok) HealthRow(item, HealthStatus.OK)
        else HealthRow(item, HealthStatus.PROBLEM, important = important, fix = fix)

    private fun partnerRow(lastSeen: Instant?, now: Instant): HealthRow {
        if (lastSeen == null) return HealthRow(HealthItem.PARTNER_SEEN, HealthStatus.INFO)
        val age = Duration.between(lastSeen, now)
        return HealthRow(
            HealthItem.PARTNER_SEEN,
            if (age > ASLEEP_AFTER) HealthStatus.WARNING else HealthStatus.OK,
            detail = formatLastSeen(age),
        )
    }

    private fun age(at: Instant, now: Instant) = formatLastSeen(Duration.between(at, now))
}
