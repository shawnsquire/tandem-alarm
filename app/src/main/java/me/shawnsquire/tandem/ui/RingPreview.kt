package me.shawnsquire.tandem.ui

import android.text.format.DateFormat
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.alarm.RingScreenButtons
import me.shawnsquire.tandem.alarm.RingScreenLayout
import me.shawnsquire.tandem.alarm.RingScreenText
import me.shawnsquire.tandem.alarm.applyGesture
import me.shawnsquire.tandem.alarm.decodeSampled
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.WallpaperFrame
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A full-screen preview of the ringing screen for framing a wallpaper: the image, scrim, a live
 * clock, a sample alarm line for [hour]:[minute] and [label], and the inert Snooze/Stop buttons,
 * laid out exactly as the ringing activity does. Drag and pinch move the image when [gesturesEnabled];
 * [showPositionSelector] offers Top/Middle/Bottom for the text block. Save reports the draft frame
 * (null while untouched or reset, and [initialFrame] unchanged without gestures) and position;
 * Reset returns the position to [resetTextPosition]. Cancel and Back discard it. The wallpaper is [wallpaperPath], or the plain gradient when null or unreadable.
 *
 * It is a [Dialog] drawn edge-to-edge (`usePlatformDefaultWidth = false`, `decorFitsSystemWindows = false`),
 * so it has the bounds of the ringing activity's window and the frame it saves is what rings.
 */
@Composable
fun RingPreview(
    wallpaperPath: String?,
    initialFrame: WallpaperFrame?,
    gesturesEnabled: Boolean,
    initialTextPosition: TextPosition,
    showPositionSelector: Boolean,
    hour: Int,
    minute: Int,
    label: String,
    onSave: (frame: WallpaperFrame?, position: TextPosition) -> Unit,
    onCancel: () -> Unit,
    resetTextPosition: TextPosition = TextPosition.MIDDLE,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        RingPreviewContent(
            wallpaperPath, initialFrame, gesturesEnabled, initialTextPosition, showPositionSelector,
            hour, minute, label, onSave, onCancel, resetTextPosition,
        )
    }
}

@Composable
private fun RingPreviewContent(
    wallpaperPath: String?,
    initialFrame: WallpaperFrame?,
    gesturesEnabled: Boolean,
    initialTextPosition: TextPosition,
    showPositionSelector: Boolean,
    hour: Int,
    minute: Int,
    label: String,
    onSave: (WallpaperFrame?, TextPosition) -> Unit,
    onCancel: () -> Unit,
    resetTextPosition: TextPosition,
) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val bitmap by produceState<android.graphics.Bitmap?>(null, wallpaperPath) {
        value = wallpaperPath?.let { path ->
            withContext(Dispatchers.IO) { decodeSampled(path, metrics.widthPixels, metrics.heightPixels) }
        }
    }
    val is24 = remember { DateFormat.is24HourFormat(context) }
    val formatter = remember(is24) {
        if (is24) DateTimeFormatter.ofPattern("HH:mm") else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    }
    val clock by produceState(LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(1_000)
        }
    }
    val alarmLine = stringResource(R.string.ring_preview_alarm_line, formatter.format(LocalTime.of(hour, minute)))

    var frame by remember { mutableStateOf(initialFrame) }
    var position by remember { mutableStateOf(initialTextPosition) }
    // The size the layout is drawn at: the framing math needs the real screen, not the controls' bounds.
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentBitmap by rememberUpdatedState(bitmap)
    val currentSize by rememberUpdatedState(size)

    Box(Modifier.onSizeChanged { size = it }) {
        RingScreenLayout(
            wallpaper = bitmap?.asImageBitmap(),
            frame = frame,
            textPosition = position,
            text = { RingScreenText(formatter.format(clock), alarmLine, listOfNotNull(label.ifBlank { null }), null, null) },
            buttons = { RingScreenButtons(canAct = false, onSnooze = {}, onStop = {}) },
            modifier = Modifier.pointerInput(gesturesEnabled) {
                if (!gesturesEnabled) return@pointerInput
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val b = currentBitmap ?: return@detectTransformGestures
                    val s = currentSize
                    if (s.width == 0 || s.height == 0) return@detectTransformGestures
                    frame = applyGesture(
                        frame, pan.x, pan.y, zoom, centroid.x, centroid.y,
                        b.width.toFloat(), b.height.toFloat(), s.width.toFloat(), s.height.toFloat(),
                    )
                }
            },
        )
        // Top, so Snooze and Stop stay in view; with the text at the top the bar moves to the middle instead.
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .align(if (position == TextPosition.TOP) Alignment.Center else Alignment.TopCenter)
                .then(if (position == TextPosition.TOP) Modifier else Modifier.statusBarsPadding())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showPositionSelector) {
                    val selectorLabel = stringResource(R.string.ring_preview_text_position)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = selectorLabel }) {
                        val options = TextPosition.entries
                        options.forEachIndexed { i, option ->
                            SegmentedButton(
                                selected = position == option,
                                onClick = { position = option },
                                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            ) {
                                Text(
                                    stringResource(
                                        when (option) {
                                            TextPosition.TOP -> R.string.ring_preview_top
                                            TextPosition.MIDDLE -> R.string.ring_preview_middle
                                            TextPosition.BOTTOM -> R.string.ring_preview_bottom
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        if (gesturesEnabled) frame = null
                        if (showPositionSelector) position = resetTextPosition
                    }) { Text(stringResource(R.string.ring_preview_reset)) }
                    Row {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.ring_preview_cancel)) }
                        TextButton(onClick = { onSave(if (gesturesEnabled) frame else initialFrame, position) }) {
                            Text(stringResource(R.string.ring_preview_save))
                        }
                    }
                }
            }
        }
    }
}
