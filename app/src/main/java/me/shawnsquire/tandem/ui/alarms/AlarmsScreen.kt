package me.shawnsquire.tandem.ui.alarms

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.fakes.SAMPLE_ALARMS
import me.shawnsquire.tandem.ui.alarmsViewModelFactory
import me.shawnsquire.tandem.ui.amPm
import me.shawnsquire.tandem.ui.formatClock
import me.shawnsquire.tandem.ui.formatWeekdayTime
import me.shawnsquire.tandem.ui.healthBannerViewModelFactory
import me.shawnsquire.tandem.ui.theme.TandemTheme
import java.time.Instant
import java.time.ZoneId

@Composable
fun AlarmsRoute(
    onAddAlarm: () -> Unit,
    onEditAlarm: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Set by the editor's Delete button; consumed once, then [onDeletedConsumed] clears it. */
    deletedAlarmId: String? = null,
    onDeletedConsumed: () -> Unit = {},
    onOpenHealth: () -> Unit = {},
    viewModel: AlarmsViewModel = viewModel(factory = alarmsViewModelFactory),
    bannerViewModel: HealthBannerViewModel = viewModel(factory = healthBannerViewModelFactory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showBanner by bannerViewModel.visible.collectAsStateWithLifecycle()
    // Returning from the Settings screens re-checks, so the card disappears once things are fixed.
    LifecycleResumeEffect(bannerViewModel) {
        bannerViewModel.refresh()
        onPauseOrDispose {}
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val message = stringResource(R.string.alarm_deleted)
    val undo = stringResource(R.string.alarm_deleted_undo)

    LaunchedEffect(deletedAlarmId) {
        if (deletedAlarmId != null) {
            viewModel.delete(deletedAlarmId)
            onDeletedConsumed()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is AlarmsEvent.AlarmDeleted -> {
                    val result = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = undo,
                        withDismissAction = false,
                        duration = SnackbarDuration.Long,
                    )
                    when (result) {
                        SnackbarResult.ActionPerformed -> viewModel.undoDelete(event.alarmId)
                        SnackbarResult.Dismissed -> viewModel.onDeleteSnackbarDismissed(event.alarmId)
                    }
                }
            }
        }
    }

    AlarmsScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onAddAlarm = onAddAlarm,
        onEditAlarm = onEditAlarm,
        onToggleEnabled = viewModel::setEnabled,
        onSkipNext = viewModel::skipNext,
        onUnskip = viewModel::unskip,
        showSetupBanner = showBanner,
        onSetupBannerClick = {
            bannerViewModel.dismiss()
            onOpenHealth()
        },
        onSetupBannerDismiss = bannerViewModel::dismiss,
        modifier = modifier,
    )
}

@Composable
fun AlarmsScreen(
    state: AlarmsUiState,
    snackbarHostState: SnackbarHostState,
    onAddAlarm: () -> Unit,
    onEditAlarm: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onSkipNext: (String) -> Unit,
    onUnskip: (String) -> Unit,
    modifier: Modifier = Modifier,
    showSetupBanner: Boolean = false,
    onSetupBannerClick: () -> Unit = {},
    onSetupBannerDismiss: () -> Unit = {},
) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddAlarm,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.alarms_add)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showSetupBanner) SetupBanner(onSetupBannerClick, onSetupBannerDismiss)
            if (state.loaded && state.rows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.alarms_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    // Leave room so the FAB never covers the last row's switch.
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(state.rows, key = { it.id }) { row ->
                        AlarmRow(
                            row = row,
                            zone = state.zone,
                            is24Hour = is24Hour,
                            onClick = { onEditAlarm(row.id) },
                            onToggleEnabled = { onToggleEnabled(row.id, it) },
                            onSkipNext = { onSkipNext(row.id) },
                            onUnskip = { onUnskip(row.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupBanner(onClick: () -> Unit, onDismiss: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.setup_banner_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.setup_banner_dismiss),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun AlarmRow(
    row: AlarmRowState,
    zone: ZoneId,
    is24Hour: Boolean,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSkipNext: () -> Unit,
    onUnskip: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dim = if (row.enabled) 1f else 0.6f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatClock(row.hour, row.minute, is24Hour),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                )
                amPm(row.hour, is24Hour)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                    )
                }
            }
            Text(
                row.label.ifBlank { stringResource(R.string.alarm_no_label) },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SuggestionChip(onClick = onClick, label = { Text(row.daysText) })
            Text(
                row.ringsInText ?: stringResource(R.string.alarm_off),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.skipped) {
                val next = row.nextRing
                AssistChip(
                    onClick = onUnskip,
                    label = {
                        Text(
                            if (next != null) {
                                stringResource(R.string.alarm_skipped_next, formatWeekdayTime(next, zone, is24Hour))
                            } else {
                                stringResource(R.string.alarm_skipped)
                            },
                        )
                    },
                )
            }
        }
        val switchDescription = stringResource(R.string.alarm_enabled_switch)
        Switch(
            checked = row.enabled,
            onCheckedChange = onToggleEnabled,
            modifier = Modifier.semantics { contentDescription = switchDescription },
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.alarm_more_options))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (row.skipped) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.alarm_unskip)) },
                        onClick = { menuOpen = false; onUnskip() },
                    )
                } else if (row.enabled) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.alarm_skip_next)) },
                        onClick = { menuOpen = false; onSkipNext() },
                    )
                }
            }
        }
    }
}

private val previewState = AlarmsUiState(
    loaded = true,
    zone = ZoneId.of("UTC"),
    rows = listOf(
        AlarmRowState("1", 6, 30, "Work", "Mon–Fri", true, Instant.parse("2026-11-02T06:30:00Z"), "Rings in 7 h 12 min", false, false),
        AlarmRowState("2", 7, 0, "Gym", "Mon, Wed, Fri", true, Instant.parse("2026-11-04T07:00:00Z"), "Rings in 2 d 3 h", true, false),
        AlarmRowState("3", 8, 0, "Weekend", "Weekends", false, null, null, false, false),
    ),
)

@Preview(showBackground = true)
@Composable
private fun AlarmsScreenPreview() {
    TandemTheme {
        AlarmsScreen(previewState, remember { SnackbarHostState() }, {}, {}, { _, _ -> }, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AlarmsScreenEmptyPreview() {
    TandemTheme {
        AlarmsScreen(AlarmsUiState(loaded = true), remember { SnackbarHostState() }, {}, {}, { _, _ -> }, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AlarmsScreenBannerPreview() {
    TandemTheme {
        AlarmsScreen(
            previewState, remember { SnackbarHostState() }, {}, {}, { _, _ -> }, {}, {},
            showSetupBanner = true,
        )
    }
}

/** Sample data from the fakes, mapped with the same function the ViewModel uses. */
@Preview(showBackground = true)
@Composable
private fun AlarmsScreenFromFakesPreview() {
    val state = buildAlarmsState(SAMPLE_ALARMS, Instant.now(), ZoneId.systemDefault())
    TandemTheme {
        AlarmsScreen(state, remember { SnackbarHostState() }, {}, {}, { _, _ -> }, {}, {})
    }
}
