package me.shawnsquire.tandem

import android.content.Context
import me.shawnsquire.tandem.alarm.AlarmScheduler
import me.shawnsquire.tandem.alarm.RingingController
import me.shawnsquire.tandem.data.AlarmRepository
import me.shawnsquire.tandem.data.RingStateStore
import me.shawnsquire.tandem.data.SettingsRepository
import me.shawnsquire.tandem.fakes.FakeAlarmRepository
import me.shawnsquire.tandem.fakes.FakeAlarmScheduler
import me.shawnsquire.tandem.fakes.FakeCommandApplier
import me.shawnsquire.tandem.fakes.FakeCommandSender
import me.shawnsquire.tandem.fakes.FakeHealthSignals
import me.shawnsquire.tandem.fakes.FakePairingRepository
import me.shawnsquire.tandem.fakes.FakePartnerRepository
import me.shawnsquire.tandem.fakes.FakePreFireCheck
import me.shawnsquire.tandem.fakes.FakeRingStateStore
import me.shawnsquire.tandem.fakes.FakeRingingController
import me.shawnsquire.tandem.fakes.FakeSettingsRepository
import me.shawnsquire.tandem.fakes.FakeStatePublisher
import me.shawnsquire.tandem.sync.CommandApplier
import me.shawnsquire.tandem.sync.CommandSender
import me.shawnsquire.tandem.sync.HealthSignals
import me.shawnsquire.tandem.sync.PairingRepository
import me.shawnsquire.tandem.sync.PartnerRepository
import me.shawnsquire.tandem.sync.PreFireCheck
import me.shawnsquire.tandem.sync.StatePublisher

@Suppress("unused")
class AppContainer(private val context: Context) {
    // U1: swap to real
    val alarmRepository: AlarmRepository = FakeAlarmRepository()
    val ringStateStore: RingStateStore = FakeRingStateStore()
    val settingsRepository: SettingsRepository = FakeSettingsRepository()

    // U3: swap to real
    val alarmScheduler: AlarmScheduler = FakeAlarmScheduler()
    val ringingController: RingingController = FakeRingingController()

    // U4: swap to real
    val preFireCheck: PreFireCheck = FakePreFireCheck()
    val commandApplier: CommandApplier = FakeCommandApplier()
    val commandSender: CommandSender = FakeCommandSender()
    val partnerRepository: PartnerRepository = FakePartnerRepository()
    val pairingRepository: PairingRepository = FakePairingRepository()
    val statePublisher: StatePublisher = FakeStatePublisher()
    val healthSignals: HealthSignals = FakeHealthSignals()
}
