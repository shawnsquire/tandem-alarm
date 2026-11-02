package me.shawnsquire.tandem.ui.alarms.linked

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.media.PickAudioFile
import me.shawnsquire.tandem.media.PickSystemRingtone
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.ui.alarms.OutputHints
import me.shawnsquire.tandem.ui.alarms.VibrationPatternDialog
import me.shawnsquire.tandem.ui.alarms.vibrationPatternName
import me.shawnsquire.tandem.ui.alarms.edit.WallpaperThumbnail
import me.shawnsquire.tandem.ui.amPm
import me.shawnsquire.tandem.ui.formatClock
import me.shawnsquire.tandem.ui.linkedAlarmViewModelFactory
import me.shawnsquire.tandem.ui.partner.CommandStatusLine
import me.shawnsquire.tandem.ui.partner.CommandUiStatus
import me.shawnsquire.tandem.ui.theme.TandemTheme

/** Everything the sheet can ask for; the previews use the no-op defaults. */
class LinkedSheetCallbacks(
    val onSetMode: (LinkMode) -> Unit = {},
    val onSetMessage: (String) -> Unit = {},
    val onPickSystemRingtone: () -> Unit = {},
    val onPickRingtoneFile: () -> Unit = {},
    val onUseOwnersSound: () -> Unit = {},
    val onSoundOnChange: (Boolean) -> Unit = {},
    val onVibrationOnChange: (Boolean) -> Unit = {},
    val onSelectVibration: (VibrationPattern) -> Unit = {},
    val onStopPreview: () -> Unit = {},
    val onUseOwnersVibration: () -> Unit = {},
    val onPickWallpaper: () -> Unit = {},
    val onUseOwnersWallpaper: () -> Unit = {},
    val onAskSkip: () -> Unit = {},
    val onCancelSkip: () -> Unit = {},
    val onSkipJustMe: () -> Unit = {},
    val onSkipBoth: () -> Unit = {},
    val onUnskip: () -> Unit = {},
    val onAskLeave: () -> Unit = {},
    val onCancelLeave: () -> Unit = {},
    val onLeave: () -> Unit = {},
)

/**
 * The Linked alarm sheet for the joined row [alarmId]: mode, overrides, skip and leave. Self-contained, so
 * any screen can host it and drop it again with [onDismiss]; it also asks to close once the row is gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedAlarmSheetRoute(
    alarmId: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LinkedAlarmViewModel = viewModel(key = "linked:$alarmId", factory = linkedAlarmViewModelFactory(alarmId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Only a real dismiss resets the sheet; leaving composition also happens on rotation.
    val dismiss = {
        viewModel.onSheetClosed()
        onDismiss()
    }
    val latestDismiss by rememberUpdatedState(dismiss)
    // Survives rotation, so only a freshly opened sheet drops a settled result from earlier.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!opened) {
            opened = true
            viewModel.onSheetOpened()
        }
    }
    // `closed` can be stale in the retained state after a rejoin, so confirm the row is really gone.
    LaunchedEffect(state.closed) { if (state.closed && viewModel.isRowGone()) latestDismiss() }

    val systemPicker = rememberLauncherForActivityResult(PickSystemRingtone()) { uri ->
        if (uri != null) viewModel.onSystemRingtonePicked(uri)
    }
    val filePicker = rememberLauncherForActivityResult(remember(context) { PickAudioFile(context.contentResolver) }) { uri ->
        if (uri != null) viewModel.onRingtoneFilePicked(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onWallpaperPicked(uri.toString())
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val errorText = when (state.error) {
        LinkedError.RingtoneImport -> stringResource(R.string.edit_error_ringtone)
        LinkedError.WallpaperImport -> stringResource(R.string.edit_error_wallpaper)
        LinkedError.Save -> stringResource(R.string.link_error_save)
        null -> null
    }
    LaunchedEffect(state.error) {
        if (errorText != null) {
            snackbarHostState.showSnackbar(errorText)
            viewModel.dismissError()
        }
    }

    ModalBottomSheet(onDismissRequest = dismiss, modifier = modifier) {
        // The message field commits on dispose, so nothing is composed before the row's real values are known.
        if (!state.loaded) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@ModalBottomSheet
        }
        LinkedAlarmSheetContent(
            state = state,
            callbacks = LinkedSheetCallbacks(
                onSetMode = viewModel::setMode,
                onSetMessage = viewModel::setMessage,
                onPickSystemRingtone = { systemPicker.launch(state.overrideRingtone) },
                onPickRingtoneFile = { filePicker.launch(Unit) },
                onUseOwnersSound = viewModel::useOwnersSound,
                onSoundOnChange = viewModel::setSoundOn,
                onVibrationOnChange = viewModel::setVibrationOn,
                onSelectVibration = viewModel::selectVibration,
                onStopPreview = viewModel::stopPreview,
                onUseOwnersVibration = viewModel::useOwnersVibration,
                onPickWallpaper = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onUseOwnersWallpaper = viewModel::useOwnersWallpaper,
                onAskSkip = viewModel::askSkip,
                onCancelSkip = viewModel::cancelSkip,
                onSkipJustMe = viewModel::skipJustMe,
                onSkipBoth = viewModel::skipBoth,
                onUnskip = viewModel::unskip,
                onAskLeave = viewModel::askLeave,
                onCancelLeave = viewModel::cancelLeave,
                onLeave = { viewModel.leave(dismiss) },
            ),
        )
        SnackbarHost(snackbarHostState)
    }
}

@Composable
fun LinkedAlarmSheetContent(
    state: LinkedSheetState,
    callbacks: LinkedSheetCallbacks,
    modifier: Modifier = Modifier,
) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    // Busy covers imports; the pickers and resets wait for one in flight.
    val locked = state.busy
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
    ) {
        Header(state, is24Hour)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.link_mode_title), style = MaterialTheme.typography.titleSmall)
            ModeToggle(state.mode, callbacks.onSetMode)
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        MessageField(state, callbacks.onSetMessage)
        SoundRow(state, locked, callbacks)
        VibrationRow(state, locked, callbacks)
        OutputHints(state.soundOn, state.vibrationOn)
        PhotoRow(state, locked, callbacks)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SkipSection(state, callbacks)
        LeaveSection(state, is24Hour, callbacks)
    }
}

@Composable
private fun Header(state: LinkedSheetState, is24Hour: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatClock(state.hour, state.minute, is24Hour), style = MaterialTheme.typography.displaySmall)
            amPm(state.hour, is24Hour)?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            }
        }
        Text(
            state.label.ifBlank { stringResource(R.string.alarm_no_label) } + " · " + state.daysText,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.link_partner_possessive, state.partnerName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeToggle(mode: LinkMode, onSetMode: (LinkMode) -> Unit) {
    val options = listOf(LinkMode.RING to R.string.link_mode_ring, LinkMode.NOTIFY to R.string.link_mode_notify)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onSetMode(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun MessageField(state: LinkedSheetState, onSetMessage: (String) -> Unit) {
    var text by remember(state.overrideLabel) { mutableStateOf(state.overrideLabel.orEmpty()) }
    var hadFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Dismissing the sheet while typing must not lose the edit, but only an edit is committed: an untouched
    // field would write the blank it started with over the stored override.
    var dirty by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(text)
    DisposableEffect(Unit) { onDispose { if (dirty) onSetMessage(latest) } }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; dirty = true },
        label = { Text(stringResource(R.string.link_override_message)) },
        placeholder = { Text(stringResource(R.string.link_use_partners, state.partnerName)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        // Done drops focus, and losing focus is what commits.
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .onFocusChanged {
                if (hadFocus && !it.isFocused) onSetMessage(text)
                hadFocus = it.isFocused
            },
    )
}

@Composable
private fun SoundRow(state: LinkedSheetState, locked: Boolean, cb: LinkedSheetCallbacks) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(R.string.link_override_sound)) },
            supportingContent = {
                Text(
                    if (state.overrideRingtone == null) stringResource(R.string.link_use_partners, state.partnerName)
                    else state.ringtoneTitle,
                )
            },
            trailingContent = {
                // The switch shows what rings here (the override, else the owner's); the last one on is locked.
                Switch(
                    checked = state.soundOn,
                    onCheckedChange = cb.onSoundOnChange,
                    enabled = !locked && (!state.soundOn || state.vibrationOn),
                )
            },
            modifier = Modifier.clickable(enabled = !locked) { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_ringtone_system)) },
                onClick = { menu = false; cb.onPickSystemRingtone() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_ringtone_file)) },
                onClick = { menu = false; cb.onPickRingtoneFile() },
            )
            // Clears the tone and the switch together, so it shows while either is set.
            if (state.overrideRingtone != null || state.overrideSoundOn != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.link_use_partners, state.partnerName)) },
                    onClick = { menu = false; cb.onUseOwnersSound() },
                    enabled = !locked,
                )
            }
        }
    }
}

@Composable
private fun VibrationRow(state: LinkedSheetState, locked: Boolean, cb: LinkedSheetCallbacks) {
    var list by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.link_override_vibration)) },
        supportingContent = { Text(vibrationPatternName(state.vibration)) },
        trailingContent = {
            Switch(
                checked = state.vibrationOn,
                onCheckedChange = cb.onVibrationOnChange,
                enabled = !locked && (!state.vibrationOn || state.soundOn),
            )
        },
        modifier = Modifier.clickable(enabled = !locked) { list = true },
    )
    if (list) {
        val overridden = state.overrideVibration != null || state.overrideVibrationOn != null
        VibrationPatternDialog(
            selected = state.vibration,
            onSelect = cb.onSelectVibration,
            onStopPreview = cb.onStopPreview,
            onDismiss = { list = false },
            usePartnersLabel = if (overridden) stringResource(R.string.link_use_partners, state.partnerName) else null,
            onUsePartners = cb.onUseOwnersVibration,
        )
    }
}

@Composable
private fun PhotoRow(state: LinkedSheetState, locked: Boolean, cb: LinkedSheetCallbacks) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.link_override_photo)) },
        supportingContent = {
            Column {
                Text(
                    if (state.overrideWallpaper == null) stringResource(R.string.link_use_partners, state.partnerName)
                    else stringResource(R.string.link_photo_custom),
                )
                Row {
                    TextButton(onClick = cb.onPickWallpaper, enabled = !locked) {
                        Text(stringResource(R.string.edit_wallpaper_choose))
                    }
                    if (state.overrideWallpaper != null) {
                        TextButton(onClick = cb.onUseOwnersWallpaper, enabled = !locked) {
                            Text(stringResource(R.string.link_use_partners, state.partnerName))
                        }
                    }
                }
            }
        },
        trailingContent = state.overrideWallpaper?.let { path -> { WallpaperThumbnail(path) } },
    )
}

@Composable
private fun SkipSection(state: LinkedSheetState, cb: LinkedSheetCallbacks) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            // Skip is a "next ring" action, so it is not offered while the row is off.
            !state.armed -> Unit
            state.skippedForYou -> OutlinedButton(onClick = cb.onUnskip) {
                Text(stringResource(R.string.link_unskip_just_me))
            }
            state.askingSkip -> {
                Text(stringResource(R.string.link_just_me_or_both), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = cb.onSkipJustMe) { Text(stringResource(R.string.link_just_me)) }
                    OutlinedButton(onClick = cb.onSkipBoth, enabled = state.bothAvailable) {
                        Text(stringResource(R.string.link_both_of_us))
                    }
                    TextButton(onClick = cb.onCancelSkip) { Text(stringResource(R.string.link_cancel)) }
                }
            }
            else -> OutlinedButton(onClick = cb.onAskSkip) { Text(stringResource(R.string.alarm_skip_next)) }
        }
        CommandStatusLine(state.bothStatus, state.partnerName, onContainer = false)
    }
}

@Composable
private fun LeaveSection(state: LinkedSheetState, is24Hour: Boolean, cb: LinkedSheetCallbacks) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.confirmingLeave) {
            val time = formatClock(state.hour, state.minute, is24Hour) + (amPm(state.hour, is24Hour)?.let { " $it" }.orEmpty())
            Text(stringResource(R.string.link_leave_confirm, state.partnerName, time), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = cb.onCancelLeave) { Text(stringResource(R.string.link_cancel)) }
                Button(
                    onClick = cb.onLeave,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.link_leave)) }
            }
        } else {
            OutlinedButton(
                onClick = cb.onAskLeave,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.link_leave)) }
        }
    }
}

private val previewRing = LinkedSheetState(
    loaded = true, hour = 6, minute = 30, daysText = "Mon–Fri", label = "Gym", partnerName = "Shawn",
    armed = true, bothAvailable = true,
)

@Preview(showBackground = true)
@Composable
private fun SheetRingDefaultPreview() {
    TandemTheme { LinkedAlarmSheetContent(previewRing, LinkedSheetCallbacks()) }
}

@Preview(showBackground = true)
@Composable
private fun SheetNotifyOverridesPreview() {
    TandemTheme {
        LinkedAlarmSheetContent(
            previewRing.copy(
                mode = LinkMode.NOTIFY, label = "Up and at 'em", overrideLabel = "Up and at 'em",
                overrideRingtone = "content://media/internal/audio/media/9", ringtoneTitle = "Morning birds",
                overrideWallpaper = "/nonexistent.jpg", skippedForYou = true,
                soundOn = false, overrideSoundOn = false,
                vibration = VibrationPattern.HEARTBEAT, overrideVibration = VibrationPattern.HEARTBEAT,
            ),
            LinkedSheetCallbacks(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SheetLeaveConfirmPreview() {
    TandemTheme { LinkedAlarmSheetContent(previewRing.copy(confirmingLeave = true), LinkedSheetCallbacks()) }
}

@Preview(showBackground = true)
@Composable
private fun SheetSkipQuestionPreview() {
    TandemTheme { LinkedAlarmSheetContent(previewRing.copy(askingSkip = true), LinkedSheetCallbacks()) }
}

@Preview(showBackground = true)
@Composable
private fun SheetBothOfUsPendingPreview() {
    TandemTheme { LinkedAlarmSheetContent(previewRing.copy(bothStatus = CommandUiStatus.Pending), LinkedSheetCallbacks()) }
}
