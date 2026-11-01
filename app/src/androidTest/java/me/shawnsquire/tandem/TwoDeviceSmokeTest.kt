package me.shawnsquire.tandem

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.time.DayOfWeek
import java.time.Duration as JavaDuration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.shawnsquire.tandem.alarm.InMemorySnoozeLedger
import me.shawnsquire.tandem.alarm.MirrorKind
import me.shawnsquire.tandem.alarm.RingMirrorPort
import me.shawnsquire.tandem.alarm.RingingControllerImpl
import me.shawnsquire.tandem.alarm.RingingNotifier
import me.shawnsquire.tandem.alarm.SlotClaim
import me.shawnsquire.tandem.alarm.SnoozeArmer
import me.shawnsquire.tandem.alarm.resolveRingMedia
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.AlarmRepositoryImpl
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.RingStateStoreImpl
import me.shawnsquire.tandem.data.TandemDatabase
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.media.AndroidMediaSource
import me.shawnsquire.tandem.media.FirebaseMediaStorage
import me.shawnsquire.tandem.media.LinkedMediaCacheImpl
import me.shawnsquire.tandem.media.MediaPublishStore
import me.shawnsquire.tandem.media.MediaPublisherImpl
import me.shawnsquire.tandem.media.sha256Hex
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
import me.shawnsquire.tandem.schedule.RingMember
import me.shawnsquire.tandem.schedule.RingOrigin
import me.shawnsquire.tandem.schedule.RingState
import me.shawnsquire.tandem.schedule.nextOccurrence
import me.shawnsquire.tandem.sync.AuthManager
import me.shawnsquire.tandem.sync.CommandApplierImpl
import me.shawnsquire.tandem.sync.CommandNotifier
import me.shawnsquire.tandem.sync.CommandSender
import me.shawnsquire.tandem.sync.CommandSenderImpl
import me.shawnsquire.tandem.sync.FcmTokenStore
import me.shawnsquire.tandem.sync.FirestoreCommandRemote
import me.shawnsquire.tandem.sync.FirestoreDeviceDocWriter
import me.shawnsquire.tandem.sync.Household
import me.shawnsquire.tandem.sync.HouseholdCheck
import me.shawnsquire.tandem.sync.HouseholdSource
import me.shawnsquire.tandem.sync.InviteLedger
import me.shawnsquire.tandem.sync.LinkRemovals
import me.shawnsquire.tandem.sync.LinkRepositoryImpl
import me.shawnsquire.tandem.sync.LinkResult
import me.shawnsquire.tandem.sync.LinkSyncImpl
import me.shawnsquire.tandem.sync.LiveZoneClock
import me.shawnsquire.tandem.sync.PairingRepository
import me.shawnsquire.tandem.sync.PairingRepositoryImpl
import me.shawnsquire.tandem.sync.PairingStatus
import me.shawnsquire.tandem.sync.PartnerAlarm
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.sync.PartnerRepositoryImpl
import me.shawnsquire.tandem.sync.PartnerState
import me.shawnsquire.tandem.sync.PreFireCheckImpl
import me.shawnsquire.tandem.sync.ProcessedCommands
import me.shawnsquire.tandem.sync.RingMirrorImpl
import me.shawnsquire.tandem.sync.StatePublisherImpl
import me.shawnsquire.tandem.sync.await
import me.shawnsquire.tandem.sync.createSyncDataStore
import me.shawnsquire.tandem.sync.householdOnServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two installs paired through the Firebase emulators (auth 9099, firestore 8080, functions 5001,
 * storage 9199 on the host), driven through the repositories, the applier and the ringing
 * controller, not the UI.
 *
 * Both identities live in this one instrumentation process as two named [FirebaseApp]s, each with its
 * own anonymous user, Room, DataStores, files and wiring copied from [AppContainer]. The default app
 * belongs to [TandemApp]'s own startup, which also runs here, and is left alone. The schedulers are
 * fakes so nothing arms a real alarm. Rings go through the real [RingingControllerImpl] (the slot
 * claim the engine makes on a fire, snooze and dismiss), with no sound, notifications or service,
 * and its LOCAL resolutions reach the real [RingMirrorImpl].
 *
 * Commands reach a phone only through the pre-fire pull ([PreFireCheckImpl.pullAndApply]); FCM
 * doesn't deliver in the emulator and neither phone has a token, so `onCommandCreated` sends nothing.
 */
@RunWith(AndroidJUnit4::class)
class TwoDeviceSmokeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString().take(8)
    private val devices = mutableListOf<SmokeDevice>()
    private lateinit var a: SmokeDevice
    private lateinit var b: SmokeDevice

    @Before
    fun setUp() {
        // API 37 blocks the emulator host (a private address) without it; the debug manifest declares it.
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        a = SmokeDevice(context, "smoke-a-$runId", "Alice").also(devices::add)
        b = SmokeDevice(context, "smoke-b-$runId", "Bob").also(devices::add)
    }

    @After
    fun tearDown() {
        devices.forEach(SmokeDevice::close)
    }

    @Test
    fun pairCommandAndUnpairAcrossTwoInstalls() = runBlocking {
        // 1. Pair: A creates a code, B joins it. Both see Paired, in the same household.
        pair()

        // 2. A creates a 06:30 alarm (repeating, so a skip sets skippedOccurrenceAt instead of turning
        // it off). B's PartnerRepository shows it with its nextOccurrenceAt. A second alarm is for step 4.
        val wake = repeatingAlarm("wake-$runId", "Wake", 6, 30)
        val gym = repeatingAlarm("gym-$runId", "Gym", 7, 15)
        a.alarms.upsert(wake)
        a.alarms.upsert(gym)
        assertTrue("A publishes its device doc", a.statePublisher.publishNow())
        val seen = within(ROUND_TRIP, "B sees A's alarms") {
            b.partner { p -> p.alarm(wake.id)?.nextOccurrenceAt != null && p.alarm(gym.id)?.nextOccurrenceAt != null }
        }
        val seenWake = seen.alarm(wake.id)!!
        assertEquals("Alice", seen.displayName)
        assertEquals(6, seenWake.hour)
        assertEquals(30, seenWake.minute)
        assertTrue(seenWake.enabled)
        assertNull(seenWake.skippedOccurrenceAt)
        assertEquals(nextOccurrence(wake, Instant.now(), LiveZoneClock.zone), seenWake.nextOccurrenceAt)

        // 3. B skips the occurrence it saw. A's pre-fire pull applies it, B's flow ends APPLIED and
        // A's republished doc carries the skip.
        val occurrence = seenWake.nextOccurrenceAt!!
        val skipStatus = b.sendAndPull(a, CommandType.SKIP_NEXT, wake.id, occurrence, null)
        assertEquals(CommandStatus.APPLIED to null, skipStatus)
        assertEquals(occurrence, a.alarms.get(wake.id)?.skippedOccurrenceAt)
        assertTrue("applier reschedules after a skip", a.scheduler.rescheduleCount >= 1)
        assertEquals(listOf(CommandType.SKIP_NEXT), a.notified.map { it.type })
        val skippedWake = within(ROUND_TRIP, "B sees the skip in A's republished doc") {
            b.partner { p -> p.alarm(wake.id)?.skippedOccurrenceAt == occurrence }
        }.alarm(wake.id)!!
        assertTrue("next occurrence moves past the skipped one", skippedWake.nextOccurrenceAt!! > occurrence)

        // 4. A SKIP_NEXT pinned to the wrong occurrence (a day after the one A would ring) is rejected.
        val staleOccurrence = seen.alarm(gym.id)!!.nextOccurrenceAt!!.plus(JavaDuration.ofDays(1))
        val staleStatus = b.sendAndPull(a, CommandType.SKIP_NEXT, gym.id, staleOccurrence, null)
        assertEquals(CommandStatus.REJECTED to "already rang or changed", staleStatus)
        assertNull(a.alarms.get(gym.id)?.skippedOccurrenceAt)
        assertEquals(1, a.notified.size)

        // 5. A is ringing. A STOP_RINGING pinned to another ring is rejected; one pinned to the ringId
        // B sees in A's doc dismisses the ring.
        val ringId = "ring-$runId"
        a.ringState.set(RingState(wake.id, ringId, Instant.now(), snoozedUntil = null))
        assertTrue("A publishes its ring", a.statePublisher.publishNow())
        val seenRing = within(ROUND_TRIP, "B sees A ringing") { b.partner { p -> p.ringing?.ringId == ringId } }.ringing!!
        val wrongRing = b.sendAndPull(a, CommandType.STOP_RINGING, wake.id, null, "ring-stale-$runId")
        assertEquals(CommandStatus.REJECTED to "not ringing", wrongRing)
        assertEquals("A still rings", ringId, a.ringState.state.first()?.ringId)
        val stopStatus = b.sendAndPull(a, CommandType.STOP_RINGING, seenRing.alarmId, null, seenRing.ringId)
        assertEquals(CommandStatus.APPLIED to null, stopStatus)
        assertNull("A's ring is dismissed", a.ringState.state.first())

        // 6. A unpairs. Both report Unpaired.
        within(CALLABLE, "A unpairs") { a.pairingRepository.unpair() }
        within(ROUND_TRIP, "A sees Unpaired") { a.pairingRepository.status.first { it == PairingStatus.Unpaired } }
        within(ROUND_TRIP, "B sees Unpaired") { b.pairingRepository.status.first { it == PairingStatus.Unpaired } }
        Unit
    }

    /**
     * B joins A's alarm and both phones ring X. B snoozes: the mirrored SNOOZE{X, T} snoozes A to the
     * same T. A dismisses: the mirrored STOP_RINGING{X} ends B's ring, and B sends nothing back (I4).
     */
    @Test
    fun joinedAlarmSnoozesAndDismissesOnBothPhones() = runBlocking {
        val hh = pair()
        val wake = upcomingAlarm("wake-$runId", "Wake")
        share(wake)
        val row = joinOnB(wake)
        val x = sharedOccurrence(wake, row)

        // Both fire X (the slot claim the engine makes). B publishes its ring under A's alarm id.
        val aRing = "ring-a-$runId"
        assertEquals(SlotClaim.Claimed, a.ringing.claimFire(RingState(wake.id, aRing, Instant.now(), null, x)))
        assertEquals(SlotClaim.Claimed, b.ringing.claimFire(RingState(row.id, "ring-b-$runId", Instant.now(), null, x)))
        within(ROUND_TRIP, "B sees A ringing X") { b.partner { it.ringing?.alarmId == wake.id && it.ringing?.occurrenceAt == x } }
        within(ROUND_TRIP, "A sees B ringing X under A's alarm id") {
            a.partner { it.ringing?.alarmId == wake.id && it.ringing?.occurrenceAt == x }
        }

        // B snoozes locally. Its mirror sends SNOOZE{wake, X, T}; A applies it, REMOTE, to the same T.
        b.ringing.snooze(RingOrigin.LOCAL)
        val t = checkNotNull(b.ringState.state.first()?.snoozedUntil)
        val snooze = b.nthSent(1)
        assertEquals(CommandType.SNOOZE, snooze.type)
        assertEquals(wake.id, snooze.alarmId)
        assertEquals(x, snooze.occurrenceAt)
        assertEquals(CommandStatus.APPLIED to null, b.deliver(snooze, a))
        val aSnoozed = a.ringState.state.first()
        assertEquals(aRing, aSnoozed?.ringId)
        assertEquals("both rings resume at the same T", t, aSnoozed?.snoozedUntil)
        assertEquals("A's REMOTE snooze mirrors nothing", emptyList<MirrorKind>(), a.mirror.calls.value)

        // A dismisses its snoozed ring locally. Its mirror sends STOP_RINGING{wake, X}; B applies it.
        a.ringing.dismiss(aRing, RingOrigin.LOCAL)
        assertNull(a.ringState.state.first())
        val stop = a.nthSent(1)
        assertEquals(CommandType.STOP_RINGING, stop.type)
        assertEquals(wake.id, stop.alarmId)
        assertEquals(x, stop.occurrenceAt)
        assertNull(stop.ringId)
        assertEquals(CommandStatus.APPLIED to null, a.deliver(stop, b))
        assertNull("B's ring has ended", b.ringState.state.first())

        // I4: B applied the stop as REMOTE, so its mirror was not called and it sent nothing. RingMirror
        // sends from a queue, so give a stray echo time to land before reading the server.
        delay(QUIET)
        assertEquals("B mirrored only its own snooze", listOf(MirrorKind.SNOOZE), b.mirror.calls.value)
        assertEquals("B sent only the snooze", listOf(CommandType.SNOOZE), b.commandSender.sent.value.map { it.type })
        within(ROUND_TRIP, "B's writes reach the server") { b.firestore.waitForPendingWrites().await() }
        val fromB = within(ROUND_TRIP, "read B's commands") {
            b.firestore.collection("households").document(hh.id).collection("commands")
                .whereEqualTo("from", hh.bUid).get(Source.SERVER).await()
        }
        assertEquals("no command from B after the dismiss", listOf("SNOOZE"), fromB.documents.map { it.getString("type") })
    }

    /** A invites B to its alarm; B's pull posts the invite, B accepts, and A's invite resolves APPLIED. */
    @Test
    fun inviteThenAccept() = runBlocking {
        val hh = pair()
        val gym = upcomingAlarm("gym-$runId", "Gym")
        share(gym)

        val status = a.linkRepository.invite(gym.id)
        within(ROUND_TRIP, "A counts the invite as open") { a.linkRepository.openInvites.first { gym.id in it } }
        within(ROUND_TRIP, "INVITE_LINK reaches the server") { a.firestore.waitForPendingWrites().await() }
        // Pulled again until it lands: one 3 s pull can miss on a cold emulator. Re-delivery doesn't re-notify.
        val invite = within(ROUND_TRIP, "B's pull posts the invite") {
            var posted: List<Command>? = null
            while (posted == null) {
                b.preFireCheck.pullAndApply(3.seconds)
                posted = withTimeoutOrNull(3.seconds) { b.invites.first { it.isNotEmpty() } }
            }
            posted
        }.single()
        assertEquals(CommandType.INVITE_LINK, invite.type)
        assertEquals(gym.id, invite.alarmId)
        assertEquals(hh.aUid, invite.from)

        val accepted = b.linkRepository.acceptInvite(invite.id, invite.alarmId)
        assertTrue("B accepts: $accepted", accepted is LinkResult.Joined)
        assertEquals(CommandStatus.APPLIED to null, within(ROUND_TRIP, "A's invite is answered") { status.toList().last() })
        val row = checkNotNull(b.alarms.get((accepted as LinkResult.Joined).alarmId))
        assertEquals(gym.id, row.link?.ownerAlarmId)
        assertEquals(hh.aUid, row.link?.ownerUid)
        assertEquals(nextOccurrence(gym, Instant.now(), LiveZoneClock.zone), nextOccurrence(row, Instant.now(), LiveZoneClock.zone))

        assertTrue("B publishes its links", b.statePublisher.publishNow())
        within(ROUND_TRIP, "A sees B joined") { a.partner { p -> p.links.any { it.ownerAlarmId == gym.id } } }
        within(ROUND_TRIP, "A's invite is no longer open") { a.linkRepository.openInvites.first { it.isEmpty() } }
        Unit
    }

    /**
     * A skips X "just me". B's copy still rings X, and when B dismisses it the mirrored STOP_RINGING is
     * rejected on A as "skipped here".
     */
    @Test
    fun ownerSoloSkipLeavesJoinerRinging() = runBlocking {
        pair()
        val wake = upcomingAlarm("wake-$runId", "Wake")
        share(wake)
        val row = joinOnB(wake)
        val x = sharedOccurrence(wake, row)

        a.alarms.setSoloSkip(wake.id, x)
        assertTrue("A publishes its solo skip", a.statePublisher.publishNow())
        within(ROUND_TRIP, "B sees A's solo skip") { b.partner { it.alarm(wake.id)?.soloSkippedOccurrenceAt == x } }
        // B's pre-fire check for its linked row reads A's doc from the server and applies it.
        // Retried: the pull and the owner-doc read share one 3 s cap, which a cold emulator can miss.
        within(ROUND_TRIP, "B's pre-fire check reads A's doc") {
            while (!b.preFireCheck.pullAndApply(3.seconds, wake.id)) delay(500.milliseconds)
        }
        val synced = checkNotNull(b.alarms.get(row.id))
        assertNull("a solo skip is never copied", synced.link?.ownerSkippedOccurrenceAt)
        assertEquals("B still rings X", x, nextOccurrence(synced, Instant.now(), LiveZoneClock.zone))
        assertNotEquals("A doesn't", x, nextOccurrence(a.alarms.get(wake.id)!!, Instant.now(), LiveZoneClock.zone))

        assertEquals(SlotClaim.Claimed, b.ringing.claimFire(RingState(row.id, "ring-b-$runId", Instant.now(), null, x)))
        b.ringing.dismiss("ring-b-$runId", RingOrigin.LOCAL)
        val stop = b.nthSent(1)
        assertEquals(CommandType.STOP_RINGING, stop.type)
        assertEquals(wake.id, stop.alarmId)
        assertEquals(x, stop.occurrenceAt)
        assertEquals(CommandStatus.REJECTED to "skipped here", b.deliver(stop, a))
        assertNull("A is not ringing", a.ringState.state.first())
        assertEquals("A's solo skip stands", x, a.alarms.get(wake.id)?.soloSkippedOccurrenceAt)
    }

    /** A turns its joined alarm off: B's copy goes off too and B is told. */
    @Test
    fun ownerTurnOffTurnsJoinerOff() = runBlocking {
        pair()
        val wake = upcomingAlarm("wake-$runId", "Wake")
        share(wake)
        val row = joinOnB(wake)
        assertTrue(row.enabled)

        a.alarms.setEnabled(wake.id, false)
        assertTrue("A publishes the turn-off", a.statePublisher.publishNow())
        within(ROUND_TRIP, "B's copy is off") {
            b.alarms.linkedAlarms.first { rows -> rows.any { it.id == row.id && !it.enabled } }
        }
        val told = within(ROUND_TRIP, "B is told A turned it off") { b.turnedOff.first { it.isNotEmpty() } }
        assertEquals(listOf(row.id), told.map { it.id })
        assertNull("B's copy no longer rings", nextOccurrence(b.alarms.get(row.id)!!, Instant.now(), LiveZoneClock.zone))
    }

    /**
     * A's photo and sound for a joined alarm are uploaded to the Storage emulator, downloaded into B's
     * `linked/` cache, and resolved for B's ring as those local files.
     */
    @Test
    fun ownerMediaIsShippedToTheJoiner() = runBlocking {
        val hh = pair()
        val photoBytes = Random.nextBytes(4096)
        val toneBytes = Random.nextBytes(8192)
        val photo = a.writeFile("wallpaper.jpg", photoBytes)
        val tone = a.writeFile("tone.mp3", toneBytes)
        val wake = upcomingAlarm("wake-$runId", "Wake")
            .copy(wallpaperPath = photo.absolutePath, ringtoneUri = Uri.fromFile(tone).toString())
        share(wake)
        // Media is uploaded only for alarms the partner has joined (or been invited to).
        val row = joinOnB(wake)

        val photoSha = sha256Hex(photo)
        val toneSha = sha256Hex(tone)
        val published = within(MEDIA, "A uploads the photo and sound") {
            a.mediaPublisher.published.first { p ->
                p?.alarms?.get(wake.id)?.let { it.wallpaper != null && it.ringtone != null } == true
            }
        }!!.alarms.getValue(wake.id)
        assertEquals(photoSha, published.wallpaper?.sha)
        assertEquals(toneSha, published.ringtone?.sha)

        assertTrue("A publishes its media refs", a.statePublisher.publishNow())
        within(ROUND_TRIP, "B sees A's media refs") {
            b.partner { p -> p.alarm(wake.id)?.media?.let { it.wallpaper?.sha == photoSha && it.ringtone?.sha == toneSha } == true }
        }
        eventually("B's LinkSync hands the refs to its cache") { b.linkedMediaCache.inUse() == setOf(photoSha, toneSha) }
        assertTrue("the cache asks for a download", b.downloadRequests.get() > 0)
        // What LinkedMediaDownloadWorker runs; called directly so it uses B's cache, not the app's.
        within(MEDIA, "B downloads A's media") {
            while (!b.linkedMediaCache.downloadMissing(hh.id).allPresent) delay(1.seconds)
        }
        val cachedPhoto = checkNotNull(b.linkedMediaCache.pathFor(photoSha))
        val cachedTone = checkNotNull(b.linkedMediaCache.pathFor(toneSha))
        assertTrue(cachedPhoto.startsWith(b.filesDir.absolutePath))
        assertArrayEquals(photoBytes, File(cachedPhoto).readBytes())
        assertArrayEquals(toneBytes, File(cachedTone).readBytes())

        // A generous bound: the default falls back to the row (no media) if the disk is slow.
        val media = resolveRingMedia(b.alarms.get(row.id), b.linkedMediaCache, null, null, timeout = 5.seconds)
        assertEquals("Wake", media.label)
        assertEquals(Uri.fromFile(File(cachedTone)).toString(), media.soundCandidates.first())
        assertEquals(cachedPhoto, media.wallpaperPaths.first())
    }

    private data class Paired(val id: String, val aUid: String, val bUid: String)

    /** Signs both in, starts their sync as TandemApp does, and pairs them through a code. */
    private suspend fun pair(): Paired {
        val aUid = within(CALLABLE, "A signs in") { a.authManager.ensureSignedIn() }
        val bUid = within(CALLABLE, "B signs in") { b.authManager.ensureSignedIn() }
        assertNotEquals(aUid, bUid)
        a.startSync()
        b.startSync()

        val code = within(CALLABLE, "A creates a pair code") { a.pairingRepository.createCode() }
        within(CALLABLE, "B joins with $code") { b.pairingRepository.join(code) }
        val aStatus = within(ROUND_TRIP, "A sees Paired") { a.pairingRepository.status.first { it is PairingStatus.Paired } }
        val bStatus = within(ROUND_TRIP, "B sees Paired") { b.pairingRepository.status.first { it is PairingStatus.Paired } }
        assertEquals((aStatus as PairingStatus.Paired).householdId, (bStatus as PairingStatus.Paired).householdId)
        // CommandSenderImpl reads the household synchronously, so wait for the state flows too.
        val aHousehold = within(ROUND_TRIP, "A's household") { a.household.first { it != null } }!!
        val bHousehold = within(ROUND_TRIP, "B's household") { b.household.first { it != null } }!!
        assertEquals(Household(aStatus.householdId, aUid, bUid), aHousehold)
        assertEquals(Household(aStatus.householdId, bUid, aUid), bHousehold)
        return Paired(aStatus.householdId, aUid, bUid)
    }

    /** A creates [alarm] and publishes; returns it as B sees it. */
    private suspend fun share(alarm: Alarm): PartnerAlarm {
        a.alarms.upsert(alarm)
        assertTrue("A publishes ${alarm.id}", a.statePublisher.publishNow())
        return within(ROUND_TRIP, "B sees ${alarm.id}") { b.partner { it.alarm(alarm.id)?.nextOccurrenceAt != null } }
            .alarm(alarm.id)!!
    }

    /** B joins A's [alarm] and publishes its links; returns B's linked row once A sees the link. */
    private suspend fun joinOnB(alarm: Alarm): Alarm {
        val joined = within(ROUND_TRIP, "B joins ${alarm.id}") { b.linkRepository.join(alarm.id) }
        assertTrue("B joins ${alarm.id}: $joined", joined is LinkResult.Joined)
        assertTrue("B publishes its links", b.statePublisher.publishNow())
        // A's owner-side mirror only sends for alarms in B's published links.
        within(ROUND_TRIP, "A sees B joined ${alarm.id}") { a.partner { p -> p.links.any { it.ownerAlarmId == alarm.id } } }
        return b.alarms.get((joined as LinkResult.Joined).alarmId)!!
    }

    /** X: the next occurrence of A's [alarm], which B's linked [row] must ring too. */
    private fun sharedOccurrence(alarm: Alarm, row: Alarm): Instant {
        val now = Instant.now()
        val x = checkNotNull(nextOccurrence(row, now, LiveZoneClock.zone))
        assertEquals("B's copy rings at A's next occurrence", nextOccurrence(alarm, now, LiveZoneClock.zone), x)
        return x
    }

    /**
     * Sends a command as this device, waits until the server has it (the create isn't awaited and the
     * pull reads from the server), runs [owner]'s pre-fire pull, and returns the command's final status.
     */
    private suspend fun SmokeDevice.sendAndPull(
        owner: SmokeDevice,
        type: CommandType,
        alarmId: String,
        occurrenceAt: Instant?,
        ringId: String?,
    ): Pair<CommandStatus, String?> {
        val updates = commandSender.send(type, alarmId, occurrenceAt, ringId)
        within(ROUND_TRIP, "$type reaches the server") { firestore.waitForPendingWrites().await() }
        assertTrue("$type: A's pre-fire pull succeeds", owner.preFireCheck.pullAndApply(3.seconds))
        // The flow ends at the first non-PENDING status.
        return within(ROUND_TRIP, "$type is resolved") { updates.toList() }.last()
    }

    /** The [n]th command this device has sent (1-based), waiting for its mirror to send it. */
    private suspend fun SmokeDevice.nthSent(n: Int): RecordingCommandSender.Sent =
        within(ROUND_TRIP, "$name sends command $n") { commandSender.sent.first { it.size >= n } }[n - 1]

    /** Waits until [sent] is on the server, then pulls on [receiver] until the command is resolved. */
    private suspend fun SmokeDevice.deliver(sent: RecordingCommandSender.Sent, receiver: SmokeDevice): Pair<CommandStatus, String?> {
        within(ROUND_TRIP, "${sent.type} reaches the server") { firestore.waitForPendingWrites().await() }
        return within(ROUND_TRIP, "${sent.type} is resolved") {
            var status: Pair<CommandStatus, String?>? = null
            while (status == null) {
                receiver.preFireCheck.pullAndApply(3.seconds)
                status = withTimeoutOrNull(3.seconds) { sent.status.first { it.first != CommandStatus.PENDING } }
            }
            status
        }
    }

    private suspend fun SmokeDevice.partner(predicate: (PartnerState) -> Boolean): PartnerState =
        partnerRepository.partner.first { it != null && predicate(it) }!!

    private fun PartnerState.alarm(id: String): PartnerAlarm? = alarms.firstOrNull { it.id == id }

    private fun repeatingAlarm(id: String, label: String, hour: Int, minute: Int) = Alarm(
        id = id, label = label, hour = hour, minute = minute, days = DayOfWeek.entries.toSet(),
        enabled = true, skippedOccurrenceAt = null, ringtoneUri = null, wallpaperPath = null,
    )

    /** A daily alarm hours away, so no occurrence passes (or comes within FIRE_GRACE) during the test. */
    private fun upcomingAlarm(id: String, label: String): Alarm {
        val at = ZonedDateTime.now(LiveZoneClock.zone).plusHours(3)
        return repeatingAlarm(id, label, at.hour, at.minute)
    }

    private suspend fun <T> within(timeout: Duration, what: String, block: suspend () -> T): T = try {
        withTimeout(timeout) { block() }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("timed out after $timeout: $what", e)
    }

    private suspend fun eventually(what: String, timeout: Duration = ROUND_TRIP, check: suspend () -> Boolean) =
        within(timeout, what) { while (!check()) delay(200.milliseconds) }

    private companion object {
        // The first callable can wait on a cold functions-emulator worker.
        val CALLABLE = 60.seconds
        val ROUND_TRIP = 30.seconds
        // A failed media pass retries after 60 s.
        val MEDIA = 2.minutes
        // How long an echo would have to show up.
        val QUIET = 3.seconds
    }
}

/** Records every command sent, with its status flow (cold: collecting it again only adds a listener). */
private class RecordingCommandSender(private val delegate: CommandSender) : CommandSender {
    class Sent(
        val type: CommandType,
        val alarmId: String,
        val occurrenceAt: Instant?,
        val ringId: String?,
        val snoozedUntil: Instant?,
        val status: Flow<Pair<CommandStatus, String?>>,
    )

    val sent = MutableStateFlow<List<Sent>>(emptyList())

    override fun send(
        type: CommandType,
        alarmId: String,
        occurrenceAt: Instant?,
        ringId: String?,
        snoozedUntil: Instant?,
        onCreated: (String) -> Unit,
    ): Flow<Pair<CommandStatus, String?>> {
        val status = delegate.send(type, alarmId, occurrenceAt, ringId, snoozedUntil, onCreated)
        sent.update { it + Sent(type, alarmId, occurrenceAt, ringId, snoozedUntil, status) }
        return status
    }
}

/** Records each LOCAL resolution the controller hands the mirror, then passes it on. */
private class RecordingMirrorPort(private val delegate: RingMirrorPort) : RingMirrorPort {
    val calls = MutableStateFlow<List<MirrorKind>>(emptyList())

    override fun onLocal(kind: MirrorKind, members: List<RingMember>, snoozedUntil: Instant?) {
        calls.update { it + kind }
        delegate.onLocal(kind, members, snoozedUntil)
    }
}

/** No snooze PendingIntents: the test resolves rings itself. */
private object NoSnoozeAlarms : SnoozeArmer {
    override fun armSnooze(alarmId: String, ringId: String, at: Instant) = Unit
    override fun cancelSnooze(ringId: String) = Unit
}

/** No notifications from either device's ringing controller. */
private object SilentRingingNotifier : RingingNotifier {
    override fun showRinging(alarm: Alarm?, ringId: String, labels: List<String>) = Unit
    override fun showNotifyRinging(alarm: Alarm?, ringId: String, labels: List<String>, alertAgain: Boolean) = true
    override fun updateRingingLabel(alarm: Alarm?, ringId: String, labels: List<String>, notifySurface: Boolean) = true
    override fun showSnoozed(ringId: String, until: Instant, labels: List<String>) = Unit
    override fun cancelSnoozed(ringId: String) = Unit
}

/** One install: a named FirebaseApp on the emulators plus the sync and ringing wiring of [AppContainer]. */
private class SmokeDevice(context: Context, val name: String, displayName: String) {
    private val app: FirebaseApp = FirebaseApp.initializeApp(context, OPTIONS, name)
    private val dir = File(context.cacheDir, "smoke/$name").apply { mkdirs() }

    /** This install's filesDir: the linked media cache lives under it, apart from the app's own. */
    val filesDir = File(dir, "files")

    // Each instance is new (unique app name), so useEmulator runs once and before first use.
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(app).apply {
        useEmulator(HOST, 9099)
        signOut()
    }
    val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(app).apply {
        // Nothing to keep across runs, so no on-disk cache per throwaway app name.
        firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
        useEmulator(HOST, 8080)
    }
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(app).apply { useEmulator(HOST, 5001) }
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(app).apply { useEmulator(HOST, 9199) }

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "$name uncaught", e) },
    )
    private val database = Room.inMemoryDatabaseBuilder(context, TandemDatabase::class.java).build()
    private val syncStore = createSyncDataStore(File(dir, "sync.preferences_pb"), scope)

    val alarms: AlarmRepository = AlarmRepositoryImpl(database.alarmDao(), LiveZoneClock)
    val ringState: RingStateStore = RingStateStoreImpl(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "ring_state.preferences_pb") }),
    )
    private val settings = FakeSettingsRepository()
    val scheduler = FakeAlarmScheduler()
    val notified: MutableList<Command> = Collections.synchronizedList(mutableListOf())
    val invites = MutableStateFlow<List<Command>>(emptyList())
    val turnedOff = MutableStateFlow<List<Alarm>>(emptyList())
    private val notifier = object : CommandNotifier {
        override fun notifyApplied(cmd: Command, alarm: Alarm?, partnerName: String, turnedOff: Boolean) {
            notified += cmd
        }
        override fun notifyInvite(cmd: Command, alarm: PartnerAlarm?, partnerName: String) = invites.update { it + cmd }
        override fun notifyOwnerDeleted(alarm: Alarm, partnerName: String) = Unit
        override fun notifyOwnerTurnedOff(alarm: Alarm, partnerName: String) = turnedOff.update { it + alarm }
        override fun notifyRejected(alarm: Alarm, type: CommandType, reason: String?, partnerName: String) = Unit
    }

    // Same construction order as AppContainer, except that the mirror comes before the controller,
    // which the engine builds earlier and attaches the mirror to later.
    val authManager = AuthManager(auth, syncStore, defaultName = displayName)
    private val householdFlow: Flow<Household?> = HouseholdSource(authManager.uid, firestore, scope).household
    val household: StateFlow<Household?> = householdFlow.stateIn(scope, SharingStarted.Eagerly, null)
    private val fcmTokenStore = FcmTokenStore(syncStore)
    private val commandRemote = FirestoreCommandRemote(firestore)
    val partnerRepository: PartnerRepository = PartnerRepositoryImpl(householdFlow, firestore)
    val pairingRepository: PairingRepository =
        PairingRepositoryImpl(authManager, householdFlow, partnerRepository.partner, functions)
    val commandSender = RecordingCommandSender(CommandSenderImpl(commandRemote, household, LiveZoneClock))
    private val linkRemovals = LinkRemovals(syncStore)
    val mirror = RecordingMirrorPort(
        RingMirrorImpl(alarms, partnerRepository.partner, household, commandSender, linkRemovals, scope),
    )
    val ringing = RingingControllerImpl(
        alarms, ringState, settings, scheduler, NoSnoozeAlarms, SilentRingingNotifier, InMemorySnoozeLedger(), mirror,
    )
    val downloadRequests = AtomicInteger()
    // The test runs downloadMissing itself; LinkedMediaDownloadWorker would use the app's container.
    val linkedMediaCache = LinkedMediaCacheImpl(
        filesDir, FirebaseMediaStorage({ storage }), enqueueDownload = { downloadRequests.incrementAndGet() },
    )
    val linkRepository = LinkRepositoryImpl(
        alarms, partnerRepository.partner, householdFlow, commandSender, commandRemote,
        ProcessedCommands(syncStore, LiveZoneClock), InviteLedger(syncStore, LiveZoneClock), linkRemovals,
        { id, reason -> linkSync.requestRemoval(id, reason) },
        { id -> linkSync.cancelRemoval(id) },
        { row, cmd -> linkSync.noteApplied(row, cmd) },
        { cmd -> linkSync.appliedSettled(cmd) },
        scheduler, notifier, LiveZoneClock, scope,
    )
    val linkSync = LinkSyncImpl(
        partnerRepository.partner, householdFlow, alarms, ringing, ringState.state,
        scheduler, linkedMediaCache, notifier, linkRemovals, LiveZoneClock, scope,
        confirmHousehold = {
            val uid = authManager.uid.value
            if (uid == null) HouseholdCheck.Unknown
            else withTimeoutOrNull(15.seconds) { householdOnServer(firestore, uid) } ?: HouseholdCheck.Unknown
        },
    )
    val mediaPublisher = MediaPublisherImpl(
        alarms, partnerRepository, linkRepository, settings, householdFlow, FirebaseMediaStorage({ storage }),
        AndroidMediaSource(context), MediaPublishStore(File(dir, "media-publisher.json")), File(dir, "tmp"), scope,
    )
    private val commandApplier = CommandApplierImpl(
        alarms, ringState, scheduler, ringing, commandRemote, householdFlow,
        partnerRepository.partner.map { it?.displayName }.stateIn(scope, SharingStarted.Eagerly, null),
        partnerRepository.partner,
        ProcessedCommands(syncStore, LiveZoneClock), InviteLedger(syncStore, LiveZoneClock),
        linkRemovals, notifier, LiveZoneClock, scope,
    )
    val preFireCheck = PreFireCheckImpl(
        householdFlow, commandRemote, commandApplier::applyPending, partnerRepository, linkSync,
        onSuccess = { scope.launch { statePublisher.publishNow() } },
    )
    val statePublisher = StatePublisherImpl(
        alarms, ringState, householdFlow, authManager.displayName, fcmTokenStore.token,
        linkRepository.links, mediaPublisher.published, FirestoreDeviceDocWriter(firestore), LiveZoneClock, scope,
    )

    /** As TandemApp does after sign-in. */
    fun startSync() {
        statePublisher.start()
        mediaPublisher.start()
        linkSync.start()
        linkRepository.start()
    }

    fun writeFile(fileName: String, bytes: ByteArray): File = File(dir, fileName).apply { writeBytes(bytes) }

    fun close() {
        scope.cancel()
        runCatching { database.close() }
        // Stops this app's listeners. The app itself is left alive: firebase-auth keeps a process-wide
        // reference to it, and once it is deleted the next test's FirebaseAuth.useEmulator throws
        // "FirebaseApp was deleted". Names are unique per test, so a live app is never reused.
        runCatching { firestore.terminate() }
        dir.deleteRecursively()
    }

    private companion object {
        const val TAG = "TwoDeviceSmokeTest"
        val HOST: String = BuildConfig.EMULATOR_HOST

        // Fake ids from app/google-services.json; the emulators don't check them.
        val OPTIONS: FirebaseOptions = FirebaseOptions.Builder()
            .setProjectId("demo-tandem")
            .setApplicationId("1:000000000000:android:0000000000000000")
            .setApiKey("AIzaSyDemoKeyForEmulatorOnly00000000000")
            .setStorageBucket("demo-tandem.appspot.com")
            .build()
    }
}
