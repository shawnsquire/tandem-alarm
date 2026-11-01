package me.shawnsquire.tandem

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.functions
import com.google.firebase.storage.storage
import java.io.File
import java.time.Clock
import me.shawnsquire.tandem.alarm.AlarmScheduler
import me.shawnsquire.tandem.alarm.RingingController
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.AlarmRepositoryImpl
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.data.SettingsRepositoryImpl
import me.shawnsquire.tandem.data.TandemDatabase
import me.shawnsquire.tandem.data.settingsDataStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.shawnsquire.tandem.sync.AndroidCommandNotifier
import me.shawnsquire.tandem.sync.AuthManager
import me.shawnsquire.tandem.sync.CommandApplier
import me.shawnsquire.tandem.sync.CommandApplierImpl
import me.shawnsquire.tandem.sync.CommandNotifier
import me.shawnsquire.tandem.sync.CommandSenderImpl
import me.shawnsquire.tandem.sync.FcmCommandHandler
import me.shawnsquire.tandem.sync.FcmTokenStore
import me.shawnsquire.tandem.sync.HealthSignalsImpl
import me.shawnsquire.tandem.sync.SyncWorker
import me.shawnsquire.tandem.sync.FirestoreCommandRemote
import me.shawnsquire.tandem.sync.FirestoreDeviceDocWriter
import me.shawnsquire.tandem.sync.Household
import me.shawnsquire.tandem.sync.HouseholdCheck
import me.shawnsquire.tandem.sync.HouseholdSource
import me.shawnsquire.tandem.sync.householdOnServer
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import me.shawnsquire.tandem.sync.AndroidLateRingNotifier
import me.shawnsquire.tandem.sync.LateRingCheckWorker
import me.shawnsquire.tandem.sync.LateRingPrompts
import me.shawnsquire.tandem.sync.LiveZoneClock
import me.shawnsquire.tandem.sync.PairingRepositoryImpl
import me.shawnsquire.tandem.sync.PartnerRepositoryImpl
import me.shawnsquire.tandem.sync.PreFireCheckImpl
import me.shawnsquire.tandem.sync.InviteLedger
import me.shawnsquire.tandem.sync.ProcessedCommands
import me.shawnsquire.tandem.sync.RingingCommandListener
import me.shawnsquire.tandem.sync.StatePublisherImpl
import me.shawnsquire.tandem.sync.syncDataStore
import me.shawnsquire.tandem.sync.CommandSender
import me.shawnsquire.tandem.sync.HealthSignals
import me.shawnsquire.tandem.sync.PairingRepository
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.sync.PreFireCheck
import me.shawnsquire.tandem.sync.StatePublisher
import me.shawnsquire.tandem.media.AndroidMediaSource
import me.shawnsquire.tandem.media.FirebaseMediaStorage
import me.shawnsquire.tandem.media.LinkedMediaCache
import me.shawnsquire.tandem.media.LinkedMediaCacheImpl
import me.shawnsquire.tandem.media.LinkedMediaDownloadWorker
import me.shawnsquire.tandem.media.MediaPublishStore
import me.shawnsquire.tandem.media.MediaPublisherImpl
import me.shawnsquire.tandem.sync.LinkRepository
import me.shawnsquire.tandem.sync.LinkRemovals
import me.shawnsquire.tandem.sync.LinkRepositoryImpl
import me.shawnsquire.tandem.sync.LinkSync
import me.shawnsquire.tandem.sync.LinkSyncImpl
import me.shawnsquire.tandem.sync.RingMirrorImpl
import me.shawnsquire.tandem.sync.MediaPublisher
import me.shawnsquire.tandem.sync.RingMirror

@Suppress("unused")
class AppContainer private constructor(
    private val context: Context,
    private val database: TandemDatabase,
    val syncScope: CoroutineScope,
    val authManager: AuthManager,
) {
    // U1: swap to real
    // Only the engine sees Room and the settings DataStore directly: everyone else goes through
    // alarmRepository and settingsRepository below.
    private val roomAlarmRepository = AlarmRepositoryImpl(database.alarmDao(), Clock.systemDefaultZone())
    private val roomSettings = SettingsRepositoryImpl(context.settingsDataStore)

    // U3: swap to real
    // Attached at the end of construction (the init block below).
    private val alarmEngine = me.shawnsquire.tandem.alarm.AlarmEngine.get(context)
    // Waits for the attach drain, so no consumer (a command applied at unlock, the UI) can be
    // overwritten by an older locked write replayed after it. No initializer below calls it.
    val alarmRepository: AlarmRepository = alarmEngine.alarmRepository
    // Its snooze and ringtone edits refresh the device-protected mirror.
    val settingsRepository: SettingsRepository = alarmEngine.settings
    // One store per process: the engine's.
    val ringStateStore: RingStateStore = alarmEngine.ringStateStore
    val alarmScheduler: AlarmScheduler = alarmEngine.scheduler
    val ringingController: RingingController = alarmEngine.controller
    val ringSlot: me.shawnsquire.tandem.alarm.RingSlot = alarmEngine.ringSlot

    // U4: swap to real
    private val firestore by lazy { Firebase.firestore }
    // Live shared flow (replay 1): its first value is a real snapshot result, so cold-process pulls don't see a fake null.
    val householdFlow: Flow<Household?> = HouseholdSource(authManager.uid, firestore, syncScope).household

    // Only for CommandSenderImpl, which needs a synchronous .value; everything else uses householdFlow.
    val household: StateFlow<Household?> = householdFlow.stateIn(syncScope, SharingStarted.Eagerly, null)
    val fcmTokenStore = FcmTokenStore(context.syncDataStore)
    private val commandRemote = FirestoreCommandRemote(firestore)
    val partnerRepository: PartnerRepository = PartnerRepositoryImpl(householdFlow, firestore)
    // L8: asks the owner whether a joiner that hasn't picked up an earlier move should ring late.
    val lateRingPrompts = LateRingPrompts(
        alarmRepository, partnerRepository, AndroidLateRingNotifier(context),
        { LateRingCheckWorker.enqueue(context, it) }, { LateRingCheckWorker.cancel(context, it) }, LiveZoneClock, syncScope,
    )
    val pairingRepository: PairingRepository =
        PairingRepositoryImpl(authManager, householdFlow, partnerRepository.partner, Firebase.functions)
    val commandNotifier: CommandNotifier = AndroidCommandNotifier(context)
    val commandSender: CommandSender = CommandSenderImpl(commandRemote, household, LiveZoneClock)

    // The link ports. Each marked line is the only line its unit changes: the real implementation can
    // use anything declared above (alarmRepository, settingsRepository, partnerRepository,
    // commandSender, commandRemote, householdFlow, commandNotifier, alarmScheduler, ringingController,
    // syncScope, context). They are declared before the consumers below, which take them as inputs,
    // and the init block hands ringMirror and linkedMediaCache to the engine. The order is
    // linkedMediaCache, linkRemovals, linkRepositoryImpl, linkSyncImpl (takes linkedMediaCache, which
    // it feeds), ringMirror, then mediaPublisher (takes linkRepository, whose openInvites it reads);
    // healthSignalsImpl is declared further down, after those and after commandApplierImpl,
    // preFireCheckImpl, statePublisherImpl and ringingCommandListener, so the earlier ones reach it only
    // through lambdas.
    // L5: real (the engine gets it in init). healthSignalsImpl is declared below; the lambdas run later.
    val linkedMediaCache: LinkedMediaCache = LinkedMediaCacheImpl(
        context.filesDir,
        FirebaseMediaStorage({ Firebase.storage }) { ok -> healthSignalsImpl.recordStorage(ok, LiveZoneClock.instant()) },
        enqueueDownload = { LinkedMediaDownloadWorker.enqueue(context) },
        onStatus = { referenced, missing -> syncScope.launch { healthSignalsImpl.recordLinkedMedia(referenced, missing) } },
    )
    private val linkRemovals = LinkRemovals(context.syncDataStore) // L4: one instance shared by the three below
    // L4: linkSyncImpl is declared below; the lambda runs later.
    val linkRepositoryImpl = LinkRepositoryImpl(
        alarmRepository, partnerRepository.partner, householdFlow, commandSender, commandRemote,
        ProcessedCommands(context.syncDataStore, LiveZoneClock), InviteLedger(context.syncDataStore, LiveZoneClock), linkRemovals,
        { id, reason -> linkSyncImpl.requestRemoval(id, reason) },
        { id -> linkSyncImpl.cancelRemoval(id) },
        { row, cmd -> linkSyncImpl.noteApplied(row, cmd) },
        { cmd -> linkSyncImpl.appliedSettled(cmd) },
        alarmScheduler, commandNotifier, LiveZoneClock, syncScope,
    )
    val linkRepository: LinkRepository = linkRepositoryImpl
    val linkSyncImpl = LinkSyncImpl(
        partnerRepository.partner, householdFlow, alarmRepository, ringingController, ringStateStore.state,
        alarmScheduler, linkedMediaCache, commandNotifier, linkRemovals, LiveZoneClock, syncScope,
        confirmHousehold = {
            val uid = authManager.uid.value
            if (uid == null) HouseholdCheck.Unknown
            else withTimeoutOrNull(15.seconds) { householdOnServer(firestore, uid) } ?: HouseholdCheck.Unknown
        },
    ) // L4
    val linkSync: LinkSync = linkSyncImpl
    val ringMirror: RingMirror = // L4 (the engine's port gets it in init)
        RingMirrorImpl(alarmRepository, partnerRepository.partner, household, commandSender, linkRemovals, syncScope)
    // L5: real
    val mediaPublisher: MediaPublisher = MediaPublisherImpl(
        alarmRepository, partnerRepository, linkRepository, settingsRepository, householdFlow,
        FirebaseMediaStorage({ Firebase.storage }) { ok -> healthSignalsImpl.recordStorage(ok, LiveZoneClock.instant()) },
        AndroidMediaSource(context), MediaPublishStore(File(context.filesDir, "media-publisher.json")),
        context.cacheDir, syncScope,
    )

    val commandApplierImpl = CommandApplierImpl(
        alarmRepository, ringStateStore, alarmScheduler, ringingController, commandRemote, householdFlow,
        partnerRepository.partner.map { it?.displayName }.stateIn(syncScope, SharingStarted.Eagerly, null),
        partnerRepository.partner,
        ProcessedCommands(context.syncDataStore, LiveZoneClock), InviteLedger(context.syncDataStore, LiveZoneClock),
        linkRemovals, commandNotifier, LiveZoneClock, syncScope,
    )
    val commandApplier: CommandApplier = commandApplierImpl
    val preFireCheckImpl = PreFireCheckImpl(
        householdFlow, commandRemote, commandApplierImpl::applyPending, partnerRepository, linkSync,
        // Lazy capture: statePublisherImpl is declared below, but this only runs after construction.
        onSuccess = { syncScope.launch { statePublisherImpl.publishNow() } },
    )
    val preFireCheck: PreFireCheck = preFireCheckImpl
    val statePublisherImpl = StatePublisherImpl(
        alarmRepository, ringStateStore, householdFlow, authManager.displayName, fcmTokenStore.token,
        linkRepository.links, mediaPublisher.published,
        FirestoreDeviceDocWriter(firestore), LiveZoneClock, syncScope,
    )
    val statePublisher: StatePublisher = statePublisherImpl
    val ringingCommandListener =
        RingingCommandListener(ringStateStore.state, householdFlow, commandRemote, commandApplierImpl::applyPending, syncScope)
    val healthSignalsImpl = HealthSignalsImpl(context.syncDataStore, fcmTokenStore)
    val healthSignals: HealthSignals = healthSignalsImpl
    val fcmCommandHandler = FcmCommandHandler(
        commandApplier, householdFlow, healthSignalsImpl::recordFcm, LiveZoneClock, { SyncWorker.runNow(context) },
    )

    // Last, once every property above is built: a construction that throws never attaches, so the
    // engine is never left on a failed attempt's Room (or a half-built preFireCheck) while the
    // retry builds another. The provider runs when a fire pulls, not here.
    // The delegates come after attach, so they too are set only by a container that fully built:
    // assigning the mirror delegate replays the locked-boot outbox into it, and a failed attempt's
    // mirror must never receive that. Until then the engine queues mirror calls on disk and uses the
    // default media cache, which resolves from the alarm row alone; ringMirror and linkedMediaCache replace them.
    init {
        alarmEngine.attach(roomAlarmRepository, roomSettings) { preFireCheck }
        alarmEngine.ringMirrorPort.delegate = ringMirror
        alarmEngine.linkedMediaCache.delegate = linkedMediaCache
    }

    companion object {
        /**
         * The scope, database and auth manager are made here, outside the constructor, so a construction
         * that throws can cancel the listeners and collectors started on the scope, close Room and
         * detach the auth-state listener. Otherwise each retry would leave another set running.
         */
        operator fun invoke(context: Context): AppContainer {
            val syncScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("Sync", "uncaught", e) },
            )
            val database = TandemDatabase.build(context)
            var authManager: AuthManager? = null
            try {
                authManager = AuthManager(Firebase.auth, context.syncDataStore)
                return AppContainer(context, database, syncScope, authManager)
            } catch (e: Throwable) {
                runCatching { authManager?.close() }
                syncScope.cancel()
                runCatching { database.close() }
                throw e
            }
        }
    }
}
