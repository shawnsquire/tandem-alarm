package me.shawnsquire.tandem.media

import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.AlarmLink
import me.shawnsquire.tandem.schedule.LinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.DayOfWeek

private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

private class FakeStorage : MediaStorage {
    val blobs = mutableMapOf<String, ByteArray>()
    val failing = mutableSetOf<String>()
    val downloads = mutableListOf<String>()
    override suspend fun upload(hid: String, ownerUid: String, sha: String, file: File, contentType: String) = Unit
    override suspend fun delete(hid: String, ownerUid: String, sha: String) = Unit
    override suspend fun download(hid: String, ownerUid: String, sha: String, dest: File, maxBytes: Long) {
        downloads += "$hid/$ownerUid/$sha"
        if (sha in failing) throw IOException("boom")
        dest.writeBytes(blobs.getValue(sha))
    }
}

@RunWith(RobolectricTestRunner::class)
class LinkedMediaCacheImplTest {
    @get:Rule val tmp = TemporaryFolder()

    private val storage = FakeStorage()
    private var enqueued = 0
    private val sys = "content://settings/system/alarm_alert"

    private fun cache() = LinkedMediaCacheImpl(tmp.root, storage, { enqueued++ }, sys)
    private val linked get() = File(tmp.root, "linked")

    private fun cacheFile(bytes: ByteArray): String {
        val sha = sha256(bytes)
        linked.mkdirs()
        File(linked, sha).writeBytes(bytes)
        return sha
    }

    private val photoBytes = "photo".toByteArray()
    private val ringBytes = "ring".toByteArray()
    private val defBytes = "default".toByteArray()
    private fun ref(b: ByteArray) = MediaRef(sha256(b), b.size.toLong())

    private fun alarm(link: AlarmLink? = link(), label: String = "Owner label") =
        Alarm("row", label, 6, 30, setOf(DayOfWeek.MONDAY), true, null, "content://ignored", "/ignored.jpg", link)

    private fun link(label: String? = null, ring: String? = null, wall: String? = null) =
        AlarmLink("owner", "oa", LinkMode.RING, null, true, null, label, ring, wall)

    private fun fullRefs(c: LinkedMediaCacheImpl) =
        c.update("owner", mapOf("oa" to AlarmMedia(ref(photoBytes), ref(ringBytes))), ref(defBytes))

    private fun cacheAll() { cacheFile(photoBytes); cacheFile(ringBytes); cacheFile(defBytes) }
    private fun path(b: ByteArray) = File(linked, sha256(b)).absolutePath
    private fun uri(b: ByteArray) = "file://" + path(b)

    // photo

    @Test fun photoOrderOverrideThenOwnThenOwnerDefaultThenJoinerDefault() = runTest {
        cacheAll()
        val c = cache().also(::fullRefs)
        val override = tmp.newFile("o.jpg").absolutePath
        assertEquals(
            listOf(override, path(photoBytes), path(defBytes), "/joiner.jpg"),
            c.resolve(alarm(link(wall = override)), null, "/joiner.jpg").wallpaperPaths,
        )
        assertEquals(listOf(path(photoBytes), path(defBytes), "/joiner.jpg"), c.resolve(alarm(), null, "/joiner.jpg").wallpaperPaths)
    }

    @Test fun photoFallsThroughWhenEarlierStepsAreAbsent() = runTest {
        cacheFile(defBytes)
        val c = cache()
        c.update("owner", mapOf("oa" to AlarmMedia(null, null)), ref(defBytes))
        assertEquals(listOf(path(defBytes), "/joiner.jpg"), c.resolve(alarm(), null, "/joiner.jpg").wallpaperPaths)
        c.update("owner", emptyMap(), null)
        assertEquals(listOf("/joiner.jpg"), c.resolve(alarm(), null, "/joiner.jpg").wallpaperPaths)
        assertEquals(emptyList<String>(), c.resolve(alarm(), null, " ").wallpaperPaths)
    }

    @Test fun unreadableWallpaperOverrideFallsToOwnerPhoto() = runTest {
        cacheAll()
        val c = cache().also(::fullRefs)
        val media = c.resolve(alarm(link(wall = "/nope/missing.jpg")), null, null)
        assertEquals(listOf(path(photoBytes), path(defBytes)), media.wallpaperPaths)
    }

    @Test fun uncachedPhotoFallsToNextStep() = runTest {
        cacheFile(defBytes)
        val c = cache().also(::fullRefs)
        assertEquals(listOf(path(defBytes)), c.resolve(alarm(), null, null).wallpaperPaths)
    }

    @Test fun ownWallpaperPathIsIgnoredOnALinkedRow() = runTest {
        val c = cache()
        assertEquals(emptyList<String>(), c.resolve(alarm(), null, null).wallpaperPaths)
        assertEquals(emptyList<String>(), c.resolve(alarm(), null, null).soundCandidates - sys)
    }

    // sound

    @Test fun soundOrderOverrideThenOwnerThenSystemThenJoinerDefault() = runTest {
        cacheAll()
        val c = cache().also(::fullRefs)
        val file = tmp.newFile("o.mp3")
        assertEquals(
            listOf("content://override", uri(ringBytes), sys, "content://joiner"),
            c.resolve(alarm(link(ring = "content://override")), "content://joiner", null).soundCandidates,
        )
        assertEquals(
            listOf(file.absolutePath, uri(ringBytes), sys, "content://joiner"),
            c.resolve(alarm(link(ring = file.absolutePath)), "content://joiner", null).soundCandidates,
        )
        assertEquals(
            listOf("file://" + file.absolutePath, uri(ringBytes), sys),
            c.resolve(alarm(link(ring = "file://" + file.absolutePath)), null, null).soundCandidates,
        )
        assertEquals(listOf(uri(ringBytes), sys, "content://joiner"), c.resolve(alarm(), "content://joiner", null).soundCandidates)
    }

    @Test fun soundFallsToSystemThenJoinerDefault() = runTest {
        val c = cache()
        assertEquals(listOf(sys, "content://joiner"), c.resolve(alarm(), "content://joiner", null).soundCandidates)
        assertEquals(listOf(sys), c.resolve(alarm(), sys, null).soundCandidates)
    }

    @Test fun unreadableSoundOverrideFallsToOwnerRingtoneNotSystem() = runTest {
        cacheAll()
        val c = cache().also(::fullRefs)
        for (bad in listOf("/nope/x.mp3", "file:///nope/x.mp3")) {
            assertEquals(listOf(uri(ringBytes), sys), c.resolve(alarm(link(ring = bad)), null, null).soundCandidates)
        }
    }

    @Test fun uncachedRingtoneFallsToSystemSound() = runTest {
        val c = cache().also(::fullRefs)
        assertEquals(listOf(sys), c.resolve(alarm(), null, null).soundCandidates)
    }

    @Test fun refsOfAnotherOwnerAreIgnored() = runTest {
        cacheAll()
        val c = cache().also(::fullRefs)
        val other = alarm(AlarmLink("someone-else", "oa", LinkMode.RING, null, true, null, null, null, null))
        assertEquals(emptyList<String>(), c.resolve(other, null, null).wallpaperPaths)
    }

    // message

    @Test fun labelOverrideThenAlarmLabel() = runTest {
        val c = cache()
        assertEquals("Mine", c.resolve(alarm(link(label = "Mine")), null, null).label)
        assertEquals("Owner label", c.resolve(alarm(link(label = " ")), null, null).label)
        assertEquals("Owner label", c.resolve(alarm(), null, null).label)
    }

    // non-linked

    @Test fun nonLinkedAlarmResolvesLikeTheDefault() = runTest {
        val c = cache().also(::fullRefs)
        val a = alarm(link = null)
        assertEquals(DefaultLinkedMediaCache.resolve(a, "content://j", "/w.jpg"), c.resolve(a, "content://j", "/w.jpg"))
    }

    // persistence

    @Test fun refsSurviveAFreshInstance() = runTest {
        cacheAll()
        fullRefs(cache())
        val fresh = cache()
        val shas = setOf(sha256(photoBytes), sha256(ringBytes), sha256(defBytes))
        assertEquals(shas, fresh.inUse())
        assertEquals(listOf(path(photoBytes), path(defBytes)), fresh.resolve(alarm(), null, null).wallpaperPaths)
        assertEquals(path(photoBytes), fresh.pathFor(sha256(photoBytes)))
    }

    @Test fun updateReplacesThePreviousState() {
        val c = cache()
        fullRefs(c)
        c.update("owner", mapOf("oa" to AlarmMedia(ref(photoBytes), null)), null)
        assertEquals(setOf(sha256(photoBytes)), cache().inUse())
    }

    @Test fun persistsOutsideTheSweptLinkedDirectory() {
        fullRefs(cache())
        assertTrue(File(tmp.root, "linked-media.json").isFile)
        assertFalse(linked.exists())
    }

    @Test fun invalidShasAreDropped() = runTest {
        val bad = MediaRef("../../x", 1)
        val c = cache()
        c.update("owner", mapOf("oa" to AlarmMedia(bad, MediaRef("A".repeat(64), 1))), MediaRef("abc", 1))
        assertEquals(emptySet<String>(), c.inUse())
        assertEquals(emptySet<String>(), cache().inUse())
        assertEquals(0, enqueued)
    }

    @Test fun pathForRejectsInvalidShaAndMissingFiles() {
        val c = cache()
        assertNull(c.pathFor("../x"))
        assertNull(c.pathFor("a".repeat(64)))
        val sha = cacheFile(photoBytes)
        assertNotNull(c.pathFor(sha))
    }

    @Test fun inUseIsEmptyForAMissingStoreAndThrowsForACorruptOne() {
        assertEquals(emptySet<String>(), cache().inUse())
        File(tmp.root, "linked-media.json").writeText("{ not json")
        assertThrows(Exception::class.java) { cache().inUse() }
    }

    @Test fun resolveSurvivesACorruptStore() = runTest {
        File(tmp.root, "linked-media.json").writeText("{ not json")
        assertEquals(listOf(sys, "content://j"), cache().resolve(alarm(), "content://j", null).soundCandidates)
    }

    @Test fun constructingDoesNoIo() {
        LinkedMediaCacheImpl(File(tmp.root, "does/not/exist"), storage, {}, sys)
        assertFalse(File(tmp.root, "does").exists())
    }

    // downloads

    @Test fun updateEnqueuesOnlyWhenSomethingIsMissing() {
        val c = cache()
        c.update("owner", emptyMap(), null)
        assertEquals(0, enqueued)
        cacheAll()
        fullRefs(c)
        assertEquals(0, enqueued)
        File(linked, sha256(ringBytes)).delete()
        fullRefs(c)
        assertEquals(1, enqueued)
    }

    @Test fun downloadMissingFetchesVerifiesAndRenames() = runTest {
        storage.blobs[sha256(photoBytes)] = photoBytes
        storage.blobs[sha256(ringBytes)] = ringBytes
        storage.blobs[sha256(defBytes)] = defBytes
        val c = cache().also(::fullRefs)
        assertTrue(c.downloadMissing("hid"))
        assertEquals(path(photoBytes), c.pathFor(sha256(photoBytes)))
        assertEquals(3, storage.downloads.size)
        assertTrue(storage.downloads.all { it.startsWith("hid/owner/") })
        assertEquals(emptyList<String>(), linked.list()!!.filter { it.endsWith(".tmp") })
        storage.downloads.clear()
        assertTrue(c.downloadMissing("hid"))
        assertTrue(storage.downloads.isEmpty())
    }

    @Test fun downloadMissingRejectsAHashMismatch() = runTest {
        storage.blobs[sha256(photoBytes)] = "tampered".toByteArray()
        val c = cache()
        c.update("owner", mapOf("oa" to AlarmMedia(ref(photoBytes), null)), null)
        assertFalse(c.downloadMissing("hid"))
        assertNull(c.pathFor(sha256(photoBytes)))
        assertEquals(emptyList<String>(), linked.list()!!.toList())
    }

    @Test fun oneFailureDoesNotStopTheOthers() = runTest {
        storage.blobs[sha256(photoBytes)] = photoBytes
        storage.blobs[sha256(defBytes)] = defBytes
        storage.failing += sha256(ringBytes)
        val c = cache().also(::fullRefs)
        assertFalse(c.downloadMissing("hid"))
        assertNotNull(c.pathFor(sha256(photoBytes)))
        assertNotNull(c.pathFor(sha256(defBytes)))
        assertNull(c.pathFor(sha256(ringBytes)))
    }

    @Test fun downloadMissingPicksUpRefsAddedDuringARun() = runTest {
        val late = "late".toByteArray()
        storage.blobs[sha256(photoBytes)] = photoBytes
        storage.blobs[sha256(late)] = late
        lateinit var c: LinkedMediaCacheImpl
        val racing = object : MediaStorage by storage {
            override suspend fun download(hid: String, ownerUid: String, sha: String, dest: File, maxBytes: Long) {
                storage.download(hid, ownerUid, sha, dest, maxBytes)
                if (sha == sha256(photoBytes)) {
                    c.update("owner", mapOf("oa" to AlarmMedia(ref(photoBytes), ref(late))), null)
                }
            }
        }
        c = LinkedMediaCacheImpl(tmp.root, racing, { enqueued++ }, sys)
        c.update("owner", mapOf("oa" to AlarmMedia(ref(photoBytes), null)), null)
        assertTrue(c.downloadMissing("hid"))
        assertNotNull(c.pathFor(sha256(late)))
    }
}
