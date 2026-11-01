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

/** The blob is not in Storage; permanent until the owner uploads it again. */
class MediaNotFoundException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Cloud Storage access for `households/{hid}/media/{ownerUid}/{sha}`. All calls throw on failure. */
interface MediaStorage {
    /** Creates the blob; the rules deny updates, so re-uploading an existing sha is only for retries after a lost result. */
    suspend fun upload(hid: String, ownerUid: String, sha: String, file: File, contentType: String)

    /** A blob that is already gone counts as deleted. */
    suspend fun delete(hid: String, ownerUid: String, sha: String)

    /** Writes the blob to [dest]; throws [MediaNotFoundException] if absent, [IOException] (and leaves no [dest]) if larger than [maxBytes]. */
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
            // The server answered, so Storage is reachable; a missing blob is a success.
            report(answeredByServer(e.errorCode, e.httpResultCode))
            if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw e
        } catch (e: Exception) {
            report(false)
            throw e
        }
    }

    override suspend fun download(hid: String, ownerUid: String, sha: String, dest: File, maxBytes: Long) {
        var oversize = false
        reported(answered = { oversize || it is MediaNotFoundException }) {
            try {
                ref(hid, ownerUid, sha).getStream { _, stream ->
                    dest.outputStream().use { out ->
                        val copied = stream.copyToLimited(out, maxBytes)
                        if (copied > maxBytes) {
                            oversize = true
                            throw IOException("$sha is larger than $maxBytes bytes")
                        }
                    }
                }.await()
                Unit
            } catch (e: StorageException) {
                dest.delete()
                if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) throw MediaNotFoundException("$sha is not in Storage", e)
                throw e
            } catch (t: Throwable) {
                dest.delete()
                throw t
            }
        }
    }

    /** Reports the outcome; a failure still counts as reachable when [answered] says the server replied. */
    private suspend fun reported(answered: (Throwable) -> Boolean = { false }, block: suspend () -> Unit) {
        try {
            block()
            report(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(answered(e) || (e is StorageException && answeredByServer(e.errorCode, e.httpResultCode)))
            throw e
        }
    }

    private suspend fun report(ok: Boolean) {
        try { withContext(Dispatchers.Default) { onResult(ok) } } catch (e: CancellationException) { throw e } catch (_: Exception) { /* a listener must not fail a Storage call */ }
    }
}

/** True when a [StorageException]'s codes show a server reply (HTTP status or a server-side error), false for network and unknown failures. */
internal fun answeredByServer(errorCode: Int, httpResultCode: Int): Boolean =
    httpResultCode in 100..599 || errorCode in SERVER_ERRORS

private val SERVER_ERRORS = setOf(
    StorageException.ERROR_OBJECT_NOT_FOUND,
    StorageException.ERROR_BUCKET_NOT_FOUND,
    StorageException.ERROR_PROJECT_NOT_FOUND,
    StorageException.ERROR_QUOTA_EXCEEDED,
    StorageException.ERROR_NOT_AUTHENTICATED,
    StorageException.ERROR_NOT_AUTHORIZED,
)

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
