package me.shawnsquire.tandem.alarm

import android.graphics.Bitmap
import android.util.Log
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.RingOrigin
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.ui.theme.TandemTheme
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val TAG = "RingingActivity"

/**
 * Runs [block], reporting any failure except cancellation to [onFailure] instead of throwing.
 * Returns normally either way so the caller can finish the screen.
 */
internal suspend fun guardedRingEnd(block: suspend () -> Unit, onFailure: (Throwable) -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        onFailure(e)
    }
}

private suspend fun runLogged(what: String, block: suspend () -> Unit) =
    guardedRingEnd(block) { Log.e(TAG, "$what failed", it) }

/** Full-screen ringing UI. Finishes when the ring ends (see [RingingScreenModel]). */
class RingingActivity : ComponentActivity() {
    private var ring by mutableStateOf<RingState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        // Works before the first unlock: wallpapers are CE files, so a locked ring gets the plain screen.
        val engine = AlarmEngine.get(this)
        val model = RingingScreenModel()

        val userStopped = CompletableDeferred<Unit>()
        lifecycleScope.launch {
            // A failure here must never crash the process: it hosts the service and the sound.
            guardedRingEnd(
                { awaitRingEnd(engine.ringStateStore.state, model, userStopped = userStopped, onRing = { ring = it }) },
                onFailure = { Log.e(TAG, "ring state observation failed; closing screen", it) },
            )
            finish()
        }

        setContent {
            TandemTheme {
                val current = ring
                val alarm by produceState<Alarm?>(null, current?.ringId) {
                    value = null
                    value = try {
                        current?.let { engine.alarmRepository.get(it.alarmId) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                val defaultPath by produceState<String?>(null) {
                    try {
                        engine.settings.defaultWallpaperPath.collect { value = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        value = null
                    }
                }
                RingingScreen(
                    label = alarm?.label.orEmpty(),
                    wallpaperPaths = chooseWallpaperPaths(alarm?.wallpaperPath, defaultPath),
                    canAct = current != null,
                    onStop = {
                        val id = current?.ringId ?: return@RingingScreen
                        lifecycleScope.launch {
                            runLogged("dismiss") { engine.controller.dismiss(id, RingOrigin.LOCAL) }
                        }
                        // Close at once; the controller's dismiss is NonCancellable.
                        userStopped.complete(Unit)
                    },
                    onSnooze = {
                        lifecycleScope.launch {
                            runLogged("snooze") { engine.controller.snooze(RingOrigin.LOCAL) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RingingScreen(
    label: String,
    wallpaperPaths: List<String>,
    canAct: Boolean,
    onStop: () -> Unit,
    onSnooze: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val metrics = context.resources.displayMetrics
    val wallpaper by produceState<Bitmap?>(null, wallpaperPaths) {
        value = withContext(Dispatchers.IO) {
            wallpaperPaths.firstNotNullOfOrNull { decodeSampled(it, metrics.widthPixels, metrics.heightPixels) }
        }
    }
    val is24 = remember { DateFormat.is24HourFormat(context) }
    val formatter = remember(is24) {
        if (is24) DateTimeFormatter.ofPattern("HH:mm")
        else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    }
    val time by produceState(LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(1_000)
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF1B2440))),
        ),
    ) {
        wallpaper?.let {
            Image(
                it.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                formatter.format(time),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp, lineHeight = 104.sp),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            if (label.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    label, style = MaterialTheme.typography.headlineMedium,
                    color = Color.White, textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
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
    }
}

/** Decodes [path] downsampled to fit the [reqW]x[reqH] memory budget; null if missing or unreadable. */
private fun decodeSampled(path: String, reqW: Int, reqH: Int): Bitmap? = try {
    if (!File(path).canRead()) null else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sample = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            sampleSizeFor(bounds.outWidth, bounds.outHeight, reqW, reqH)
        } else 1
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}
