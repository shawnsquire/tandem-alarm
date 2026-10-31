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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    val alarmScheduler: AlarmScheduler = FakeAlarmScheduler()
    val ringingController: RingingController = FakeRingingController()

    // U4: swap to real
    val syncScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("Sync", "uncaught", e) },
    )
    private val firestore by lazy { Firebase.firestore }
    val authManager = AuthManager(Firebase.auth, context.syncDataStore)
    val household: StateFlow<Household?> =
        HouseholdSource(authManager.uid, firestore, syncScope).household.stateIn(syncScope, SharingStarted.Eagerly, null)
    val fcmTokenStore = FcmTokenStore(context.syncDataStore)
    private val commandRemote = FirestoreCommandRemote(firestore)
    val partnerRepository: PartnerRepository = PartnerRepositoryImpl(household, firestore)
    val pairingRepository: PairingRepository =
        PairingRepositoryImpl(authManager, household, partnerRepository.partner, Firebase.functions)
    val commandApplierImpl = CommandApplierImpl(
        alarmRepository, ringStateStore, alarmScheduler, ringingController, commandRemote, household,
        partnerRepository.partner.map { it?.displayName },
        ProcessedCommands(context.syncDataStore, Clock.systemDefaultZone()),
        AndroidCommandNotifier(context), Clock.systemDefaultZone(),
    )
    val commandApplier: CommandApplier = commandApplierImpl
    val preFireCheckImpl = PreFireCheckImpl(household, commandRemote, commandApplierImpl::applyPending)
    val preFireCheck: PreFireCheck = preFireCheckImpl
    val commandSender: CommandSender = CommandSenderImpl(commandRemote, household, Clock.systemDefaultZone())
    val statePublisher: StatePublisher = StatePublisherImpl(
        alarmRepository, ringStateStore, household, authManager.displayName, fcmTokenStore.token,
        FirestoreDeviceDocWriter(firestore), Clock.systemDefaultZone(), syncScope,
    )
    val ringingCommandListener =
        RingingCommandListener(ringStateStore.state, household, commandRemote, commandApplierImpl::applyPending, syncScope)
    val healthSignalsImpl = HealthSignalsImpl(context.syncDataStore, fcmTokenStore)
    val healthSignals: HealthSignals = healthSignalsImpl
    val fcmCommandHandler = FcmCommandHandler(
        commandApplier, healthSignalsImpl::recordFcm, Clock.systemDefaultZone(), { SyncWorker.runNow(context) },
    )
}
