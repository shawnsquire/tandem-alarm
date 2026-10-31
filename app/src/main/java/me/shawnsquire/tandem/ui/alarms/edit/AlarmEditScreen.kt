package me.shawnsquire.tandem.ui.alarms.edit

import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import me.shawnsquire.tandem.ui.alarmEditViewModelFactory
import me.shawnsquire.tandem.ui.formatDays
import me.shawnsquire.tandem.ui.theme.TandemTheme
import java.time.DayOfWeek

@Composable
fun AlarmEditRoute(
    onClose: () -> Unit,
    onDelete: (String) -> Unit,
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

    BackHandler(onBack = viewModel::cancel)

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
        onDelete = {
            viewModel.existingId?.let { id ->
                viewModel.discardSession()
                onDelete(id)
            }
        },
        onErrorShown = viewModel::dismissError,
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
) {
    val snackbarHostState = remember { SnackbarHostState() }
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
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.edit_close))
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = state.loaded && !state.busy) {
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
                    modifier = Modifier.clickable { ringtoneMenu = true },
                )
                DropdownMenu(expanded = ringtoneMenu, onDismissRequest = { ringtoneMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_ringtone_system)) },
                        onClick = { ringtoneMenu = false; onPickSystemRingtone() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_ringtone_file)) },
                        onClick = { ringtoneMenu = false; onPickRingtoneFile() },
                    )
                }
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.edit_wallpaper)) },
                supportingContent = {
                    Column {
                        Text(
                            stringResource(
                                if (state.wallpaperPath == null) R.string.edit_wallpaper_none else R.string.edit_wallpaper_set,
                            ),
                        )
                        Row {
                            TextButton(onClick = onPickWallpaper, enabled = !state.busy) {
                                Text(stringResource(R.string.edit_wallpaper_choose))
                            }
                            if (state.wallpaperPath != null) {
                                TextButton(onClick = onRemoveWallpaper) {
                                    Text(stringResource(R.string.edit_wallpaper_remove))
                                }
                            }
                        }
                    }
                },
                trailingContent = state.wallpaperPath?.let { path -> { WallpaperThumbnail(path) } },
            )

            if (!state.isNew) {
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(stringResource(R.string.edit_delete))
                }
            }
        }
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

@Composable
internal fun WallpaperThumbnail(path: String) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(path, 256) }
    }
    val shape = MaterialTheme.shapes.small
    Box(Modifier.size(width = 56.dp, height = 80.dp).clip(shape)) {
        bitmap?.let {
            Image(
                it,
                contentDescription = stringResource(R.string.edit_wallpaper_thumbnail),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

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
