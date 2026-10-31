package me.shawnsquire.tandem.ui.settings.health

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.sync.HealthSignals
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.sync.PartnerState
import me.shawnsquire.tandem.ui.MainDispatcherRule
import me.shawnsquire.tandem.ui.alarms.BannerPrefs
import me.shawnsquire.tandem.ui.alarms.HealthBannerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FakeProbe(
    var exact: Boolean = true,
    var fullScreen: Boolean = true,
    var notif: Boolean = true,
    var battery: Boolean = true,
) : HealthProbe {
    override fun exactAlarms() = exact
    override fun fullScreenIntent() = fullScreen
    override fun notifications() = notif
    override fun batteryUnrestricted() = battery
}

class FakeBannerPrefs(var dismissed: Boolean = false) : BannerPrefs {
    var writes = 0
    override fun isDismissed() = dismissed
    override fun setDismissed() { dismissed = true; writes++ }
}

private class TestSignals(
    lastFcm: Instant? = null,
    token: Boolean = true,
) : HealthSignals {
    val lastFcmFlow = MutableStateFlow(lastFcm)
    val tokenFlow = MutableStateFlow(token)
    override val lastFcmAt: Flow<Instant?> = lastFcmFlow
    override val fcmTokenPresent: Flow<Boolean> = tokenFlow
}

private class TestPartner(lastSeen: Instant?, present: Boolean = true) : PartnerRepository {
    override val partner: Flow<PartnerState?> =
        flowOf(if (present) PartnerState("P", emptyList(), null, lastSeen) else null)
}

private val NOW = Instant.parse("2026-11-01T12:00:00Z")
private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

class HealthViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun vm(
        probe: FakeProbe = FakeProbe(),
        signals: TestSignals = TestSignals(),
        partner: PartnerRepository = TestPartner(null, present = false),
    ) = HealthViewModel(probe, signals, partner, clock, flowOf(Unit))

    private suspend fun HealthViewModel.rows(): Map<HealthItem, HealthRow> {
        // Collecting keeps the WhileSubscribed upstream alive.
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).let {
            it.launch { state.collect {} }
        }
        val rows = state.value.rows.associateBy { it.item }
        job.cancel()
        return rows
    }

    @Test fun rowsReflectProbe() = runTest {
        val v = vm(FakeProbe(exact = true, fullScreen = false, notif = true, battery = false))
        val rows = v.rows()
        assertEquals(HealthStatus.OK, rows.getValue(HealthItem.EXACT_ALARMS).status)
        assertEquals(HealthStatus.PROBLEM, rows.getValue(HealthItem.FULL_SCREEN_INTENT).status)
        assertEquals(HealthFix.FULL_SCREEN_INTENT, rows.getValue(HealthItem.FULL_SCREEN_INTENT).fix)
        assertEquals(HealthStatus.OK, rows.getValue(HealthItem.NOTIFICATIONS).status)
        assertNull(rows.getValue(HealthItem.NOTIFICATIONS).fix)
        assertEquals(HealthFix.BATTERY, rows.getValue(HealthItem.BATTERY).fix)
    }

    @Test fun refreshPicksUpProbeChange() = runTest {
        val probe = FakeProbe(battery = false)
        val v = vm(probe)
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { v.state.collect {} }
        assertEquals(HealthStatus.PROBLEM, v.state.value.rows.first { it.item == HealthItem.BATTERY }.status)
        probe.battery = true
        v.refresh()
        assertEquals(HealthStatus.OK, v.state.value.rows.first { it.item == HealthItem.BATTERY }.status)
        job.cancel()
    }

    @Test fun missingNotificationsIsImportant() = runTest {
        val rows = vm(FakeProbe(notif = false)).rows()
        val row = rows.getValue(HealthItem.NOTIFICATIONS)
        assertTrue(row.important)
        assertEquals(HealthFix.NOTIFICATIONS, row.fix)
        assertFalse(vm(FakeProbe(notif = true)).rows().getValue(HealthItem.NOTIFICATIONS).important)
    }

    @Test fun fcmRowsFollowSignals() = runTest {
        val never = vm(signals = TestSignals(lastFcm = null, token = false)).rows()
        assertEquals(HealthStatus.PROBLEM, never.getValue(HealthItem.FCM_TOKEN).status)
        assertEquals(HealthStatus.INFO, never.getValue(HealthItem.LAST_FCM).status)
        assertNull(never.getValue(HealthItem.LAST_FCM).detail)

        val recent = vm(signals = TestSignals(lastFcm = NOW.minusSeconds(3 * 60))).rows()
        assertEquals(HealthStatus.OK, recent.getValue(HealthItem.FCM_TOKEN).status)
        assertEquals("3 min ago", recent.getValue(HealthItem.LAST_FCM).detail)
    }

    @Test fun partnerLastSeenWarnsAfterAnHour() = runTest {
        fun partnerRow(lastSeen: Instant?, present: Boolean = true) =
            vm(partner = TestPartner(lastSeen, present)).let { it }
        val fresh = partnerRow(NOW.minusSeconds(10 * 60)).rows().getValue(HealthItem.PARTNER_SEEN)
        assertEquals(HealthStatus.OK, fresh.status)
        assertEquals("10 min ago", fresh.detail)
        val stale = partnerRow(NOW.minusSeconds(61 * 60)).rows().getValue(HealthItem.PARTNER_SEEN)
        assertEquals(HealthStatus.PROBLEM, stale.status)
        val exactly = partnerRow(NOW.minusSeconds(60 * 60)).rows().getValue(HealthItem.PARTNER_SEEN)
        assertEquals(HealthStatus.OK, exactly.status)
        assertEquals(HealthStatus.INFO, partnerRow(null).rows().getValue(HealthItem.PARTNER_SEEN).status)
        assertEquals(HealthStatus.INFO, partnerRow(null, present = false).rows().getValue(HealthItem.PARTNER_SEEN).status)
    }
}

class HealthBannerViewModelTest {
    @Test fun shownWhenNotificationsMissing() {
        assertTrue(HealthBannerViewModel(FakeProbe(notif = false), FakeBannerPrefs()).visible.value)
    }

    @Test fun shownWhenFullScreenMissing() {
        assertTrue(HealthBannerViewModel(FakeProbe(fullScreen = false), FakeBannerPrefs()).visible.value)
    }

    @Test fun hiddenWhenBothGranted() {
        assertFalse(HealthBannerViewModel(FakeProbe(), FakeBannerPrefs()).visible.value)
    }

    @Test fun hiddenAfterDismissAndPrefsWritten() {
        val prefs = FakeBannerPrefs()
        val vm = HealthBannerViewModel(FakeProbe(notif = false), prefs)
        vm.dismiss()
        assertFalse(vm.visible.value)
        assertTrue(prefs.dismissed)
        assertEquals(1, prefs.writes)
    }

    @Test fun neverReturnsAfterDismissEvenIfStillMissing() {
        val prefs = FakeBannerPrefs()
        val probe = FakeProbe(notif = false)
        HealthBannerViewModel(probe, prefs).dismiss()
        val later = HealthBannerViewModel(probe, prefs)
        assertFalse(later.visible.value)
        later.refresh()
        assertFalse(later.visible.value)
    }

    @Test fun hidesOnRefreshOnceFixed() {
        val probe = FakeProbe(notif = false)
        val vm = HealthBannerViewModel(probe, FakeBannerPrefs())
        assertTrue(vm.visible.value)
        probe.notif = true
        vm.refresh()
        assertFalse(vm.visible.value)
    }
}
