package me.shawnsquire.tandem.ui.alarms

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.fakes.SAMPLE_ALARMS
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.ui.alarms.linked.LinkedAlarmSheetRoute
import me.shawnsquire.tandem.ui.alarms.linked.LinkedSwitchOptions
import me.shawnsquire.tandem.ui.alarms.linked.LinkedSwitchQuestion
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
    // The Linked alarm sheet is hosted here; the id survives rotation.
    var openLinkedId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val message = stringResource(R.string.alarm_deleted)
    val undo = stringResource(R.string.alarm_deleted_undo)

    LaunchedEffect(deletedAlarmId) {
        if (deletedAlarmId != null) {
            viewModel.delete(deletedAlarmId)
            onDeletedConsumed()
        }
    }

    // Driven by VM state, so rotation or navigation while the snackbar shows re-shows it.
    val pendingDeleteId by viewModel.pendingDeleteId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDeleteId) {
        val id = pendingDeleteId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = undo,
            withDismissAction = false,
            duration = SnackbarDuration.Long,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoDelete(id)
            SnackbarResult.Dismissed -> viewModel.onDeleteSnackbarDismissed(id)
        }
    }

    AlarmsScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onAddAlarm = onAddAlarm,
        onEditAlarm = onEditAlarm,
        onOpenLinked = { openLinkedId = it },
        onLinkedJustMe = viewModel::setLinkedJustMe,
        onLinkedBoth = viewModel::setLinkedBoth,
        onToggleEnabled = viewModel::setEnabled,
        onSkipNext = viewModel::skipNext,
        onSkipJustMe = viewModel::skipJustMe,
        onUnskip = viewModel::unskip,
        showSetupBanner = showBanner,
        onSetupBannerClick = {
            bannerViewModel.dismiss()
            onOpenHealth()
        },
        onSetupBannerDismiss = bannerViewModel::dismiss,
        modifier = modifier,
    )

    openLinkedId?.let { id -> LinkedAlarmSheetRoute(alarmId = id, onDismiss = { openLinkedId = null }) }
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
    onOpenLinked: (String) -> Unit = {},
    onLinkedJustMe: (String) -> Unit = {},
    onLinkedBoth: (String) -> Unit = {},
    onSkipJustMe: (String) -> Unit = {},
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
                            // A joined row's time belongs to the owner, so it never opens the editor.
                            onClick = { if (row.linked != null) onOpenLinked(row.id) else onEditAlarm(row.id) },
                            onToggleEnabled = { onToggleEnabled(row.id, it) },
                            // A joined row's on/off is a "just me or both of us?" question instead.
                            onLinkedJustMe = { onLinkedJustMe(row.id) },
                            onLinkedBoth = { onLinkedBoth(row.id) },
                            onSkipNext = { onSkipNext(row.id) },
                            onSkipJustMe = { onSkipJustMe(row.id) },
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
    onLinkedJustMe: () -> Unit,
    onLinkedBoth: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipJustMe: () -> Unit,
    onUnskip: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // On an own row the partner joined, Skip asks "Just me or both of us?" inside the same menu.
    var skipQuestionOpen by remember { mutableStateOf(false) }
    var switchQuestionOpen by remember { mutableStateOf(false) }
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
            row.linked?.let { LinkedChips(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SuggestionChip(onClick = onClick, label = { Text(row.daysText) })
                row.partnerJoined?.let { name ->
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.link_joined, name)) },
                    )
                }
                if (row.vibrateOnly) {
                    Icon(
                        painterResource(R.drawable.ic_vibrate),
                        contentDescription = stringResource(R.string.alarm_vibrate_only),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                row.ringsInText ?: when {
                    // On but nothing left to ring (an own one-shot skipped "just me"): not "Off" beside an on switch.
                    row.enabled && row.skipped ->
                        stringResource(if (row.skippedSolo) R.string.link_state_skipped_for_you else R.string.alarm_skipped)
                    else -> stringResource(R.string.alarm_off)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.linked?.let { LinkedState(it) }
            if (row.skipped && row.linked == null) {
                val next = row.nextRing
                AssistChip(
                    onClick = onUnskip,
                    label = {
                        Text(
                            if (row.skippedSolo) {
                                stringResource(R.string.link_state_skipped_for_you)
                            } else if (next != null) {
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
        Box {
            Switch(
                checked = row.enabled,
                onCheckedChange = { if (row.linked != null) switchQuestionOpen = true else onToggleEnabled(it) },
                modifier = Modifier.semantics { contentDescription = switchDescription },
            )
            row.linked?.let { linked ->
                DropdownMenu(expanded = switchQuestionOpen, onDismissRequest = { switchQuestionOpen = false }) {
                    LinkedSwitchQuestion(
                        options = linked.switchOptions,
                        onJustMe = { switchQuestionOpen = false; onLinkedJustMe() },
                        onBoth = { switchQuestionOpen = false; onLinkedBoth() },
                    )
                }
            }
        }
        val hasMenu = row.linked == null && (row.skipped || row.enabled)
        // Without a menu, keep its slot so every row's switch sits in the same column.
        if (!hasMenu) Spacer(Modifier.width(48.dp))
        if (hasMenu) Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.alarm_more_options))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false; skipQuestionOpen = false },
            ) {
                if (skipQuestionOpen) {
                    LinkedSwitchQuestion(
                        options = LinkedSwitchOptions(justMe = true, both = true),
                        onJustMe = { menuOpen = false; skipQuestionOpen = false; onSkipJustMe() },
                        onBoth = { menuOpen = false; skipQuestionOpen = false; onSkipNext() },
                    )
                } else if (row.skipped) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.alarm_unskip)) },
                        onClick = { menuOpen = false; onUnskip() },
                    )
                } else if (row.enabled) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.alarm_skip_next)) },
                        onClick = {
                            if (row.partnerJoined != null) {
                                skipQuestionOpen = true
                            } else {
                                menuOpen = false
                                onSkipNext()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The partner chip, link icon and Ring/Notify tag of a joined row. */
@Composable
private fun LinkedChips(linked: LinkedRowState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.ic_link),
            contentDescription = stringResource(R.string.link_icon_description),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text(stringResource(R.string.link_partner_possessive, linked.partnerName)) },
        )
        SuggestionChip(
            onClick = {},
            enabled = false,
            label = {
                Text(
                    stringResource(
                        if (linked.mode == LinkMode.RING) R.string.link_mode_ring else R.string.link_mode_notify,
                    ),
                )
            },
        )
    }
}

/** The one skip state a joined row shows, plus the "Waiting for ..." line while a both-of-us command is out. */
@Composable
private fun LinkedState(linked: LinkedRowState) {
    val text = when {
        linked.ownerSkipped -> stringResource(R.string.link_state_owner_skipped, linked.partnerName)
        linked.skippedForYou -> stringResource(R.string.link_state_skipped_for_you)
        linked.ownerSkippingSolo -> stringResource(R.string.link_state_owner_skipping_solo, linked.partnerName)
        else -> null
    }
    text?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
    }
    if (linked.pendingBoth) {
        Text(
            stringResource(R.string.link_waiting_for_partner, linked.partnerName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val previewState = AlarmsUiState(
    loaded = true,
    zone = ZoneId.of("UTC"),
    rows = listOf(
        AlarmRowState("1", 6, 30, "Work", "Mon–Fri", true, Instant.parse("2026-11-02T06:30:00Z"), "Rings in 7 h 12 min", false, false),
        AlarmRowState("2", 7, 0, "Gym", "Mon, Wed, Fri", true, Instant.parse("2026-11-04T07:00:00Z"), "Rings in 2 d 3 h", true, false),
        AlarmRowState("3", 8, 0, "Weekend", "Weekends", false, null, null, false, false, vibrateOnly = true),
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

private fun joinedPreviewRow(id: String, mode: LinkMode, linked: LinkedRowState.() -> LinkedRowState) = AlarmRowState(
    id, 7, 15, "Gym", "Mon–Fri", true, Instant.parse("2026-11-03T07:15:00Z"), "Rings in 1 d 2 h", false, false,
    linked = LinkedRowState("Shawn", mode, false, false, false, false).linked(),
)

private fun joinedPreviewState(row: AlarmRowState) = AlarmsUiState(loaded = true, zone = ZoneId.of("UTC"), rows = listOf(row))

@Preview(showBackground = true)
@Composable
private fun JoinedRingOwnerSkippedPreview() {
    TandemTheme {
        AlarmsScreen(
            joinedPreviewState(joinedPreviewRow("j1", LinkMode.RING) { copy(ownerSkipped = true) }.copy(skipped = true)),
            remember { SnackbarHostState() }, {}, {}, { _, _ -> }, {}, {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun JoinedNotifySkippedForYouPreview() {
    TandemTheme {
        AlarmsScreen(
            joinedPreviewState(joinedPreviewRow("j2", LinkMode.NOTIFY) { copy(skippedForYou = true) }.copy(skipped = true)),
            remember { SnackbarHostState() }, {}, {}, { _, _ -> }, {}, {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun JoinedOwnerSkippingSoloPreview() {
    TandemTheme {
        AlarmsScreen(
            joinedPreviewState(joinedPreviewRow("j3", LinkMode.RING) { copy(ownerSkippingSolo = true) }),
            remember { SnackbarHostState() }, {}, {}, { _, _ -> }, {}, {},
        )
    }
}

private fun ownJoinedPreviewRow(id: String, change: AlarmRowState.() -> AlarmRowState = { this }) = AlarmRowState(
    id, 6, 30, "Work", "Mon–Fri", true, Instant.parse("2026-11-03T06:30:00Z"), "Rings in 1 d 1 h", false, false,
    partnerJoined = "Shawn",
).change()

/** An own alarm the partner joined. */
@Preview(showBackground = true)
@Composable
private fun OwnJoinedPreview() {
    TandemTheme {
        AlarmsScreen(joinedPreviewState(ownJoinedPreviewRow("p1")), remember { SnackbarHostState() }, {}, {}, { _, _ -> }, {}, {})
    }
}

/** An own alarm with only the owner's "just me" skip. */
@Preview(showBackground = true)
@Composable
private fun OwnJoinedSkippedForYouPreview() {
    TandemTheme {
        AlarmsScreen(
            joinedPreviewState(ownJoinedPreviewRow("p2") { copy(skipped = true, skippedSolo = true) }),
            remember { SnackbarHostState() }, {}, {}, { _, _ -> }, {}, {},
        )
    }
}
