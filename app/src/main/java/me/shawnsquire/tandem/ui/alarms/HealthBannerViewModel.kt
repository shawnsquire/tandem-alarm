package me.shawnsquire.tandem.ui.alarms

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.shawnsquire.tandem.ui.settings.health.HealthProbe

/** Remembers that the one-time setup banner was dismissed. Ui-owned; not part of SettingsRepository. */
interface BannerPrefs {
    fun isDismissed(): Boolean
    fun setDismissed()
}

/** Drives the one-time "finish setup" card on the Alarms tab. */
class HealthBannerViewModel(
    private val probe: HealthProbe,
    private val prefs: BannerPrefs,
) : ViewModel() {

    private val _visible = MutableStateFlow(compute())
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Re-checks the system state; the screen calls this on every resume. */
    fun refresh() {
        _visible.value = compute()
    }

    /** Close icon, or tapping through to the Health screen: either way it never returns. */
    fun dismiss() {
        prefs.setDismissed()
        _visible.value = false
    }

    private fun compute() = !prefs.isDismissed() && (!probe.notifications() || !probe.fullScreenIntent())
}
