package me.shawnsquire.tandem.media

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.sync.Household
import me.shawnsquire.tandem.sync.LinkRepository
import me.shawnsquire.tandem.sync.MediaPublisher
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.sync.PublishedMedia
import java.io.File

private const val TAG = "MediaPublisher"

/** Storage rules' size cap, inclusive. */
internal const val MAX_MEDIA_BYTES = 15_728_640L

private const val FIRST_RETRY_MS = 60_000L
private const val MAX_RETRY_MS = 60 * 60_000L

/**
 * Owner-side [MediaPublisher]: uploads the wallpaper and ringtone of every own alarm that has a link
 * or an open invite, plus the default wallpaper, as `households/{hid}/media/{myUid}/{sha}`.
 *
 * Refs and the uploaded shas (per household id, since unpair deletes the household's media) are
 * persisted in [store]. Nothing is uploaded or deleted while [household] is null.
 *
 * A Firebase task keeps running when its coroutine is cancelled, so a sha is persisted as "may be in
 * Storage" ([MediaPublishState.pendingDelete]) before an upload or delete starts and only moved to
 * or out of `uploaded` once it finishes. Cleanup deletes every unreferenced sha in either set.
 */
class MediaPublisherImpl(
    private val alarms: AlarmRepository,
    private val partner: PartnerRepository,
    private val links: LinkRepository,
    private val settings: SettingsRepository,
    private val household: Flow<Household?>,
    private val storage: MediaStorage,
    private val source: MediaSource,
    private val store: MediaPublishStore,
    private val tmpDir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MediaPublisher {
    /** The covered alarms with the only fields a pass reads, so unrelated alarm edits start no pass. */
    private data class Covered(val id: String, val wallpaperPath: String?, val ringtoneUri: String?)

    private data class Input(val household: Household?, val covered: List<Covered>, val defaultWallpaper: String?)

    private sealed interface Outcome {
        data class Ok(val ref: MediaRef?) : Outcome
        data object Failed : Outcome
    }

    // Read lazily, off the caller's thread: a collector may arrive before start().
    private val state: MutableStateFlow<MediaPublishState> by lazy { MutableStateFlow(store.load()) }
    private var job: Job? = null

    /**
     * The persisted refs, emitted at once; once the household is known and differs from the one they were
     * made for (re-pair), empty refs instead, since those blobs do not exist in the new household.
     */
    override val published: Flow<PublishedMedia?> = flow {
        val s = withContext(io) { state }
        emitAll(
            combine(s, household.onStart { emit(null) }) { st, hh ->
                if (hh != null && st.published != null && st.publishedHid != null && st.publishedHid != hh.id) {
                    PublishedMedia(null, emptyMap())
                } else {
                    st.published
                }
            }.distinctUntilChanged(),
        )
    }

    @Synchronized
    override fun start() {
        if (job != null) return
        job = scope.launch {
            inputs().collectLatest { input ->
                var wait = FIRST_RETRY_MS
                // No input change may come after a failure, so retry on a timer; a new input supersedes the wait.
                while (runPass(input)) {
                    delay(wait)
                    wait = minOf(wait * 2, MAX_RETRY_MS)
                }
            }
        }
    }

    private fun inputs(): Flow<Input> = combine(
        alarms.ownAlarms,
        partner.partner.map { p -> p?.links?.map { it.ownerAlarmId }.orEmpty().toSet() }.distinctUntilChanged(),
        links.openInvites,
        settings.defaultWallpaperPath,
        household,
    ) { own, linked, invited, defaultWallpaper, hh ->
        val ids = linked + invited
        Input(hh, own.filter { it.id in ids }.map { Covered(it.id, it.wallpaperPath, it.ringtoneUri) }, defaultWallpaper)
    }.distinctUntilChanged()

    /** One pass; true when something failed and should be retried. Never throws except for cancellation. */
    private suspend fun runPass(input: Input): Boolean {
        val hh = input.household ?: return false
        return try {
            withContext(io) { pass(hh, input) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "media pass failed", e)
            true
        }
    }

    private suspend fun pass(hh: Household, input: Input): Boolean {
        var failed = false
        // Refs of another household point at blobs that do not exist here, so they are no fallback.
        val prev = state.value.published.takeIf { state.value.publishedHid == hh.id }
        val tracked = Tracked(
            state.value.uploaded[hh.id].orEmpty().toMutableSet(),
            state.value.pendingDelete[hh.id].orEmpty().toMutableSet(),
        )

        suspend fun resolve(path: String?, kind: MediaKind, previous: MediaRef?): MediaRef? {
            if (path == null) return null
            return when (val o = publish(hh, path, kind, tracked)) {
                is Outcome.Ok -> o.ref
                Outcome.Failed -> { failed = true; previous }
            }
        }

        val defaultWallpaper = resolve(input.defaultWallpaper, MediaKind.WALLPAPER, prev?.defaultWallpaper)
        val perAlarm = mutableMapOf<String, AlarmMedia>()
        for (c in input.covered) {
            val before = prev?.alarms?.get(c.id)
            val m = AlarmMedia(
                wallpaper = resolve(c.wallpaperPath, MediaKind.WALLPAPER, before?.wallpaper),
                ringtone = resolve(c.ringtoneUri, MediaKind.RINGTONE, before?.ringtone),
            )
            if (m.wallpaper != null || m.ringtone != null) perAlarm[c.id] = m
        }
        val refs = if (state.value.published == null && defaultWallpaper == null && perAlarm.isEmpty()) null else PublishedMedia(defaultWallpaper, perAlarm)

        val referenced = buildSet {
            refs?.defaultWallpaper?.let { add(it.sha) }
            refs?.alarms?.values?.forEach { m -> m.wallpaper?.let { add(it.sha) }; m.ringtone?.let { add(it.sha) } }
        }
        commit { it.copy(published = refs, publishedHid = hh.id) }
        commitTracked(hh, tracked)

        // Never delete what the refs just emitted point at, even if it is still in a set.
        for (sha in (tracked.uploaded + tracked.pending) - referenced) {
            currentCoroutineContext().ensureActive()
            try {
                // Persist "might be gone" first: the delete can finish server-side and still be cancelled or lose its result.
                tracked.uploaded -= sha
                tracked.pending += sha
                commitTracked(hh, tracked)
                // Not cancellable: collectLatest joins this pass before starting the next, so a re-upload of
                // the same sha cannot be overtaken by a delete that is still running server-side.
                withContext(NonCancellable) {
                    storage.delete(hh.id, hh.myUid, sha)
                    tracked.pending -= sha
                    commitTracked(hh, tracked)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "delete failed", e)
                failed = true
            }
        }
        return failed
    }

    /** The shas of one household: [uploaded] is known to be in Storage, [pending] may be and is not to be skipped by an upload. */
    private class Tracked(val uploaded: MutableSet<String>, val pending: MutableSet<String>)

    private fun commitTracked(hh: Household, t: Tracked) =
        commit { it.copy(uploaded = mapOf(hh.id to t.uploaded.toSet()), pendingDelete = mapOf(hh.id to t.pending.toSet())) }

    /** Copies, hashes and (unless [t] has it uploaded) uploads one file, recording the sha as pending before and as uploaded after. */
    private suspend fun publish(hh: Household, path: String, kind: MediaKind, t: Tracked): Outcome {
        val tmp = File(tmpDir, "publish-${System.nanoTime()}.tmp")
        try {
            check(tmpDir.isDirectory || tmpDir.mkdirs()) { "Cannot create $tmpDir" }
            val info = source.copy(path, kind, tmp, MAX_MEDIA_BYTES)
            val size = tmp.length()
            if (size > MAX_MEDIA_BYTES) {
                Log.w(TAG, "skipping oversized $kind")
                return Outcome.Ok(null)
            }
            val sha = sha256Hex(tmp)
            if (sha !in t.uploaded) {
                val contentType = when (kind) {
                    MediaKind.WALLPAPER -> "image/jpeg"
                    // Files named by hash have no extension, so Storage would infer octet-stream, which the rules deny.
                    MediaKind.RINGTONE -> info.contentType?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
                }
                // A cancelled upload may still finish server-side; pending makes cleanup delete it if nothing references it.
                t.pending += sha
                commitTracked(hh, t)
                // Not cancellable: the SDK upload keeps running when cancelled, and collectLatest joins this pass
                // before the next, so cleanup cannot delete the sha before the upload has finalized.
                withContext(NonCancellable) {
                    storage.upload(hh.id, hh.myUid, sha, tmp, contentType)
                    t.pending -= sha
                    t.uploaded += sha
                    commitTracked(hh, t)
                }
            }
            return Outcome.Ok(MediaRef(sha, size, if (kind == MediaKind.RINGTONE) info.title else null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "publishing $kind failed", e)
            return Outcome.Failed
        } finally {
            tmp.delete()
        }
    }

    @Synchronized
    private fun commit(change: (MediaPublishState) -> MediaPublishState) {
        val next = change(state.value)
        // Only the current household is kept: older ones were deleted server-side on unpair.
        // In-memory first: a failed write costs a re-upload after a restart, not a lost ref.
        state.value = next
        try { store.save(next) } catch (e: Exception) { Log.w(TAG, "cannot save media state", e) }
    }
}
