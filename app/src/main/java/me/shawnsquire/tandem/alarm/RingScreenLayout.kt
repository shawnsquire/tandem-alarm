package me.shawnsquire.tandem.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.WallpaperFrame
import me.shawnsquire.tandem.ui.theme.TandemTheme
import kotlin.math.roundToInt

/**
 * The whole ringing screen, drawn by the ringing activity and by the editor's preview so the two
 * can't drift: the gradient, [wallpaper] placed by [frame] (null = centred cover crop; drawn at the
 * size this composable is laid out at), the scrim, then a column inside the safe-drawing insets with
 * the [text] block at [textPosition] and the [buttons] pinned at the bottom whatever the position.
 * A text block taller than the room above the buttons scrolls instead of pushing them off screen.
 * Callers fill the screen; a preview overlays its own controls on top.
 */
@Composable
fun RingScreenLayout(
    wallpaper: ImageBitmap?,
    frame: WallpaperFrame?,
    textPosition: TextPosition,
    text: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    /** 0 hides the [text] while keeping its room, so the layout does not jump when it appears. */
    textAlpha: Float = 1f,
) {
    Box(
        modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF1B2440))))
            .clipToBounds()
            .drawBehind {
                if (wallpaper == null) return@drawBehind
                val t = frameTransform(frame, wallpaper.width.toFloat(), wallpaper.height.toFloat(), size.width, size.height)
                drawImage(
                    wallpaper,
                    dstOffset = IntOffset(t.left.roundToInt(), t.top.roundToInt()),
                    dstSize = IntSize((wallpaper.width * t.scale).roundToInt(), (wallpaper.height * t.scale).roundToInt()),
                )
            },
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The text gets the room above the buttons and sits at its edge or centre of it.
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = when (textPosition) {
                    TextPosition.TOP -> Alignment.TopCenter
                    TextPosition.MIDDLE -> Alignment.Center
                    TextPosition.BOTTOM -> Alignment.BottomCenter
                },
            ) {
                Column(
                    Modifier.fillMaxWidth().alpha(textAlpha).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = text,
                )
            }
            Spacer(Modifier.height(if (textPosition == TextPosition.BOTTOM) 32.dp else 16.dp))
            Column(Modifier.fillMaxWidth(), content = buttons)
        }
    }
}

/**
 * The ringing screen's text block: the big clock [time], the smaller [alarmLine] under it, one line
 * per label, "With <partner>" and the remote [notice], each only when present.
 */
@Composable
fun ColumnScope.RingScreenText(
    time: String,
    alarmLine: String?,
    labels: List<String>,
    withPartner: String?,
    notice: String?,
) {
    Text(
        time,
        style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp, lineHeight = 104.sp),
        color = Color.White,
        textAlign = TextAlign.Center,
    )
    if (alarmLine != null) {
        Text(
            alarmLine, style = MaterialTheme.typography.titleLarge,
            color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
        )
    }
    for (label in labels) {
        Spacer(Modifier.height(8.dp))
        Text(
            label, style = MaterialTheme.typography.headlineMedium,
            color = Color.White, textAlign = TextAlign.Center,
        )
    }
    if (withPartner != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ringing_with_partner, withPartner), style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
        )
    }
    if (notice != null) {
        Spacer(Modifier.height(24.dp))
        Text(
            notice, style = MaterialTheme.typography.titleLarge,
            color = Color.White, textAlign = TextAlign.Center,
        )
    }
}

/** Snooze and Stop at the size and place the ringing screen has them; the preview passes no-op handlers and canAct = true. */
@Composable
fun ColumnScope.RingScreenButtons(canAct: Boolean, onSnooze: () -> Unit, onStop: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().alpha(if (canAct) 1f else 0.5f),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FilledTonalButton(
            onClick = onSnooze, enabled = canAct,
            modifier = Modifier.fillMaxWidth().height(80.dp),
        ) { Text(stringResource(R.string.alarm_action_snooze), fontSize = 24.sp) }
        Button(
            onClick = onStop, enabled = canAct,
            modifier = Modifier.fillMaxWidth().height(96.dp),
            colors = ButtonDefaults.buttonColors(),
        ) { Text(stringResource(R.string.alarm_action_stop), fontSize = 28.sp) }
    }
}

private val sampleLabels = listOf(
    "Wake up and go for the long morning run before work",
    "Take the medication with a full glass of water and breakfast",
    "Call the dentist about rescheduling the appointment",
)

@Composable
private fun RingScreenSample(position: TextPosition) {
    TandemTheme {
        RingScreenLayout(
            wallpaper = null, frame = null, textPosition = position,
            text = { RingScreenText("6:30", "6:30 alarm · snoozed 2×", sampleLabels, "Sam", null) },
            buttons = { RingScreenButtons(canAct = true, onSnooze = {}, onStop = {}) },
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun RingScreenTopPreview() = RingScreenSample(TextPosition.TOP)

@Preview(showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun RingScreenMiddlePreview() = RingScreenSample(TextPosition.MIDDLE)

@Preview(showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun RingScreenBottomPreview() = RingScreenSample(TextPosition.BOTTOM)
