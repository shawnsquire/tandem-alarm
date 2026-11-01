package me.shawnsquire.tandem.media

import com.google.firebase.storage.StorageException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStorageTest {
    @Test fun serverRepliesCountAsReachable() {
        assertTrue(answeredByServer(StorageException.ERROR_OBJECT_NOT_FOUND, 404))
        assertTrue(answeredByServer(StorageException.ERROR_NOT_AUTHORIZED, 403))
        assertTrue(answeredByServer(StorageException.ERROR_QUOTA_EXCEEDED, 0))
        assertTrue(answeredByServer(StorageException.ERROR_UNKNOWN, 412))
    }

    @Test fun networkFailuresDoNot() {
        assertFalse(answeredByServer(StorageException.ERROR_RETRY_LIMIT_EXCEEDED, -2))
        assertFalse(answeredByServer(StorageException.ERROR_UNKNOWN, 0))
        assertFalse(answeredByServer(StorageException.ERROR_CANCELED, 0))
    }
}
