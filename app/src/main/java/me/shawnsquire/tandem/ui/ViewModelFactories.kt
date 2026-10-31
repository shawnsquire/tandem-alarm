package me.shawnsquire.tandem.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import me.shawnsquire.tandem.AppContainer
import me.shawnsquire.tandem.TandemApp
import me.shawnsquire.tandem.media.RingtoneImporter
import me.shawnsquire.tandem.media.WallpaperImporter
import me.shawnsquire.tandem.media.ringtoneTitle
import me.shawnsquire.tandem.sync.LiveZoneClock
import me.shawnsquire.tandem.ui.alarms.AlarmEdits
import me.shawnsquire.tandem.ui.alarms.AlarmsViewModel
import me.shawnsquire.tandem.ui.alarms.edit.AlarmEditViewModel
import me.shawnsquire.tandem.ui.partner.PartnerViewModel
import java.util.UUID

/** The only place ui touches [AppContainer]. */
private fun CreationExtras.container(): AppContainer =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TandemApp).container

val alarmsViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TandemApp
        val container = container()
        AlarmsViewModel(
            repository = container.alarmRepository,
            edits = AlarmEdits(container.alarmRepository, container.alarmScheduler),
            clock = LiveZoneClock,
            ticker = tickerFlow(),
            // Both importers refuse paths outside app-local storage, so this is safe for any alarm.
            onAlarmPurged = { alarm ->
                alarm.wallpaperPath?.let { WallpaperImporter(app).delete(it) }
                alarm.ringtoneUri?.let { RingtoneImporter(app).delete(it) }
            },
        )
    }
}

const val ALARM_ID_ARG = "id"

val alarmEditViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TandemApp
        val container = container()
        val handle: SavedStateHandle = createSavedStateHandle()
        val ringtones = RingtoneImporter(app)
        val wallpapers = WallpaperImporter(app)
        AlarmEditViewModel(
            alarmId = handle.get<String>(ALARM_ID_ARG),
            repository = container.alarmRepository,
            edits = AlarmEdits(container.alarmRepository, container.alarmScheduler),
            ringtoneTitle = { ringtoneTitle(app, it) },
            importRingtone = { ringtones.import(Uri.parse(it)) },
            deleteRingtoneFile = { ringtones.delete(it) },
            importWallpaper = { wallpapers.import(Uri.parse(it)) },
            deleteWallpaperFile = { wallpapers.delete(it) },
            newId = { UUID.randomUUID().toString() },
        )
    }
}

val partnerViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val container = container()
        PartnerViewModel(
            pairing = container.pairingRepository,
            partnerRepository = container.partnerRepository,
            sender = container.commandSender,
            clock = LiveZoneClock,
            ticker = tickerFlow(),
        )
    }
}
