package me.shawnsquire.tandem.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeLinkRepository
import me.shawnsquire.tandem.fakes.FakePartnerRepository
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.LinkMode
import me.shawnsquire.tandem.sync.Household
import me.shawnsquire.tandem.sync.PartnerState
import me.shawnsquire.tandem.sync.PublishedLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant

private class FakeMediaStorage : MediaStorage {
    data class Upload(val hid: String, val uid: String, val sha: String, val bytes: Int, val contentType: String)

    val uploads = mutableListOf<Upload>()
    val deletes = mutableListOf<Triple<String, String, String>>()
    var failUploads = false

    override suspend fun upload(hid: String, ownerUid: String, sha: String, file: File, contentType: String) {
        if (failUploads) throw IOException("offline")
        uploads += Upload(hid, ownerUid, sha, file.readBytes().size, contentType)
    }

    override suspend fun delete(hid: String, ownerUid: String, sha: String) {
        deletes += Triple(hid, ownerUid, sha)
    }

    override suspend fun download(hid: String, ownerUid: String, sha: String, dest: File, maxBytes: Long) = Unit
}

/** Sources by name; the bytes are a repeated marker so a big file costs no test memory until copied. */
private class FakeMediaSource : MediaSource {
    class Entry(val bytes: ByteArray, val info: SourceInfo)

    val entries = mutableMapOf<String, Entry>()
    val sizes = mutableMapOf<String, Long>()

    fun put(source: String, bytes: ByteArray, contentType: String? = null, title: String? = null) {
        entries[source] = Entry(bytes, SourceInfo(contentType, title))
    }

    override fun copy(source: String, kind: MediaKind, dest: File, maxBytes: Long): SourceInfo {
        val e = entries[source] ?: throw IOException("missing $source")
        val sized = sizes[source]
        if (sized != null) {
            // Like the real source: stops after maxBytes + 1.
            dest.outputStream().use { out -> out.write(ByteArray(minOf(sized, maxBytes + 1).toInt())) }
        } else {
            dest.writeBytes(e.bytes)
        }
        return e.info
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaPublisherImplTest {
    @get:Rule val tmp = TemporaryFolder()

    private val hh = Household("hid-1", "me", "partner")
    private val storage = FakeMediaStorage()
    private val source = FakeMediaSource()
    private val household = MutableStateFlow<Household?>(hh)
    private val alarms = FakeAlarmRepository(emptyList())
    private val partner = FakePartnerRepository(null)
    private val links = FakeLinkRepository()
    private val settings = FakeSettingsRepository()
    private val stateFile by lazy { File(tmp.newFolder("state"), "media.json") }
    private val tmpDir by lazy { tmp.newFolder("tmp") }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun alarm(id: String, ringtone: String? = null, wallpaper: String? = null) =
        Alarm(id, id, 6, 30, emptySet(), true, null, ringtone, wallpaper)

    private fun link(alarmId: String) = PartnerState(
        "P", emptyList(), null, Instant.EPOCH, links = listOf(PublishedLink(alarmId, LinkMode.RING)),
    )

    private fun TestScope.publisher(): MediaPublisherImpl = MediaPublisherImpl(
        alarms, partner, links, settings, household, storage, source,
        MediaPublishStore(stateFile), tmpDir, newScope(), StandardTestDispatcher(testScheduler),
    )

    // Not backgroundScope: advanceUntilIdle ignores its tasks.
    private val scopes = mutableListOf<Job>()

    private fun TestScope.newScope(): CoroutineScope {
        val job = Job().also(scopes::add)
        return CoroutineScope(StandardTestDispatcher(testScheduler) + job)
    }

    @After fun cancelScopes() = scopes.forEach { it.cancel() }

    private val photo = "photo".toByteArray()
    private val tone = "tone".toByteArray()

    @Test
    fun uploadsLinkedAndInvitedAlarmsButNotUnlinkedOnes() = runTest {
        source.put("/w/a.jpg", photo)
        source.put("file:///r/a.mp3", tone, "audio/mpeg", "Birds")
        source.put("/w/b.jpg", "other".toByteArray())
        source.put("/w/c.jpg", "third".toByteArray())
        alarms.upsert(alarm("a", "file:///r/a.mp3", "/w/a.jpg"))
        alarms.upsert(alarm("b", wallpaper = "/w/b.jpg"))
        alarms.upsert(alarm("c", wallpaper = "/w/c.jpg"))
        partner.set(link("a"))
        links.openInvites.value = setOf("b")
        val p = publisher()
        p.start()
        advanceUntilIdle()

        val published = p.published.first()!!
        assertEquals(setOf("a", "b"), published.alarms.keys)
        assertEquals(MediaRef(sha(photo), 5), published.alarms.getValue("a").wallpaper)
        assertEquals(MediaRef(sha(tone), 4, "Birds"), published.alarms.getValue("a").ringtone)
        assertEquals(3, storage.uploads.size)
        assertEquals("image/jpeg", storage.uploads.first { it.sha == sha(photo) }.contentType)
        assertEquals("audio/mpeg", storage.uploads.first { it.sha == sha(tone) }.contentType)
        assertTrue(storage.uploads.all { it.hid == "hid-1" && it.uid == "me" })
    }

    @Test
    fun hashIsStableAndKnown() = runTest {
        // SHA-256("abc")
        source.put("/w/a.jpg", "abc".toByteArray())
        alarms.upsert(alarm("a", wallpaper = "/w/a.jpg"))
        partner.set(link("a"))
        val p = publisher()
        p.start()
        advanceUntilIdle()
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            p.published.first()!!.alarms.getValue("a").wallpaper!!.sha,
        )
    }

    @Test
    fun alreadyUploadedHashIsSkipped() = runTest {
        source.put("/w/a.jpg", photo)
        source.put("/w/b.jpg", photo)
        alarms.upsert(alarm("a", wallpaper = "/w/a.jpg"))
        alarms.upsert(alarm("b", wallpaper = "/w/b.jpg"))
        partner.set(link("a"))
        links.openInvites.value = setOf("b")
        publisher().apply { start() }
        advanceUntilIdle()
        assertEquals(1, storage.uploads.size)

        // A fresh instance (process restart) reads the persisted set.
        publisher().apply { start() }
        advanceUntilIdle()
        assertEquals(1, storage.uploads.size)
    }

    @Test
    fun oversizedFileGetsNoRefAndExactCapUploads() = runTest {
        source.put("/w/big.jpg", photo)
        source.sizes["/w/big.jpg"] = 15_728_641
        source.put("/w/cap.jpg", photo)
        source.sizes["/w/cap.jpg"] = 15_728_640
        alarms.upsert(alarm("big", wallpaper = "/w/big.jpg"))
        alarms.upsert(alarm("cap", wallpaper = "/w/cap.jpg"))
        partner.set(PartnerState("P", emptyList(), null, null, links = listOf(PublishedLink("big", LinkMode.RING), PublishedLink("cap", LinkMode.RING))))
        val p = publisher()
        p.start()
        advanceUntilIdle()

        val published = p.published.first()!!
        assertNull(published.alarms["big"])
        assertEquals(15_728_640L, published.alarms.getValue("cap").wallpaper!!.size)
        assertEquals(listOf(15_728_640), storage.uploads.map { it.bytes })
    }

    @Test
    fun unpairedDoesNothing() = runTest {
        source.put("/w/a.jpg", photo)
        alarms.upsert(alarm("a", wallpaper = "/w/a.jpg"))
        partner.set(link("a"))
        household.value = null
        val p = publisher()
        p.start()
        advanceUntilIdle()
        assertTrue(storage.uploads.isEmpty())
        assertTrue(storage.deletes.isEmpty())
        assertNull(p.published.first())
    }

    @Test
    fun cleanupDeletesOnlyUnreferencedHashes() = runTest {
        source.put("/w/a.jpg", photo)
        source.put("/w/b.jpg", "other".toByteArray())
        alarms.upsert(alarm("a", wallpaper = "/w/a.jpg"))
        alarms.upsert(alarm("b", wallpaper = "/w/b.jpg"))
        partner.set(PartnerState("P", emptyList(), null, null, links = listOf(PublishedLink("a", LinkMode.RING), PublishedLink("b", LinkMode.RING))))
        val p = publisher()
        p.start()
        advanceUntilIdle()
        assertTrue(storage.deletes.isEmpty())

        partner.set(link("a"))
        advanceUntilIdle()

        assertEquals(listOf(Triple("hid-1", "me", sha("other".toByteArray()))), storage.deletes)
        assertEquals(setOf("a"), p.published.first()!!.alarms.keys)
    }

    @Test
    fun defaultWallpaperIsPublished() = runTest {
        source.put("/w/default.jpg", photo)
        settings.setDefaultWallpaperPath("/w/default.jpg")
        val p = publisher()
        p.start()
        advanceUntilIdle()
        assertEquals(MediaRef(sha(photo), 5), p.published.first()!!.defaultWallpaper)
        assertEquals("image/jpeg", storage.uploads.single().contentType)
    }

    @Test
    fun persistedRefsAreEmittedByAFreshInstanceWithoutStart() = runTest {
        source.put("/w/a.jpg", photo)
        alarms.upsert(alarm("a", wallpaper = "/w/a.jpg"))
        partner.set(link("a"))
        val first = publisher()
        first.start()
        advanceUntilIdle()
        val expected = first.published.first()
        assertNotNull(expected)

        assertEquals(expected, publisher().published.first())
    }

    @Test
    fun nothingEverPublishedEmitsNull() = runTest {
        assertNull(publisher().published.first())
    }

    @Test
    fun rePairWithNewHouseholdReuploads() = runTest {
        source.put("/w/a.jpg", photo)
        alarms.upsert(alarm("a", wallpaper = "/w/a.jpg"))
        partner.set(link("a"))
        publisher().apply { start() }
        advanceUntilIdle()
        assertEquals(listOf("hid-1"), storage.uploads.map { it.hid })

        household.value = null
        advanceUntilIdle()
        household.value = Household("hid-2", "me", "partner2")
        advanceUntilIdle()
        assertEquals(listOf("hid-1", "hid-2"), storage.uploads.map { it.hid })
    }

    @Test
    fun uploadFailureKeepsPreviousRefAndRetriesWithBackoff() = runTest {
        source.put("/w/a.jpg", photo)
        alarms.upsert(alarm("a", wallpaper = "/w/a.jpg"))
        partner.set(link("a"))
        val p = publisher()
        p.start()
        advanceUntilIdle()
        val old = sha(photo)
        assertEquals(old, p.published.first()!!.alarms.getValue("a").wallpaper!!.sha)

        // Change the file while Storage is down: the old ref stays, and the old blob is not deleted.
        val changed = "changed".toByteArray()
        source.put("/w/a.jpg", changed)
        storage.failUploads = true
        source.put("/w/a2.jpg", changed)
        alarms.upsert(alarm("a", wallpaper = "/w/a2.jpg"))
        runCurrent() // not advanceUntilIdle: the retry timer would spin forever while Storage is down
        assertEquals(old, p.published.first()!!.alarms.getValue("a").wallpaper!!.sha)
        assertTrue(storage.deletes.isEmpty())

        storage.failUploads = false
        advanceTimeBy(61_000)
        advanceUntilIdle()
        assertEquals(sha(changed), p.published.first()!!.alarms.getValue("a").wallpaper!!.sha)
        assertEquals(listOf(Triple("hid-1", "me", old)), storage.deletes)
    }

    @Test
    fun ringtoneContentTypeFallsBackToAudio() = runTest {
        source.put("file:///r/a", tone, null, null)
        source.put("file:///r/b", "b".toByteArray(), "application/octet-stream", "B")
        alarms.upsert(alarm("a", ringtone = "file:///r/a"))
        alarms.upsert(alarm("b", ringtone = "file:///r/b"))
        partner.set(PartnerState("P", emptyList(), null, null, links = listOf(PublishedLink("a", LinkMode.RING), PublishedLink("b", LinkMode.RING))))
        publisher().apply { start() }
        advanceUntilIdle()
        assertTrue(storage.uploads.all { it.contentType == "audio/mpeg" })
        assertEquals(2, storage.uploads.size)
    }

    @Test
    fun startIsIdempotent() = runTest {
        source.put("/w/a.jpg", photo)
        alarms.upsert(alarm("a", wallpaper = "/w/a.jpg"))
        partner.set(link("a"))
        val p = publisher()
        p.start()
        p.start()
        advanceUntilIdle()
        assertEquals(1, storage.uploads.size)
    }
}
