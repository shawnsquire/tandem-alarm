package me.shawnsquire.tandem

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.functions
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
import me.shawnsquire.tandem.sync.CommandSenderImpl
import me.shawnsquire.tandem.sync.FcmCommandHandler
import me.shawnsquire.tandem.sync.FcmTokenStore
import me.shawnsquire.tandem.sync.HealthSignalsImpl
import me.shawnsquire.tandem.sync.SyncWorker
import me.shawnsquire.tandem.sync.FirestoreCommandRemote
import me.shawnsquire.tandem.sync.FirestoreDeviceDocWriter
import me.shawnsquire.tandem.sync.Household
import me.shawnsquire.tandem.sync.HouseholdSource
import me.shawnsquire.tandem.sync.LiveZoneClock
import me.shawnsquire.tandem.sync.PairingRepositoryImpl
import me.shawnsquire.tandem.sync.PartnerRepositoryImpl
import me.shawnsquire.tandem.sync.PreFireCheckImpl
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

@Suppress("unused")
class AppContainer private constructor(
    private val context: Context,
    private val database: TandemDatabase,
    val syncScope: CoroutineScope,
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
    val authManager = AuthManager(Firebase.auth, context.syncDataStore)
    // Live shared flow (replay 1): its first value is a real snapshot result, so cold-process pulls don't see a fake null.
    val householdFlow: Flow<Household?> = HouseholdSource(authManager.uid, firestore, syncScope).household

    // Only for CommandSenderImpl, which needs a synchronous .value; everything else uses householdFlow.
    val household: StateFlow<Household?> = householdFlow.stateIn(syncScope, SharingStarted.Eagerly, null)
    val fcmTokenStore = FcmTokenStore(context.syncDataStore)
    private val commandRemote = FirestoreCommandRemote(firestore)
    val partnerRepository: PartnerRepository = PartnerRepositoryImpl(householdFlow, firestore)
    val pairingRepository: PairingRepository =
        PairingRepositoryImpl(authManager, householdFlow, partnerRepository.partner, Firebase.functions)
    val commandApplierImpl = CommandApplierImpl(
        alarmRepository, ringStateStore, alarmScheduler, ringingController, commandRemote, householdFlow,
        partnerRepository.partner.map { it?.displayName }.stateIn(syncScope, SharingStarted.Eagerly, null),
        ProcessedCommands(context.syncDataStore, LiveZoneClock),
        AndroidCommandNotifier(context), LiveZoneClock, syncScope,
    )
    val commandApplier: CommandApplier = commandApplierImpl
    val preFireCheckImpl = PreFireCheckImpl(
        householdFlow, commandRemote, commandApplierImpl::applyPending,
        // Lazy capture: statePublisherImpl is declared below, but this only runs after construction.
        onSuccess = { syncScope.launch { statePublisherImpl.publishNow() } },
    )
    val preFireCheck: PreFireCheck = preFireCheckImpl
    val commandSender: CommandSender = CommandSenderImpl(commandRemote, household, LiveZoneClock)
    val statePublisherImpl = StatePublisherImpl(
        alarmRepository, ringStateStore, householdFlow, authManager.displayName, fcmTokenStore.token,
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
    init {
        alarmEngine.attach(roomAlarmRepository, roomSettings) { preFireCheck }
    }

    companion object {
        /**
         * The scope and database are made here, outside the constructor, so a construction that throws
         * can cancel the listeners and collectors started on the scope and close Room. Otherwise each
         * retry would leave another set running.
         */
        operator fun invoke(context: Context): AppContainer {
            val syncScope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("Sync", "uncaught", e) },
            )
            val database = TandemDatabase.build(context)
            try {
                return AppContainer(context, database, syncScope)
            } catch (e: Throwable) {
                syncScope.cancel()
                runCatching { database.close() }
                throw e
            }
        }
    }
}
