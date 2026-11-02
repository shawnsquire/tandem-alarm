package me.shawnsquire.tandem.alarm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.TandemApp
import me.shawnsquire.tandem.media.RingWallpaper
import me.shawnsquire.tandem.schedule.RingOrigin
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.TextPosition
import me.shawnsquire.tandem.schedule.WallpaperFrame
import me.shawnsquire.tandem.ui.theme.TandemTheme
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
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

/** [content] as resolved for the ring [ringId]: a merge keeps showing it until the new one resolves, a new ring doesn't. */
private class ShownContent(val ringId: String, val content: RingScreenContent)

/** Full-screen ringing UI. Finishes when the ring ends (see [RingingScreenModel]). */
class RingingActivity : ComponentActivity() {
    private var ring by mutableStateOf<RingState?>(null)
    private var notice by mutableStateOf<RemoteRingEnd?>(null)

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
                {
                    awaitRingEnd(
                        engine.ringStateStore.state, model, userStopped = userStopped,
                        remoteNotices = engine.remoteRingNotices.latest, onNotice = { notice = it }, onRing = { ring = it },
                    )
                },
                onFailure = { Log.e(TAG, "ring state observation failed; closing screen", it) },
            )
            finish()
        }

        setContent {
            TandemTheme {
                val current = ring
                val defaultWallpaper by produceState<RingWallpaper?>(null) {
                    try {
                        combine(engine.settings.defaultWallpaperPath, engine.settings.defaultWallpaperFrame) { path, frame ->
                            path?.let { RingWallpaper(it, frame) }
                        }.collect { value = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        value = null
                    }
                }
                val partnerName by produceState<String?>(null) { partnerNames().collect { value = it } }
                // Keyed on the members as well as the ringId: a merge or a promotion changes what is shown.
                val shown by produceState<ShownContent?>(
                    null, current?.ringId, current?.alarmId, current?.occurrenceAt, current?.merged, defaultWallpaper, partnerName,
                ) {
                    val r = current ?: return@produceState
                    val content = try {
                        ringScreenContent(r, engine.alarmRepository::get, engine.linkedMediaCache, defaultWallpaper, partnerName)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "resolving the ring's labels failed", e)
                        null
                    }
                    value = content?.let { ShownContent(r.ringId, it) }
                }
                val content = shown?.takeIf { it.ringId == current?.ringId }?.content
                val ended = notice?.endsRing == true
                RingingScreen(
                    labels = content?.labels.orEmpty(),
                    withPartner = content?.withPartner,
                    notice = notice,
                    partnerName = partnerName,
                    wallpapers = content?.wallpapers ?: chooseWallpapers(null, null, defaultWallpaper),
                    textPosition = content?.textPosition ?: TextPosition.MIDDLE,
                    ring = current,
                    // The ring is over; the notice is only saying who ended it.
                    canAct = current != null && !ended,
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

    /**
     * The partner's display name, or null whenever it can't be had. The repository lives in the
     * CE-only AppContainer, whose getter throws while locked and may run full startup, so it is
     * fetched off the main thread; any failure just means no name. Never throws but for cancellation.
     */
    private fun partnerNames(): Flow<String?> = flow {
        val partner = withContext(Dispatchers.IO) { (application as? TandemApp)?.container?.partnerRepository?.partner }
        if (partner != null) emitAll(partner.map { it?.displayName?.takeIf { name -> name.isNotBlank() } })
    }.catch { e ->
        Log.w(TAG, "partner name unavailable", e)
        emit(null)
    }
}

/**
 * The notice for a REMOTE end: "Shawn dismissed", "Shawn snoozed to 6:40", or for one member of a
 * ring that sounds on "Shawn dismissed the 6:30 alarm". Without a name, a neutral "on your partner's phone".
 */
private fun remoteNoticeText(context: Context, end: RemoteRingEnd, partner: String?, format: (Instant) -> String): String {
    val until = end.snoozedUntil?.let(format)
    val member = end.member?.takeIf { !end.endsRing }?.let { format(it.occurrenceAt) }
    return if (end.kind == MirrorKind.SNOOZE && until != null) {
        when {
            member != null && partner != null -> context.getString(R.string.ringing_remote_snoozed_member, partner, member, until)
            member != null -> context.getString(R.string.ringing_remote_snoozed_member_unnamed, member, until)
            partner != null -> context.getString(R.string.ringing_remote_snoozed, partner, until)
            else -> context.getString(R.string.ringing_remote_snoozed_unnamed, until)
        }
    } else {
        when {
            member != null && partner != null -> context.getString(R.string.ringing_remote_dismissed_member, partner, member)
            member != null -> context.getString(R.string.ringing_remote_dismissed_member_unnamed, member)
            partner != null -> context.getString(R.string.ringing_remote_dismissed, partner)
            else -> context.getString(R.string.ringing_remote_dismissed_unnamed)
        }
    }
}

@Composable
private fun RingingScreen(
    labels: List<String>,
    withPartner: String?,
    notice: RemoteRingEnd?,
    partnerName: String?,
    wallpapers: List<RingWallpaper>,
    textPosition: TextPosition,
    ring: RingState?,
    canAct: Boolean,
    onStop: () -> Unit,
    onSnooze: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val metrics = context.resources.displayMetrics
    val decoded by produceState<DecodedWallpaper?>(null, wallpapers) {
        value = withContext(Dispatchers.IO) {
            firstDecoded(wallpapers) { decodeSampled(it, metrics.widthPixels, metrics.heightPixels) }
                ?.let { (bitmap, frame) -> DecodedWallpaper(bitmap, frame) }
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

    val noticeText = notice?.let {
        remember(it, partnerName, formatter) {
            remoteNoticeText(context, it, partnerName) { at -> formatter.format(at.atZone(ZoneId.systemDefault())) }
        }
    }
    val alarmLine = ring?.let {
        remember(it, formatter) { alarmTimeLine(it) { at -> formatter.format(at.atZone(ZoneId.systemDefault())) } }
    }

    RingScreenLayout(
        wallpaper = decoded?.bitmap?.asImageBitmap(),
        frame = decoded?.frame,
        textPosition = textPosition,
        text = { RingScreenText(formatter.format(time), alarmLine, labels, withPartner, noticeText) },
        buttons = { RingScreenButtons(canAct, onSnooze, onStop) },
    )
}

/** The first of [candidates] that [decode] can read, kept together with the frame it was imported with. */
internal fun <T : Any> firstDecoded(candidates: List<RingWallpaper>, decode: (String) -> T?): Pair<T, WallpaperFrame?>? =
    candidates.firstNotNullOfOrNull { c -> decode(c.path)?.let { it to c.frame } }

private class DecodedWallpaper(val bitmap: Bitmap, val frame: WallpaperFrame?)

/** Decodes [path] downsampled to fit the [reqW]x[reqH] memory budget; null if missing or unreadable. */
internal fun decodeSampled(path: String, reqW: Int, reqH: Int): Bitmap? = try {
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
