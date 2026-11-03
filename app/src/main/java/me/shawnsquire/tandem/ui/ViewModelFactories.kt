package me.shawnsquire.tandem.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.shawnsquire.tandem.AppContainer
import me.shawnsquire.tandem.BuildConfig
import me.shawnsquire.tandem.R
import me.shawnsquire.tandem.TandemApp
import me.shawnsquire.tandem.alarm.resolveRingMedia
import me.shawnsquire.tandem.backup.BackupExporter
import me.shawnsquire.tandem.backup.BackupImporter
import me.shawnsquire.tandem.backup.BackupSnapshot
import me.shawnsquire.tandem.media.RingWallpaper
import me.shawnsquire.tandem.media.RingtoneImporter
import me.shawnsquire.tandem.media.WallpaperImporter
import me.shawnsquire.tandem.media.ringtoneTitle
import me.shawnsquire.tandem.media.sha256Hex
import me.shawnsquire.tandem.sync.LiveZoneClock
import me.shawnsquire.tandem.ui.alarms.AlarmEdits
import me.shawnsquire.tandem.ui.alarms.AlarmsViewModel
import me.shawnsquire.tandem.ui.alarms.HealthBannerViewModel
import me.shawnsquire.tandem.ui.alarms.SharedPrefsBannerPrefs
import me.shawnsquire.tandem.ui.alarms.edit.AlarmEditViewModel
import me.shawnsquire.tandem.ui.alarms.linked.LinkedAlarmActions
import me.shawnsquire.tandem.ui.alarms.linked.LinkedAlarmViewModel
import me.shawnsquire.tandem.ui.partner.PartnerViewModel
import me.shawnsquire.tandem.ui.settings.PendingImport
import me.shawnsquire.tandem.ui.settings.SettingsViewModel
import me.shawnsquire.tandem.ui.settings.health.AndroidHealthProbe
import me.shawnsquire.tandem.ui.settings.health.HealthViewModel
import java.io.File
import java.io.IOException
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
            links = container.linkRepository,
            pairing = container.pairingRepository,
            partnerRepository = container.partnerRepository,
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
            sameWallpaperContent = { a, b -> withContext(Dispatchers.IO) { sha256Hex(File(a)) == sha256Hex(File(b)) } },
            settings = container.settingsRepository,
            newId = { UUID.randomUUID().toString() },
            preview = container.vibrationPreview,
            links = container.linkRepository,
            pairing = container.pairingRepository,
            partnerRepository = container.partnerRepository,
            inviteFailedReason = app.getString(R.string.link_invite_failed),
            onSaved = container.lateRingPrompts::onEdited,
        )
    }
}

/** The Linked alarm sheet's ViewModel for the joined row [alarmId]; key it per row (`linked:$alarmId`). */
fun linkedAlarmViewModelFactory(alarmId: String): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TandemApp
        val container = container()
        val ringtones = RingtoneImporter(app)
        val wallpapers = WallpaperImporter(app)
        LinkedAlarmViewModel(
            alarmId = alarmId,
            repository = container.alarmRepository,
            links = container.linkRepository,
            actions = LinkedAlarmActions(container.linkRepository, AlarmEdits(container.alarmRepository, container.alarmScheduler)),
            pairing = container.pairingRepository,
            partnerRepository = container.partnerRepository,
            clock = LiveZoneClock,
            ringtoneTitle = { ringtoneTitle(app, it) },
            importRingtone = { ringtones.import(Uri.parse(it)) },
            deleteRingtoneFile = { ringtones.delete(it) },
            importWallpaper = { wallpapers.import(Uri.parse(it)) },
            deleteWallpaperFile = { wallpapers.delete(it) },
            sameWallpaperContent = { a, b -> withContext(Dispatchers.IO) { sha256Hex(File(a)) == sha256Hex(File(b)) } },
            wallpaperWithoutOverride = { alarm ->
                // What would ring without the override: the owner's image and frame, else the Settings default.
                val settings = container.settingsRepository
                val default = settings.defaultWallpaperPath.first()?.let { RingWallpaper(it, settings.defaultWallpaperFrame.first()) }
                resolveRingMedia(alarm, container.linkedMediaCache, null, default).wallpapers.firstOrNull()
            },
            preview = container.vibrationPreview,
            rejectedReason = app.getString(R.string.link_both_rejected),
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
            alarmRepository = container.alarmRepository,
            linkRepository = container.linkRepository,
            ringingController = container.ringingController,
            clock = LiveZoneClock,
            ticker = tickerFlow(),
        )
    }
}

val settingsViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TandemApp
        val container = container()
        val ringtones = RingtoneImporter(app)
        val wallpapers = WallpaperImporter(app)
        val importer = BackupImporter(
            filesDir = app.filesDir,
            stagingRoot = File(app.cacheDir, "backup-import"),
            // A system sound this phone can't open falls back to the default sound, like a missing one.
            canOpenUri = { uri ->
                try {
                    app.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
                } catch (_: Exception) {
                    false
                }
            },
        )
        SettingsViewModel(
            settings = container.settingsRepository,
            pairing = container.pairingRepository,
            displayName = container.authManager.displayName,
            setDisplayName = { container.authManager.setDisplayName(it) },
            ringtoneTitle = { ringtoneTitle(app, it) },
            importRingtone = { ringtones.import(Uri.parse(it)) },
            deleteRingtoneFile = { ringtones.delete(it) },
            importWallpaper = { wallpapers.import(Uri.parse(it)) },
            deleteWallpaperFile = { wallpapers.delete(it) },
            sameWallpaperContent = { a, b -> withContext(Dispatchers.IO) { sha256Hex(File(a)) == sha256Hex(File(b)) } },
            exportBackup = { uri ->
                withContext(Dispatchers.IO) {
                    val settings = container.settingsRepository
                    val snapshot = BackupSnapshot(
                        alarms = container.alarmRepository.alarms.first(),
                        snoozeMinutes = settings.snoozeMinutes.first(),
                        defaultRingtoneUri = settings.defaultRingtoneUri.first(),
                        defaultWallpaperPath = settings.defaultWallpaperPath.first(),
                        defaultWallpaperFrame = settings.defaultWallpaperFrame.first(),
                        displayName = container.authManager.storedDisplayName.first(),
                    )
                    // "wt": plain "w" need not truncate, and leftover bytes from an overwritten file would corrupt the zip.
                    val out = app.contentResolver.openOutputStream(Uri.parse(uri), "wt") ?: throw IOException("Cannot open $uri")
                    out.buffered().use { BackupExporter(app.filesDir, BuildConfig.VERSION_NAME).export(snapshot, it) }
                }
            },
            readBackup = { uri ->
                withContext(Dispatchers.IO) {
                    val staged = app.contentResolver.openInputStream(Uri.parse(uri))?.use { importer.read(it) }
                        ?: throw IOException("Cannot open $uri")
                    PendingImport(
                        alarms = staged.manifest.alarms.size,
                        // Runs to the end even if Settings is left: a cancelled import would roll back what it added.
                        apply = {
                            withContext(Dispatchers.IO + NonCancellable) {
                                val edits = AlarmEdits(container.alarmRepository, container.alarmScheduler)
                                importer.apply(
                                    staged, edits::upsert, edits::delete, container.settingsRepository,
                                    displayName = { container.authManager.storedDisplayName.first() },
                                    setDisplayName = { container.authManager.setDisplayName(it) },
                                )
                            }
                        },
                        discard = staged::discard,
                    )
                }
            },
        )
    }
}

val healthViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TandemApp
        val container = container()
        HealthViewModel(
            probe = AndroidHealthProbe(app),
            signals = container.healthSignals,
            partnerRepository = container.partnerRepository,
            clock = LiveZoneClock,
            ticker = tickerFlow(),
        )
    }
}

val healthBannerViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TandemApp
        HealthBannerViewModel(probe = AndroidHealthProbe(app), prefs = SharedPrefsBannerPrefs(app))
    }
}
