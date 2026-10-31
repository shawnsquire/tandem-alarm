package me.shawnsquire.tandem.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import me.shawnsquire.tandem.AppContainer
import me.shawnsquire.tandem.TandemApp
import me.shawnsquire.tandem.media.RingtoneImporter
import me.shawnsquire.tandem.media.WallpaperImporter
import me.shawnsquire.tandem.sync.LiveZoneClock
import me.shawnsquire.tandem.ui.alarms.AlarmEdits
import me.shawnsquire.tandem.ui.alarms.AlarmsViewModel

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
