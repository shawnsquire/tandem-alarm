package me.shawnsquire.tandem.media

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.sync.await
import java.io.File
import java.io.IOException

/** Cloud Storage access for `households/{hid}/media/{ownerUid}/{sha}`. All calls throw on failure. */
interface MediaStorage {
    /** Creates the blob; the rules deny updates, so re-uploading an existing sha is only for retries after a lost result. */
    suspend fun upload(hid: String, ownerUid: String, sha: String, file: File, contentType: String)

    /** A blob that is already gone counts as deleted. */
    suspend fun delete(hid: String, ownerUid: String, sha: String)

    /** Writes the blob to [dest]; throws [IOException] (and leaves no [dest]) if it is larger than [maxBytes]. */
    suspend fun download(hid: String, ownerUid: String, sha: String, dest: File, maxBytes: Long)
}

/**
 * [MediaStorage] on Firebase Storage. [onResult] hears every call's outcome (the health check's "Storage reachable").
 * [storage] is resolved on first use, so building this touches no Firebase instance (the emulator must be set first).
 */
class FirebaseMediaStorage(
    storage: () -> FirebaseStorage,
    private val onResult: suspend (ok: Boolean) -> Unit = {},
) : MediaStorage {
    private val storage by lazy(storage)

    private fun ref(hid: String, ownerUid: String, sha: String) =
        storage.reference.child("households/$hid/media/$ownerUid/$sha")

    override suspend fun upload(hid: String, ownerUid: String, sha: String, file: File, contentType: String) = reported {
        // Set explicitly: the object name has no extension, and the rules deny the octet-stream Storage would infer.
        val metadata = StorageMetadata.Builder().setContentType(contentType).build()
        ref(hid, ownerUid, sha).putFile(Uri.fromFile(file), metadata).await()
        Unit
    }

    override suspend fun delete(hid: String, ownerUid: String, sha: String) {
        try {
            ref(hid, ownerUid, sha).delete().await()
            report(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageException) {
            // The server answered, so Storage is reachable.
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                report(true)
            } else {
                report(false)
                throw e
            }
        } catch (e: Exception) {
            report(false)
            throw e
        }
    }

    override suspend fun download(hid: String, ownerUid: String, sha: String, dest: File, maxBytes: Long) = reported {
        try {
            ref(hid, ownerUid, sha).getStream { _, stream ->
                dest.outputStream().use { out ->
                    val copied = stream.copyToLimited(out, maxBytes)
                    if (copied > maxBytes) throw IOException("$sha is larger than $maxBytes bytes")
                }
            }.await()
            Unit
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
    }

    private suspend fun reported(block: suspend () -> Unit) {
        try {
            block()
            report(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(false)
            throw e
        }
    }

    private suspend fun report(ok: Boolean) {
        try { withContext(Dispatchers.Default) { onResult(ok) } } catch (e: CancellationException) { throw e } catch (_: Exception) { /* a listener must not fail a Storage call */ }
    }
}

/** Copies at most [limit] + 1 bytes and returns how many were copied, so a caller can tell "over the limit" without reading the rest. */
internal fun java.io.InputStream.copyToLimited(out: java.io.OutputStream, limit: Long): Long {
    val buf = ByteArray(COPY_BUFFER)
    var total = 0L
    while (total <= limit) {
        val n = read(buf, 0, minOf(buf.size.toLong(), limit + 1 - total).toInt())
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return total
}

private const val COPY_BUFFER = 64 * 1024
