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
import me.shawnsquire.tandem.data.RingStateStoreImpl
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.data.SettingsRepositoryImpl
import me.shawnsquire.tandem.data.TandemDatabase
import me.shawnsquire.tandem.data.ringStateDataStore
import me.shawnsquire.tandem.data.settingsDataStore
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeRingingController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
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
class AppContainer(private val context: Context) {
    // U1: swap to real
    val alarmRepository: AlarmRepository = AlarmRepositoryImpl(TandemDatabase.build(context).alarmDao(), Clock.systemDefaultZone())
    val ringStateStore: RingStateStore = RingStateStoreImpl(context.ringStateDataStore)
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(context.settingsDataStore)

    // U3: swap to real
    val alarmScheduler: AlarmScheduler =
        me.shawnsquire.tandem.alarm.AlarmSchedulerImpl(context, alarmRepository, ringStateStore)
    val ringingController: RingingController = me.shawnsquire.tandem.alarm.RingingControllerImpl(
        alarmRepository, ringStateStore, settingsRepository, alarmScheduler,
        me.shawnsquire.tandem.alarm.AndroidRingingNotifier(context),
    )

    // U4: swap to real
    val syncScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("Sync", "uncaught", e) },
    )
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
}
