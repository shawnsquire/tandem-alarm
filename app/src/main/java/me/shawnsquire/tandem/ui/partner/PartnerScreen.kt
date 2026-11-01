package me.shawnsquire.tandem.ui.partner

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.fakes.FakePartnerRepository
import me.shawnsquire.tandem.ui.amPm
import me.shawnsquire.tandem.ui.alarms.linked.LinkedAlarmSheetRoute
import me.shawnsquire.tandem.ui.formatClock
import me.shawnsquire.tandem.ui.partnerViewModelFactory
import me.shawnsquire.tandem.ui.theme.TandemTheme
import java.time.Instant

class PartnerCallbacks(
    val onShowCode: () -> Unit = {},
    val onJoinInput: (String) -> Unit = {},
    val onJoin: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onAction: (PartnerAction, String) -> Unit = { _, _ -> },
    val onJoinAlarm: (String) -> Unit = {},
    val onOpenLinked: (String) -> Unit = {},
)

@Composable
fun PartnerRoute(
    modifier: Modifier = Modifier,
    viewModel: PartnerViewModel = viewModel(factory = partnerViewModelFactory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The Linked alarm sheet is hosted here; the id survives rotation.
    var openLinkedId by rememberSaveable { mutableStateOf<String?>(null) }
    val callbacks = PartnerCallbacks(
        onShowCode = viewModel::showCode,
        onJoinInput = viewModel::onJoinInput,
        onJoin = viewModel::join,
        onStop = viewModel::stopRinging,
        onJoinAlarm = viewModel::joinAlarm,
        onOpenLinked = { openLinkedId = it },
        onAction = { action, id ->
            when (action) {
                PartnerAction.SKIP_NEXT -> viewModel.skipNext(id)
                PartnerAction.UNSKIP -> viewModel.unskip(id)
                PartnerAction.TURN_OFF -> viewModel.turnOff(id)
                PartnerAction.TURN_ON -> viewModel.turnOn(id)
            }
        },
    )
    PartnerScreen(state, callbacks, modifier)

    openLinkedId?.let { id -> LinkedAlarmSheetRoute(alarmId = id, onDismiss = { openLinkedId = null }) }
}

@Composable
fun PartnerScreen(state: PartnerUiState, callbacks: PartnerCallbacks, modifier: Modifier = Modifier) {
    when {
        // Neutral until the first real emission, so a paired user never flashes the unpaired UI.
        state.loading -> Box(modifier.fillMaxSize())
        state.paired -> PairedContent(state, callbacks, modifier)
        else -> UnpairedContent(state, callbacks, modifier)
    }
}

@Composable
private fun UnpairedContent(state: PartnerUiState, callbacks: PartnerCallbacks, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.partner_unpaired_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.code == null) {
                    FilledTonalButton(
                        onClick = callbacks.onShowCode,
                        enabled = !state.codeLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.codeLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.partner_show_code))
                        }
                    }
                } else {
                    Text(
                        state.code,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 6.sp,
                        ),
                    )
                    QrCode(state.code, stringResource(R.string.partner_code_qr))
                    Text(
                        stringResource(R.string.partner_code_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.joinInput,
                    onValueChange = callbacks.onJoinInput,
                    label = { Text(stringResource(R.string.partner_enter_code)) },
                    singleLine = true,
                    enabled = !state.joining,
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { callbacks.onJoin() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = callbacks.onJoin,
                    enabled = state.joinInput.isNotEmpty() && !state.joining,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.joining) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.partner_join))
                    }
                }
            }
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PairedContent(state: PartnerUiState, callbacks: PartnerCallbacks, modifier: Modifier) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(state.partnerName, style = MaterialTheme.typography.headlineMedium)
                Text(
                    state.lastSeenText?.let { stringResource(R.string.partner_last_seen, it) }
                        ?: stringResource(R.string.partner_not_seen),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.asleepWarning) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Text(
                        stringResource(R.string.partner_asleep, state.partnerName),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        state.ringing?.let { banner ->
            item { RingingCard(banner, is24Hour, callbacks.onStop) }
        }
        if (!state.partnerLoaded) {
            item {
                Text(
                    stringResource(R.string.partner_waiting, state.partnerName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (state.alarms.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.partner_no_alarms, state.partnerName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.alarms, key = { it.id }) { row ->
            AlarmCard(row, state.partnerName, is24Hour, callbacks.onAction, callbacks.onJoinAlarm, callbacks.onOpenLinked)
        }
    }
}

@Composable
private fun RingingCard(banner: RingingBanner, is24Hour: Boolean, onStop: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val title = if (banner.snoozedHour != null && banner.snoozedMinute != null) {
                val time = formatClock(banner.snoozedHour, banner.snoozedMinute, is24Hour) +
                    (amPm(banner.snoozedHour, is24Hour)?.let { " $it" } ?: "")
                stringResource(R.string.partner_snoozed_until, time)
            } else {
                stringResource(R.string.partner_ringing, banner.partnerName)
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Button(
                onClick = onStop,
                enabled = banner.stopStatus != CommandUiStatus.Pending,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                contentPadding = ButtonDefaults.ContentPadding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.partner_stop), style = MaterialTheme.typography.titleLarge)
            }
            CommandStatusLine(banner.stopStatus, banner.partnerName, onContainer = true)
        }
    }
}

/** A read-only status pill: deliberately not a chip, so it does not look tappable. */
@Composable
private fun StateChip(chip: AlarmChip) {
    val (container, content) = when (chip) {
        AlarmChip.ON -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        AlarmChip.OFF -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        AlarmChip.SKIPPED_NEXT -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(
            stringResource(
                when (chip) {
                    AlarmChip.ON -> R.string.partner_chip_on
                    AlarmChip.OFF -> R.string.partner_chip_off
                    AlarmChip.SKIPPED_NEXT -> R.string.partner_chip_skipped
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AlarmCard(
    row: PartnerAlarmRow,
    partnerName: String,
    is24Hour: Boolean,
    onAction: (PartnerAction, String) -> Unit,
    onJoinAlarm: (String) -> Unit,
    onOpenLinked: (String) -> Unit,
) {
    val dim = if (row.chip == AlarmChip.OFF) 0.6f else 1f
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            formatClock(row.hour, row.minute, is24Hour),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                        )
                        amPm(row.hour, is24Hour)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                            )
                        }
                    }
                    Text(
                        row.label.ifBlank { stringResource(R.string.alarm_no_label) } + " · " + row.daysText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StateChip(row.chip)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.actions.forEach { action ->
                    val label = stringResource(
                        when (action) {
                            PartnerAction.SKIP_NEXT -> R.string.partner_action_skip
                            PartnerAction.UNSKIP -> R.string.partner_action_unskip
                            PartnerAction.TURN_OFF -> R.string.partner_action_turn_off
                            PartnerAction.TURN_ON -> R.string.partner_action_turn_on
                        },
                    )
                    if (action == PartnerAction.TURN_OFF) {
                        TextButton(onClick = { onAction(action, row.id) }, enabled = row.pendingAction != action) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onAction(action, row.id) }, enabled = row.pendingAction != action) { Text(label) }
                    }
                }
                if (row.linkedAlarmId != null) {
                    FilledTonalButton(onClick = { onOpenLinked(row.linkedAlarmId) }) {
                        Text(stringResource(R.string.link_joined_state))
                    }
                } else {
                    OutlinedButton(onClick = { onJoinAlarm(row.id) }, enabled = !row.joining) {
                        Text(stringResource(R.string.link_join))
                    }
                }
            }
            if (row.soloSkipping) {
                Text(
                    stringResource(R.string.link_state_owner_skipping_solo, partnerName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CommandStatusLine(row.commandStatus, partnerName, onContainer = false)
            row.joinError?.let { CommandStatusLine(CommandUiStatus.Rejected(it), partnerName, onContainer = false) }
        }
    }
}

@Composable
internal fun CommandStatusLine(status: CommandUiStatus?, partnerName: String, onContainer: Boolean) {
    if (status == null) return
    val neutral = if (onContainer) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (status) {
            CommandUiStatus.Pending -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.partner_status_pending), style = MaterialTheme.typography.bodySmall, color = neutral)
            }
            CommandUiStatus.Applied -> {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = neutral)
                Text(stringResource(R.string.partner_status_applied), style = MaterialTheme.typography.bodySmall, color = neutral)
            }
            is CommandUiStatus.Rejected -> Text(
                status.reason,
                style = MaterialTheme.typography.bodySmall,
                // The red-on-red banner would be unreadable, so lean on the container's own on-colour there.
                color = if (onContainer) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.error,
            )
            CommandUiStatus.Unknown -> Text(
                stringResource(R.string.partner_status_unknown, partnerName),
                style = MaterialTheme.typography.bodySmall,
                color = neutral,
            )
        }
    }
}

private val sampleAlarms = listOf(
    PartnerAlarmRow("1", "Work", 6, 30, "Mon–Fri", AlarmChip.ON, listOf(PartnerAction.SKIP_NEXT, PartnerAction.TURN_OFF), CommandUiStatus.Pending),
    PartnerAlarmRow("2", "Gym", 7, 0, "Mon, Wed, Fri", AlarmChip.SKIPPED_NEXT, listOf(PartnerAction.UNSKIP, PartnerAction.TURN_OFF), CommandUiStatus.Applied),
    PartnerAlarmRow("3", "Weekend", 8, 30, "Weekends", AlarmChip.OFF, listOf(PartnerAction.TURN_ON), CommandUiStatus.Rejected("Alex changed this alarm")),
    PartnerAlarmRow("4", "Nap", 14, 0, "Once", AlarmChip.ON, listOf(PartnerAction.SKIP_NEXT, PartnerAction.TURN_OFF), CommandUiStatus.Unknown),
)

private val pairedPreview = PartnerUiState(
    loading = false, paired = true, partnerName = "Alex", partnerLoaded = true, lastSeenText = "3 min ago",
    alarms = sampleAlarms,
)

@Preview(showBackground = true)
@Composable
private fun UnpairedPreview() {
    TandemTheme { PartnerScreen(PartnerUiState(loading = false, joinInput = "AB1"), PartnerCallbacks()) }
}

@Preview(showBackground = true)
@Composable
private fun UnpairedCodePreview() {
    TandemTheme { PartnerScreen(PartnerUiState(loading = false, code = "K7M2QX", error = "That code is invalid or has expired."), PartnerCallbacks()) }
}

@Preview(showBackground = true)
@Composable
private fun PairedPreview() {
    TandemTheme { PartnerScreen(pairedPreview, PartnerCallbacks()) }
}

@Preview(showBackground = true)
@Composable
private fun JoinPreview() {
    TandemTheme { PartnerScreen(pairedPreview.copy(alarms = listOf(sampleAlarms[0])), PartnerCallbacks()) }
}

@Preview(showBackground = true)
@Composable
private fun JoinedPreview() {
    TandemTheme {
        PartnerScreen(pairedPreview.copy(alarms = listOf(sampleAlarms[0].copy(linkedAlarmId = "linked-1"))), PartnerCallbacks())
    }
}

@Preview(showBackground = true)
@Composable
private fun SoloSkippingPreview() {
    TandemTheme {
        PartnerScreen(pairedPreview.copy(alarms = listOf(sampleAlarms[0].copy(soloSkipping = true))), PartnerCallbacks())
    }
}

@Preview(showBackground = true)
@Composable
private fun JoinFailedPreview() {
    TandemTheme {
        PartnerScreen(
            pairedPreview.copy(alarms = listOf(sampleAlarms[0].copy(commandStatus = null, joinError = "Alex deleted this alarm"))),
            PartnerCallbacks(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RingingAsleepPreview() {
    TandemTheme {
        PartnerScreen(
            pairedPreview.copy(
                lastSeenText = "2 h ago",
                asleepWarning = true,
                ringing = RingingBanner("Alex", "1", "r1", null, null, CommandUiStatus.Pending),
            ),
            PartnerCallbacks(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SnoozedPreview() {
    TandemTheme {
        PartnerScreen(
            pairedPreview.copy(ringing = RingingBanner("Alex", "1", "r1", 6, 40, null)),
            PartnerCallbacks(),
        )
    }
}

/** Sample data from the fakes, mapped with the same row mapping the ViewModel uses. */
@Preview(showBackground = true)
@Composable
private fun PairedFromFakesPreview() {
    val partner = FakePartnerRepository.sampleState()
    val now = Instant.now()
    TandemTheme {
        PartnerScreen(
            PartnerUiState(
                loading = false, paired = true, partnerName = partner.displayName, partnerLoaded = true,
                lastSeenText = "5 min ago", alarms = partner.alarms.map { partnerAlarmRow(it, now, null) },
            ),
            PartnerCallbacks(),
        )
    }
}
