package me.shawnsquire.tandem

import android.Manifest
import android.content.Context
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
import com.google.firebase.functions.FirebaseFunctions
import java.io.File
import java.time.DayOfWeek
import java.time.Duration as JavaDuration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.AlarmRepositoryImpl
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.RingStateStoreImpl
import me.shawnsquire.tandem.data.TandemDatabase
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeLinkSync
import me.shawnsquire.tandem.fakes.FakeRingingController
import me.shawnsquire.tandem.schedule.Alarm
import me.shawnsquire.tandem.schedule.Command
import me.shawnsquire.tandem.schedule.CommandStatus
import me.shawnsquire.tandem.schedule.CommandType
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
import me.shawnsquire.tandem.sync.HouseholdSource
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
import me.shawnsquire.tandem.sync.StatePublisherImpl
import me.shawnsquire.tandem.sync.await
import me.shawnsquire.tandem.sync.createSyncDataStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two installs paired through the Firebase emulators (auth 9099, firestore 8080, functions 5001 on
 * the host), driven through the repositories and the applier, not the UI.
 *
 * Both identities live in this one instrumentation process as two named [FirebaseApp]s, each with its
 * own anonymous user, Room, DataStores and wiring copied from [AppContainer]. The default app belongs
 * to [TandemApp]'s own startup, which also runs here, and is left alone. A's scheduler and ringing
 * controller are fakes so nothing arms a real alarm or starts the ringing service.
 *
 * Commands reach A only through the pre-fire pull ([PreFireCheckImpl.pullAndApply]); FCM doesn't
 * deliver in the emulator and A has no token, so `onCommandCreated` sends nothing.
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
        val aUid = within(CALLABLE, "A signs in") { a.authManager.ensureSignedIn() }
        val bUid = within(CALLABLE, "B signs in") { b.authManager.ensureSignedIn() }
        assertNotEquals(aUid, bUid)
        // As TandemStartup does after sign-in.
        a.statePublisher.start()
        b.statePublisher.start()

        // 1. Pair: A creates a code, B joins it. Both see Paired, in the same household.
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
        assertEquals(emptyList<String>(), a.ringing.dismissed)
        val stopStatus = b.sendAndPull(a, CommandType.STOP_RINGING, seenRing.alarmId, null, seenRing.ringId)
        assertEquals(CommandStatus.APPLIED to null, stopStatus)
        assertEquals(listOf(ringId), a.ringing.dismissed)

        // 6. A unpairs. Both report Unpaired.
        within(CALLABLE, "A unpairs") { a.pairingRepository.unpair() }
        within(ROUND_TRIP, "A sees Unpaired") { a.pairingRepository.status.first { it == PairingStatus.Unpaired } }
        within(ROUND_TRIP, "B sees Unpaired") { b.pairingRepository.status.first { it == PairingStatus.Unpaired } }
        Unit
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

    private suspend fun SmokeDevice.partner(predicate: (PartnerState) -> Boolean): PartnerState =
        partnerRepository.partner.first { it != null && predicate(it) }!!

    private fun PartnerState.alarm(id: String): PartnerAlarm? = alarms.firstOrNull { it.id == id }

    private fun repeatingAlarm(id: String, label: String, hour: Int, minute: Int) = Alarm(
        id = id, label = label, hour = hour, minute = minute, days = DayOfWeek.entries.toSet(),
        enabled = true, skippedOccurrenceAt = null, ringtoneUri = null, wallpaperPath = null,
    )

    private suspend fun <T> within(timeout: Duration, what: String, block: suspend () -> T): T = try {
        withTimeout(timeout) { block() }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("timed out after $timeout: $what", e)
    }

    private companion object {
        // The first callable can wait on a cold functions-emulator worker.
        val CALLABLE = 60.seconds
        val ROUND_TRIP = 30.seconds
    }
}

/** One install: a named FirebaseApp on the emulators plus the sync wiring of [AppContainer]. */
private class SmokeDevice(context: Context, name: String, displayName: String) {
    private val app: FirebaseApp = FirebaseApp.initializeApp(context, OPTIONS, name)
    private val dir = File(context.cacheDir, "smoke/$name").apply { mkdirs() }

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

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "$name uncaught", e) },
    )
    private val database = Room.inMemoryDatabaseBuilder(context, TandemDatabase::class.java).build()
    private val syncStore = createSyncDataStore(File(dir, "sync.preferences_pb"), scope)

    val alarms: AlarmRepository = AlarmRepositoryImpl(database.alarmDao(), LiveZoneClock)
    val ringState: RingStateStore = RingStateStoreImpl(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "ring_state.preferences_pb") }),
    )
    val scheduler = FakeAlarmScheduler()
    val ringing = FakeRingingController()
    val notified: MutableList<Command> = Collections.synchronizedList(mutableListOf())
    private val notifier = object : CommandNotifier {
        override fun notifyApplied(cmd: Command, alarm: Alarm?, partnerName: String, turnedOff: Boolean) {
            notified += cmd
        }
        override fun notifyInvite(cmd: Command, alarm: PartnerAlarm?, partnerName: String) = Unit
        override fun notifyOwnerDeleted(alarm: Alarm, partnerName: String) = Unit
        override fun notifyOwnerTurnedOff(alarm: Alarm, partnerName: String) = Unit
        override fun notifyRejected(alarm: Alarm, type: CommandType, reason: String?, partnerName: String) = Unit
    }

    // Same construction order as AppContainer.
    val authManager = AuthManager(auth, syncStore, defaultName = displayName)
    private val householdFlow: Flow<Household?> = HouseholdSource(authManager.uid, firestore, scope).household
    val household: StateFlow<Household?> = householdFlow.stateIn(scope, SharingStarted.Eagerly, null)
    private val fcmTokenStore = FcmTokenStore(syncStore)
    private val commandRemote = FirestoreCommandRemote(firestore)
    val partnerRepository: PartnerRepository = PartnerRepositoryImpl(householdFlow, firestore)
    val pairingRepository: PairingRepository =
        PairingRepositoryImpl(authManager, householdFlow, partnerRepository.partner, functions)
    private val commandApplier = CommandApplierImpl(
        alarms, ringState, scheduler, ringing, commandRemote, householdFlow,
        partnerRepository.partner.map { it?.displayName }.stateIn(scope, SharingStarted.Eagerly, null),
        partnerRepository.partner,
        ProcessedCommands(syncStore, LiveZoneClock), notifier, LiveZoneClock, scope,
    )
    val preFireCheck = PreFireCheckImpl(
        householdFlow, commandRemote, commandApplier::applyPending, partnerRepository, FakeLinkSync(),
        onSuccess = { scope.launch { statePublisher.publishNow() } },
    )
    val commandSender: CommandSender = CommandSenderImpl(commandRemote, household, LiveZoneClock)
    val statePublisher = StatePublisherImpl(
        alarms, ringState, householdFlow, authManager.displayName, fcmTokenStore.token,
        flowOf(emptyList()), flowOf(null), FirestoreDeviceDocWriter(firestore), LiveZoneClock, scope,
    )

    fun close() {
        scope.cancel()
        runCatching { database.close() }
        runCatching { app.delete() }
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
            .build()
    }
}
