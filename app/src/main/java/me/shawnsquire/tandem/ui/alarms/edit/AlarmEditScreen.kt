package me.shawnsquire.tandem.ui.alarms.edit

import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import me.shawnsquire.tandem.alarm.frameTransform
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.WallpaperFrame
import me.shawnsquire.tandem.ui.RingPreview
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.fakes.SAMPLE_ALARMS
import me.shawnsquire.tandem.media.PickAudioFile
import me.shawnsquire.tandem.media.PickSystemRingtone
import me.shawnsquire.tandem.schedule.VibrationPattern
import me.shawnsquire.tandem.ui.alarmEditViewModelFactory
import me.shawnsquire.tandem.ui.alarms.OutputHints
import me.shawnsquire.tandem.ui.alarms.VibrationPatternDialog
import me.shawnsquire.tandem.ui.alarms.vibrationPatternName
import me.shawnsquire.tandem.ui.formatDays
import me.shawnsquire.tandem.ui.partner.CommandStatusLine
import me.shawnsquire.tandem.ui.partner.CommandUiStatus
import me.shawnsquire.tandem.ui.theme.TandemTheme
import java.time.DayOfWeek

@Composable
fun AlarmEditRoute(
    onClose: () -> Unit,
    /** Receives the alarm id and a callback that drops unsaved session files; call it only once the delete goes ahead. */
    onDelete: (id: String, discardSession: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlarmEditViewModel = viewModel(factory = alarmEditViewModelFactory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.closed) { if (state.closed) onClose() }

    val systemPicker = rememberLauncherForActivityResult(PickSystemRingtone()) { uri ->
        if (uri != null) viewModel.onSystemRingtonePicked(uri)
    }
    val filePicker = rememberLauncherForActivityResult(remember(context) { PickAudioFile(context.contentResolver) }) { uri ->
        if (uri != null) viewModel.onRingtoneFilePicked(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onWallpaperPicked(uri.toString())
    }

    // Back is consumed as a no-op while busy so an in-flight save or import can finish (the VM also ignores cancel then).
    BackHandler { if (!state.busy) viewModel.cancel() }

    AlarmEditScreen(
        state = state,
        onCancel = viewModel::cancel,
        onSave = viewModel::save,
        onTimeChanged = viewModel::setTime,
        onToggleDay = viewModel::toggleDay,
        onLabelChanged = viewModel::setLabel,
        onPickSystemRingtone = { systemPicker.launch(state.ringtoneUri) },
        onPickRingtoneFile = { filePicker.launch(Unit) },
        onPickWallpaper = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRemoveWallpaper = viewModel::removeWallpaper,
        onFramingChanged = viewModel::setFraming,
        onPreviewRequestHandled = viewModel::previewRequestHandled,
        onDelete = {
            viewModel.existingId?.let { id ->
                onDelete(id, viewModel::discardSession)
            }
        },
        onErrorShown = viewModel::dismissError,
        onInvite = viewModel::invite,
        onSoundOnChange = viewModel::setSoundOn,
        onVibrationOnChange = viewModel::setVibrationOn,
        onSelectVibration = viewModel::selectVibration,
        onStopPreview = viewModel::stopPreview,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    state: AlarmEditUiState,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onTimeChanged: (Int, Int) -> Unit,
    onToggleDay: (DayOfWeek) -> Unit,
    onLabelChanged: (String) -> Unit,
    onPickSystemRingtone: () -> Unit,
    onPickRingtoneFile: () -> Unit,
    onPickWallpaper: () -> Unit,
    onRemoveWallpaper: () -> Unit,
    onDelete: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    onInvite: () -> Unit = {},
    onSoundOnChange: (Boolean) -> Unit = {},
    onVibrationOnChange: (Boolean) -> Unit = {},
    onSelectVibration: (VibrationPattern) -> Unit = {},
    onStopPreview: () -> Unit = {},
    onFramingChanged: (WallpaperFrame?, TextPosition) -> Unit = { _, _ -> },
    onPreviewRequestHandled: () -> Unit = {},
) {
    // Busy covers imports and saves; none of the controls may act while one is in flight.
    val locked = state.busy
    val snackbarHostState = remember { SnackbarHostState() }
    var showRingPreview by rememberSaveable { mutableStateOf(false) }
    // A fresh pick opens the preview so the new image can be framed right away.
    LaunchedEffect(state.previewRequested) {
        if (state.previewRequested) {
            showRingPreview = true
            onPreviewRequestHandled()
        }
    }
    val errorText = when (state.error) {
        EditError.RingtoneImport -> stringResource(R.string.edit_error_ringtone)
        EditError.WallpaperImport -> stringResource(R.string.edit_error_wallpaper)
        EditError.Save -> stringResource(R.string.edit_error_save)
        null -> null
    }
    LaunchedEffect(state.error) {
        if (errorText != null) {
            snackbarHostState.showSnackbar(errorText)
            onErrorShown()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isNew) R.string.edit_title_new else R.string.edit_title_existing))
                },
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !locked) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.edit_close))
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = state.loaded && !locked) {
                        Text(stringResource(R.string.edit_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Created only after load so it starts at the alarm's time.
            val timeState = rememberTimePickerState(
                initialHour = state.hour,
                initialMinute = state.minute,
                is24Hour = DateFormat.is24HourFormat(LocalContext.current),
            )
            LaunchedEffect(timeState) {
                snapshotFlow { timeState.hour to timeState.minute }.collect { (h, m) -> onTimeChanged(h, m) }
            }
            TimePicker(timeState, modifier = Modifier.padding(vertical = 16.dp))

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.edit_repeat), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in state.days,
                            onClick = { onToggleDay(day) },
                            label = { Text(dayLabel(day), maxLines = 1, softWrap = false) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    formatDays(state.days),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.label,
                    onValueChange = onLabelChanged,
                    label = { Text(stringResource(R.string.edit_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            var ringtoneMenu by remember { mutableStateOf(false) }
            Box {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.edit_ringtone)) },
                    supportingContent = { Text(state.ringtoneTitle) },
                    trailingContent = {
                        // The last output still on can't be switched off.
                        val switchLabel = stringResource(R.string.edit_ringtone)
                        Switch(
                            checked = state.soundOn,
                            modifier = Modifier.semantics { contentDescription = switchLabel },
                            onCheckedChange = onSoundOnChange,
                            enabled = !locked && (!state.soundOn || state.vibrationOn),
                        )
                    },
                    modifier = Modifier.clickable(enabled = !locked) { ringtoneMenu = true },
                )
                DropdownMenu(expanded = ringtoneMenu, onDismissRequest = { ringtoneMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_ringtone_system)) },
                        onClick = { ringtoneMenu = false; onPickSystemRingtone() },
                        enabled = !locked,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_ringtone_file)) },
                        onClick = { ringtoneMenu = false; onPickRingtoneFile() },
                        enabled = !locked,
                    )
                }
            }

            var vibrationList by rememberSaveable { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.edit_vibration)) },
                supportingContent = { Text(vibrationPatternName(state.vibration)) },
                trailingContent = {
                    val switchLabel = stringResource(R.string.edit_vibration)
                    Switch(
                        checked = state.vibrationOn,
                        modifier = Modifier.semantics { contentDescription = switchLabel },
                        onCheckedChange = onVibrationOnChange,
                        enabled = !locked && (!state.vibrationOn || state.soundOn),
                    )
                },
                modifier = Modifier.clickable(enabled = !locked) { vibrationList = true },
            )
            if (vibrationList) {
                VibrationPatternDialog(
                    selected = state.vibration,
                    onSelect = onSelectVibration,
                    onStopPreview = onStopPreview,
                    onDismiss = { vibrationList = false },
                )
            }
            OutputHints(state.soundOn, state.vibrationOn)

            ListItem(
                headlineContent = { Text(stringResource(R.string.edit_wallpaper)) },
                supportingContent = {
                    Column {
                        Text(
                            stringResource(
                                if (state.wallpaperPath == null) R.string.edit_wallpaper_none else R.string.edit_wallpaper_set,
                            ),
                        )
                        Text(stringResource(R.string.edit_text_position, textPositionName(state.textPosition)))
                        Row {
                            TextButton(onClick = onPickWallpaper, enabled = !locked) {
                                Text(stringResource(R.string.edit_wallpaper_choose))
                            }
                            TextButton(onClick = { showRingPreview = true }, enabled = !locked) {
                                Text(stringResource(R.string.edit_wallpaper_preview))
                            }
                            if (state.wallpaperPath != null) {
                                TextButton(onClick = onRemoveWallpaper, enabled = !locked) {
                                    Text(stringResource(R.string.edit_wallpaper_remove))
                                }
                            }
                        }
                    }
                },
                trailingContent = state.wallpaperPath?.let { path -> { WallpaperThumbnail(path, state.wallpaperFrame) } },
                modifier = Modifier.clickable(enabled = !locked) { showRingPreview = true },
            )
            if (showRingPreview) {
                // Without an image of its own the preview shows the Settings default, whose frame is Settings' to change.
                val own = state.wallpaperPath != null
                RingPreview(
                    wallpaperPath = state.wallpaperPath ?: state.defaultWallpaperPath,
                    initialFrame = if (own) state.wallpaperFrame else state.defaultWallpaperFrame,
                    gesturesEnabled = own,
                    initialTextPosition = state.textPosition,
                    showPositionSelector = true,
                    hour = state.hour,
                    minute = state.minute,
                    label = state.label,
                    onSave = { frame, position ->
                        onFramingChanged(frame, position)
                        showRingPreview = false
                    },
                    onCancel = { showRingPreview = false },
                )
            }

            InviteSection(state, locked, onInvite)

            if (!state.isNew) {
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !locked,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(stringResource(R.string.edit_delete))
                }
            }
        }
    }
}

/** Invite, Invited or "joined" for a saved own alarm while paired; nothing otherwise. */
@Composable
private fun InviteSection(state: AlarmEditUiState, locked: Boolean, onInvite: () -> Unit) {
    if (state.invite == EditInvite.Hidden) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.invite) {
            EditInvite.Invite -> OutlinedButton(onClick = onInvite, enabled = !locked) {
                Text(stringResource(R.string.link_invite, state.partnerName))
            }
            EditInvite.Invited -> OutlinedButton(onClick = {}, enabled = false) {
                Text(stringResource(R.string.link_invited))
            }
            EditInvite.Joined -> Text(
                stringResource(R.string.link_joined, state.partnerName),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            EditInvite.Hidden -> Unit
        }
        CommandStatusLine(state.inviteStatus, state.partnerName, onContainer = false)
    }
}

@Composable
private fun dayLabel(day: DayOfWeek): String = stringResource(
    when (day) {
        DayOfWeek.MONDAY -> R.string.edit_day_mon
        DayOfWeek.TUESDAY -> R.string.edit_day_tue
        DayOfWeek.WEDNESDAY -> R.string.edit_day_wed
        DayOfWeek.THURSDAY -> R.string.edit_day_thu
        DayOfWeek.FRIDAY -> R.string.edit_day_fri
        DayOfWeek.SATURDAY -> R.string.edit_day_sat
        DayOfWeek.SUNDAY -> R.string.edit_day_sun
    },
)

/**
 * The wallpaper as the ringing screen draws it: a phone-shaped box at this screen's aspect ratio,
 * with [frame] applied (null is the centred crop).
 */
@Composable
internal fun WallpaperThumbnail(path: String, frame: WallpaperFrame?) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(path, 256) }
    }
    val metrics = LocalContext.current.resources.displayMetrics
    val aspect = metrics.widthPixels.toFloat() / metrics.heightPixels
    val description = stringResource(R.string.edit_wallpaper_thumbnail)
    Box(
        Modifier
            .height(96.dp)
            .aspectRatio(aspect)
            .clip(MaterialTheme.shapes.small)
            .semantics { contentDescription = description }
            .drawBehind {
                val image = bitmap ?: return@drawBehind
                val t = frameTransform(frame, image.width.toFloat(), image.height.toFloat(), size.width, size.height)
                drawImage(
                    image,
                    dstOffset = IntOffset(t.left.roundToInt(), t.top.roundToInt()),
                    dstSize = IntSize((image.width * t.scale).roundToInt(), (image.height * t.scale).roundToInt()),
                )
            },
    )
}

@Composable
private fun textPositionName(position: TextPosition): String = stringResource(
    when (position) {
        TextPosition.TOP -> R.string.ring_preview_top
        TextPosition.MIDDLE -> R.string.ring_preview_middle
        TextPosition.BOTTOM -> R.string.ring_preview_bottom
    },
).lowercase()

private fun decodeThumbnail(path: String, maxSide: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
}

private val previewNew = AlarmEditUiState(loaded = true, isNew = true, ringtoneTitle = "Default alarm sound")

private val previewExisting = AlarmEditUiState(
    loaded = true, isNew = false, hour = 6, minute = 30,
    days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    label = "Work", ringtoneUri = "file:///x", ringtoneTitle = "Morning birds.mp3",
    wallpaperPath = "/nonexistent.jpg",
)

@Preview(showBackground = true)
@Composable
private fun AlarmEditNewPreview() {
    TandemTheme {
        AlarmEditScreen(previewNew, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AlarmEditExistingPreview() {
    TandemTheme {
        AlarmEditScreen(previewExisting, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/** Sound off: vibration is the last output on, so its switch is locked and the hints show. */
@Preview(showBackground = true)
@Composable
private fun AlarmEditVibrateOnlyPreview() {
    TandemTheme {
        AlarmEditScreen(
            previewExisting.copy(soundOn = false, vibration = VibrationPattern.HEARTBEAT),
            {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

/** Sample data from the fakes. */
@Preview(showBackground = true)
@Composable
private fun AlarmEditFromFakesPreview() {
    val alarm = SAMPLE_ALARMS.first()
    val state = AlarmEditUiState(
        loaded = true, isNew = false, hour = alarm.hour, minute = alarm.minute, days = alarm.days,
        label = alarm.label, ringtoneTitle = "Default alarm sound",
    )
    TandemTheme {
        AlarmEditScreen(state, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AlarmEditInvitePreview() {
    TandemTheme {
        AlarmEditScreen(
            previewExisting.copy(invite = EditInvite.Invite, partnerName = "Shawn"),
            {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AlarmEditInvitedPreview() {
    TandemTheme {
        AlarmEditScreen(
            previewExisting.copy(invite = EditInvite.Invited, partnerName = "Shawn"),
            {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AlarmEditJoinedPreview() {
    TandemTheme {
        AlarmEditScreen(
            previewExisting.copy(invite = EditInvite.Joined, partnerName = "Shawn"),
            {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

/** A declined invite: back to Invite, with the reason. */
@Preview(showBackground = true)
@Composable
private fun AlarmEditInviteRejectedPreview() {
    TandemTheme {
        AlarmEditScreen(
            previewExisting.copy(
                invite = EditInvite.Invite, partnerName = "Shawn",
                inviteStatus = CommandUiStatus.Rejected("Shawn declined"),
            ),
            {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}
