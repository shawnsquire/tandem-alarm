package me.shawnsquire.tandem.ui.settings.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.ui.healthViewModelFactory
import me.shawnsquire.tandem.ui.theme.TandemTheme

@Composable
fun HealthRoute(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HealthViewModel = viewModel(factory = healthViewModelFactory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Returning from a Settings screen lands here, so every resume re-reads the switches.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    // A denied request (or one the system won't show any more) falls through to the app's notification settings.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.refresh() else startSettingsSafely(context, notificationSettingsIntent(context))
    }

    HealthScreen(
        state = state,
        onBack = onBack,
        onFix = { fix ->
            when (fix) {
                HealthFix.EXACT_ALARMS -> startSettingsSafely(context, exactAlarmsIntent(context))
                HealthFix.FULL_SCREEN_INTENT -> startSettingsSafely(context, fullScreenIntentSettingsIntent(context))
                HealthFix.BATTERY -> startSettingsSafely(context, batteryIntent())
                HealthFix.NOTIFICATIONS ->
                    // Permission already held means notifications were switched off in settings; the dialog can't help.
                    if (notificationPermissionGranted(context)) {
                        startSettingsSafely(context, notificationSettingsIntent(context))
                    } else {
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(
    state: HealthUiState,
    onBack: () -> Unit,
    onFix: (HealthFix) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Full-screen route: MainScreen applies no padding, so this Scaffold handles the system-bar insets.
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_health)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            state.rows.forEach { HealthRowItem(it, onFix) }
        }
    }
}

@Composable
private fun HealthRowItem(row: HealthRow, onFix: (HealthFix) -> Unit) {
    val fix = row.fix
    val statusLabel = stringResource(
        when (row.status) {
            HealthStatus.OK -> R.string.health_status_ok
            HealthStatus.PROBLEM -> R.string.health_status_problem
            HealthStatus.INFO -> R.string.health_status_info
            HealthStatus.WARNING -> R.string.health_status_warning
        },
    )
    ListItem(
        leadingContent = {
            when (row.status) {
                HealthStatus.OK -> Icon(Icons.Default.CheckCircle, statusLabel, tint = okColor())
                HealthStatus.PROBLEM -> Icon(Icons.Default.Warning, statusLabel, tint = MaterialTheme.colorScheme.error)
                HealthStatus.INFO -> Icon(Icons.Default.Info, statusLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                HealthStatus.WARNING -> Icon(Icons.Default.Info, statusLabel, tint = MaterialTheme.colorScheme.tertiary)
            }
        },
        headlineContent = { Text(title(row.item)) },
        supportingContent = {
            Text(description(row))
            if (row.important) {
                Text(
                    stringResource(R.string.health_notif_required),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        trailingContent = if (fix != null) {
            { FilledTonalButton(onClick = { onFix(fix) }) { Text(stringResource(R.string.health_fix)) } }
        } else null,
    )
}

/** Material 3 has no success role, so use a fixed green that keeps contrast on light and dark surfaces. */
@Composable
private fun okColor(): Color = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

@Composable
private fun title(item: HealthItem): String = stringResource(
    when (item) {
        HealthItem.EXACT_ALARMS -> R.string.health_exact_title
        HealthItem.FULL_SCREEN_INTENT -> R.string.health_fsi_title
        HealthItem.NOTIFICATIONS -> R.string.health_notif_title
        HealthItem.BATTERY -> R.string.health_battery_title
        HealthItem.FCM_TOKEN -> R.string.health_token_title
        HealthItem.LAST_FCM -> R.string.health_lastfcm_title
        HealthItem.PARTNER_SEEN -> R.string.health_partner_title
        HealthItem.STORAGE_REACHABLE -> R.string.health_storage_title
        HealthItem.LINKED_MEDIA -> R.string.health_linkedmedia_title
    },
)

@Composable
private fun description(row: HealthRow): String {
    val ok = row.status == HealthStatus.OK
    return when (row.item) {
        HealthItem.EXACT_ALARMS -> stringResource(if (ok) R.string.health_exact_ok else R.string.health_exact_bad)
        HealthItem.FULL_SCREEN_INTENT -> stringResource(if (ok) R.string.health_fsi_ok else R.string.health_fsi_bad)
        HealthItem.NOTIFICATIONS -> stringResource(if (ok) R.string.health_notif_ok else R.string.health_notif_bad)
        HealthItem.BATTERY -> stringResource(if (ok) R.string.health_battery_ok else R.string.health_battery_bad)
        HealthItem.FCM_TOKEN -> stringResource(if (ok) R.string.health_token_ok else R.string.health_token_bad)
        HealthItem.LAST_FCM -> row.detail ?: stringResource(R.string.health_lastfcm_never)
        HealthItem.PARTNER_SEEN -> when {
            row.detail == null -> stringResource(R.string.health_partner_none)
            ok -> row.detail
            else -> row.detail + ". " + stringResource(R.string.health_partner_stale)
        }
        HealthItem.STORAGE_REACHABLE -> stringResource(
            if (ok) R.string.health_storage_ok else R.string.health_storage_bad,
        ) + (row.detail?.let { " ($it)" } ?: "")
        HealthItem.LINKED_MEDIA -> row.detail ?: stringResource(R.string.health_linkedmedia_ok)
    }
}

private fun sampleRows(allOk: Boolean) = listOf(
    HealthRow(HealthItem.EXACT_ALARMS, HealthStatus.OK),
    if (allOk) HealthRow(HealthItem.FULL_SCREEN_INTENT, HealthStatus.OK)
    else HealthRow(HealthItem.FULL_SCREEN_INTENT, HealthStatus.PROBLEM, fix = HealthFix.FULL_SCREEN_INTENT),
    if (allOk) HealthRow(HealthItem.NOTIFICATIONS, HealthStatus.OK)
    else HealthRow(HealthItem.NOTIFICATIONS, HealthStatus.PROBLEM, important = true, fix = HealthFix.NOTIFICATIONS),
    if (allOk) HealthRow(HealthItem.BATTERY, HealthStatus.OK)
    else HealthRow(HealthItem.BATTERY, HealthStatus.PROBLEM, fix = HealthFix.BATTERY),
    HealthRow(HealthItem.FCM_TOKEN, HealthStatus.OK),
    HealthRow(HealthItem.LAST_FCM, HealthStatus.INFO, detail = "3 min ago"),
    if (allOk) HealthRow(HealthItem.PARTNER_SEEN, HealthStatus.OK, detail = "5 min ago")
    else HealthRow(HealthItem.PARTNER_SEEN, HealthStatus.WARNING, detail = "3 h ago"),
)

@Preview(showBackground = true)
@Composable
private fun HealthAllOkPreview() {
    TandemTheme { HealthScreen(HealthUiState(sampleRows(true)), {}, {}) }
}

@Preview(showBackground = true)
@Composable
private fun HealthProblemsPreview() {
    TandemTheme { HealthScreen(HealthUiState(sampleRows(false)), {}, {}) }
}
