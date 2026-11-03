package me.shawnsquire.tandem.ui.settings

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.backup.BackupRejected
import me.shawnsquire.tandem.ui.amPm
import me.shawnsquire.tandem.ui.formatClock
import me.shawnsquire.tandem.media.PickAudioFile
import me.shawnsquire.tandem.media.PickSystemRingtone
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.WallpaperFrame
import me.shawnsquire.tandem.ui.RingPreview
import me.shawnsquire.tandem.ui.alarms.edit.WallpaperThumbnail
import me.shawnsquire.tandem.ui.settingsViewModelFactory
import me.shawnsquire.tandem.ui.theme.TandemTheme
import java.time.LocalDate

@Composable
fun SettingsRoute(
    onOpenHealth: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = settingsViewModelFactory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val systemPicker = rememberLauncherForActivityResult(PickSystemRingtone()) { uri ->
        if (uri != null) viewModel.onSystemRingtonePicked(uri)
    }
    val filePicker = rememberLauncherForActivityResult(remember(context) { PickAudioFile(context.contentResolver) }) { uri ->
        if (uri != null) viewModel.onRingtoneFilePicked(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onWallpaperPicked(uri.toString())
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.onExportDocumentCreated(uri.toString())
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onImportDocumentPicked(uri.toString())
    }

    SettingsScreen(
        state = state,
        onSnoozeSelected = viewModel::setSnooze,
        onPickSystemRingtone = { systemPicker.launch(state.ringtoneUri) },
        onPickRingtoneFile = { filePicker.launch(Unit) },
        onResetRingtone = viewModel::resetRingtone,
        onPickWallpaper = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRemoveWallpaper = viewModel::removeWallpaper,
        onFramingChanged = viewModel::setWallpaperFrame,
        onPreviewRequestHandled = viewModel::previewRequestHandled,
        onSaveName = viewModel::saveDisplayName,
        onUnpair = viewModel::unpair,
        onOpenHealth = onOpenHealth,
        onErrorShown = viewModel::dismissError,
        onExport = { exportLauncher.launch("tandem-backup-${LocalDate.now()}.zip") },
        onNoticeShown = viewModel::noticeShown,
        onRepickShown = viewModel::repickShown,
        // Some providers report a zip as octet-stream.
        onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
        onConfirmImport = viewModel::confirmImport,
        onCancelImport = viewModel::cancelImport,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSnoozeSelected: (Int) -> Unit,
    onPickSystemRingtone: () -> Unit,
    onPickRingtoneFile: () -> Unit,
    onResetRingtone: () -> Unit,
    onPickWallpaper: () -> Unit,
    onRemoveWallpaper: () -> Unit,
    onSaveName: (String) -> Unit,
    onUnpair: () -> Unit,
    onOpenHealth: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    onFramingChanged: (WallpaperFrame?) -> Unit = {},
    onPreviewRequestHandled: () -> Unit = {},
    onExport: () -> Unit = {},
    onNoticeShown: () -> Unit = {},
    onRepickShown: () -> Unit = {},
    onImport: () -> Unit = {},
    onConfirmImport: () -> Unit = {},
    onCancelImport: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showRingPreview by rememberSaveable { mutableStateOf(false) }
    // A fresh pick opens the preview so the new image can be framed right away.
    LaunchedEffect(state.previewRequested) {
        if (state.previewRequested) {
            showRingPreview = true
            onPreviewRequestHandled()
        }
    }
    val errorText = when (val e = state.error) {
        SettingsError.RingtoneImport -> stringResource(R.string.settings_error_ringtone)
        SettingsError.WallpaperImport -> stringResource(R.string.settings_error_wallpaper)
        SettingsError.Save -> stringResource(R.string.settings_error_save)
        SettingsError.Export -> stringResource(R.string.settings_error_export)
        is SettingsError.Import -> stringResource(
            when (e.reason) {
                BackupRejected.Reason.NOT_A_BACKUP -> R.string.settings_error_import_not_a_backup
                BackupRejected.Reason.NEWER_VERSION -> R.string.settings_error_import_newer_version
                BackupRejected.Reason.DAMAGED -> R.string.settings_error_import_damaged
                BackupRejected.Reason.INCOMPLETE -> R.string.settings_error_import_incomplete
                BackupRejected.Reason.TOO_LARGE -> R.string.settings_error_import_too_large
                BackupRejected.Reason.NO_SPACE -> R.string.settings_error_import_no_space
                null -> R.string.settings_error_import_read
            },
        )
        SettingsError.ImportApply -> stringResource(R.string.settings_error_import_apply)
        is SettingsError.Unpair -> e.message
        null -> null
    }
    LaunchedEffect(state.error) {
        if (errorText != null) {
            snackbarHostState.showSnackbar(errorText)
            onErrorShown()
        }
    }
    val noticeText = when (val n = state.notice) {
        is SettingsNotice.Exported -> {
            val done = pluralStringResource(R.plurals.settings_export_done, n.alarms, n.alarms)
            if (n.missingMedia > 0) {
                val missing = pluralStringResource(R.plurals.settings_export_missing, n.missingMedia, n.missingMedia)
                stringResource(R.string.settings_export_done_missing, done, missing)
            } else {
                done
            }
        }
        is SettingsNotice.Imported -> {
            val done = pluralStringResource(R.plurals.settings_import_done, n.alarms, n.alarms)
            if (n.missingMedia > 0) {
                val missing = pluralStringResource(R.plurals.settings_import_missing, n.missingMedia, n.missingMedia)
                stringResource(R.string.settings_export_done_missing, done, missing)
            } else {
                done
            }
        }
        null -> null
    }
    // Hold the notice while the modal repick dialog is up, or it times out unseen behind it.
    LaunchedEffect(state.notice, state.soundsToRepick) {
        if (state.soundsToRepick != null) return@LaunchedEffect
        if (noticeText != null) {
            snackbarHostState.showSnackbar(noticeText)
            onNoticeShown()
        }
    }

    var confirmUnpair by remember { mutableStateOf(false) }
    val partner = state.partnerName ?: stringResource(R.string.settings_partner_fallback)

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            SectionHeader(R.string.settings_section_alarms)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_snooze), style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SNOOZE_CHOICES.forEach { minutes ->
                        FilterChip(
                            selected = state.snoozeMinutes == minutes,
                            onClick = { onSnoozeSelected(minutes) },
                            label = { Text(stringResource(R.string.settings_snooze_minutes, minutes), maxLines = 1, softWrap = false) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            var ringtoneMenu by remember { mutableStateOf(false) }
            Box {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_ringtone)) },
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
                        enabled = !state.busy,
                    )
                    if (state.ringtoneUri != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_ringtone_reset)) },
                            onClick = { ringtoneMenu = false; onResetRingtone() },
                        )
                    }
                }
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_wallpaper)) },
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
                                TextButton(onClick = { showRingPreview = true }, enabled = !state.busy) {
                                    Text(stringResource(R.string.edit_wallpaper_preview))
                                }
                                TextButton(onClick = onRemoveWallpaper) {
                                    Text(stringResource(R.string.edit_wallpaper_remove))
                                }
                            }
                        }
                    }
                },
                trailingContent = state.wallpaperPath?.let { path -> { WallpaperThumbnail(path, state.wallpaperFrame) } },
                modifier = if (state.wallpaperPath != null) Modifier.clickable(enabled = !state.busy) { showRingPreview = true } else Modifier,
            )
            if (showRingPreview && state.wallpaperPath != null) {
                // Text position is per alarm, so only the framing is set here.
                RingPreview(
                    wallpaperPath = state.wallpaperPath,
                    initialFrame = state.wallpaperFrame,
                    gesturesEnabled = true,
                    initialTextPosition = TextPosition.MIDDLE,
                    showPositionSelector = false,
                    hour = 7,
                    minute = 0,
                    label = stringResource(R.string.alarm_no_label),
                    onSave = { frame, _ ->
                        onFramingChanged(frame)
                        showRingPreview = false
                    },
                    onCancel = { showRingPreview = false },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(R.string.settings_section_you)
            NameField(state.displayName, onSaveName)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(R.string.settings_section_partner)
            if (state.paired) {
                ListItem(headlineContent = { Text(stringResource(R.string.settings_paired_with, partner)) })
                OutlinedButton(
                    onClick = { confirmUnpair = true },
                    enabled = !state.unpairing,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.settings_unpair))
                }
            } else {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_not_paired)) },
                    supportingContent = { Text(stringResource(R.string.settings_not_paired_hint)) },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(R.string.settings_section_backup)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_export)) },
                supportingContent = { Text(stringResource(R.string.settings_export_summary)) },
                modifier = Modifier.clickable(enabled = !state.busy, onClick = onExport),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_import)) },
                supportingContent = { Text(stringResource(R.string.settings_import_summary)) },
                modifier = Modifier.clickable(enabled = !state.busy, onClick = onImport),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(R.string.settings_section_diagnostics)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_health)) },
                supportingContent = { Text(stringResource(R.string.settings_health_summary)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenHealth),
            )
        }
    }

    state.importConfirm?.let { alarms ->
        AlertDialog(
            onDismissRequest = onCancelImport,
            title = { Text(stringResource(R.string.settings_import_title)) },
            text = {
                Text(
                    if (alarms == 0) {
                        stringResource(R.string.settings_import_confirm_settings_only)
                    } else {
                        pluralStringResource(R.plurals.settings_import_confirm, alarms, alarms)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmImport) { Text(stringResource(R.string.settings_import_action)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelImport) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }

    state.soundsToRepick?.let { repick ->
        val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
        val lines = repick.alarms.map { a ->
            a.label.ifBlank {
                val time = formatClock(a.hour, a.minute, is24Hour) + (amPm(a.hour, is24Hour)?.let { " $it" } ?: "")
                stringResource(R.string.settings_repick_time_alarm, time)
            }
        }
        AlertDialog(
            onDismissRequest = onRepickShown,
            title = { Text(stringResource(R.string.settings_repick_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lines.isNotEmpty()) {
                        Text(stringResource(R.string.settings_repick_intro))
                        lines.forEach { Text(it) }
                        Text(stringResource(R.string.settings_repick_outro))
                    }
                    if (repick.defaultLost) {
                        Text(stringResource(if (lines.isEmpty()) R.string.settings_repick_default_only else R.string.settings_repick_default))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onRepickShown) { Text(stringResource(R.string.settings_repick_ok)) }
            },
        )
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.settings_unpair_title)) },
            text = { Text(stringResource(R.string.settings_unpair_message, partner)) },
            confirmButton = {
                TextButton(onClick = { confirmUnpair = false; onUnpair() }) {
                    Text(stringResource(R.string.settings_unpair))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun SectionHeader(titleRes: Int) {
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun NameField(saved: String, onSave: (String) -> Unit) {
    var text by remember(saved) { mutableStateOf(saved) }
    var hadFocus by remember { mutableStateOf(false) }
    val save = { if (text != saved) onSave(text) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.settings_name)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
        trailingIcon = {
            if (text != saved) {
                IconButton(onClick = save) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.settings_name_save))
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .onFocusChanged { if (hadFocus && !it.isFocused) save(); hadFocus = it.isFocused },
    )
}

private fun previewState(paired: Boolean) = SettingsUiState(
    snoozeMinutes = 10, displayName = "Sam", ringtoneUri = "file:///x", ringtoneTitle = "Morning birds.mp3",
    wallpaperPath = "/nonexistent.jpg", paired = paired, partnerName = if (paired) "Alex" else null,
)

@Preview(showBackground = true)
@Composable
private fun SettingsPairedPreview() {
    TandemTheme { SettingsScreen(previewState(true), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
}

@Preview(showBackground = true)
@Composable
private fun SettingsUnpairedPreview() {
    TandemTheme { SettingsScreen(previewState(false), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
}
