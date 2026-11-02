# Tandem Alarm — design

Date: 2026-10-31 · Status: implemented (see "Decisions made during the build")

## Goal

An Android alarm clock in the spirit of the stock Clock app, plus partner control: two paired phones (Shawn and his wife) can each see the other's alarms, see whether one is ringing, and skip, turn off, or stop the other's alarm. Typical use: whoever gets up first skips the other's morning alarm; whoever acknowledges their own alarm can silence the partner's.

## Requirements

1. Runs on current Android (compile/target SDK 37, Android 17; minSdk 33).
2. Alarms have a time and a set of weekdays. No weekdays = one-shot (turns itself off after it rings, or when a partner skips it).
3. Per-alarm ringtone: any system alarm/ringtone sound, or an audio file the user picks.
4. Per-alarm wallpaper shown full-screen while ringing, falling back to one default image from Settings.
5. Pair with one other phone. The partner sees **all** your alarms and their state (on/off, next ring time, skipped, ringing, snoozed).
6. The partner can: **skip next ring**, **un-skip**, **turn off**, **turn on**, **stop ringing** (dismiss, including a pending snooze). The partner cannot create or edit alarm times.
7. "Skip next ring" cancels exactly the next occurrence, whenever it is; the alarm stays on and resumes after. This is also available locally.
8. When the partner changes one of your alarms you get a notification ("Shawn skipped your 6:30").
9. Stock-clock basics: snooze (default 10 min, set in Settings), auto-silence after 10 min ringing, ring through Do Not Disturb.
10. Per-alarm ring outputs: a vibration pattern (Pulse, Heartbeat, Rapid, Slow wave or Escalating; Pulse is the default and the pattern rings used before patterns existed), a vibration switch and a sound switch. Both switches default to on. Switching sound off keeps the chosen ringtone. There is no "no vibration" pattern: that is the vibration switch. At least one switch stays on, so a vibrate-only alarm is an alarm with sound off.
11. Wallpaper framing and text position: each wallpaper has a frame (zoom and the point of the image at the screen's centre) that the editor sets with a full-screen preview of the ringing screen, and the text block (clock, alarm-time line, labels) sits at the top, middle or bottom of the ringing screen. The alarm-time line under the clock names the occurrence times that rang and, after a snooze, how many times the ring was snoozed.

Out of scope (YAGNI): iOS, more than two devices, remote editing of times, notifying the partner when your alarm starts ringing (possible later via the same Cloud Function), cloud backup of alarms, timers/stopwatch.

## Invariants (do not "improve" these away)

- **I1 — Alarms ring without the network.** Firing never waits on Firebase beyond a hard pre-fire timeout (3 s). If the check fails, times out, or the user is offline, the alarm rings. A ring may be suppressed only by a command already stored locally or by a pre-fire check that actually succeeded.
- **I2 — One writer per alarm.** Each phone is the sole source of truth for its own alarms (local Room DB). It mirrors them to Firestore. The partner never writes alarm data; it sends *commands*, and the owner applies them and republishes.
- **I3 — Commands are pinned to what the partner saw.** Skip, un-skip, and turn-off carry the `occurrenceAt` epoch the partner was looking at; stop-ringing carries the `ringId` of that specific ring. The owner rejects a command whose target has passed or doesn't match (so a skip delayed overnight never skips tomorrow by accident). Commands expire after 24 h and are idempotent by command ID.

## Architecture

```
 Phone A (owner of A's alarms)                     Firebase                         Phone B
 ┌───────────────────────────┐   mirror state   ┌──────────────────────┐  snapshot  ┌──────────────┐
 │ Room ── AlarmScheduler ───┼─────────────────▶│ households/{h}/      │──────────▶│ Partner UI   │
 │   ▲     (setAlarmClock)   │                  │   devices/{uidA}     │            │              │
 │   │                       │                  │   commands/{id}  ◀───┼────────────┤ send command │
 │ CommandApplier ◀── FCM ◀──┼── Cloud Function ◀─ onCreate(command)   │            └──────────────┘
 │   ▲                       │   (high-priority data msg)              │
 │   └── pull pending (pre-fire check, app open, periodic WorkManager) │
 └───────────────────────────┘                  └──────────────────────┘
```

Command delivery has three paths, any of which is enough: (1) FCM high-priority data message sent by a Cloud Function when the command is created; (2) pull of pending commands right before an alarm fires (setAlarmClock brings the device out of Doze just before the alarm); (3) pull on app open and a periodic WorkManager job (~15 min). While ringing, the ringing service also holds a Firestore listener on commands so "stop" lands instantly even if FCM is slow.

## Units

Each unit has one job and is testable alone. Package root `me.shawnsquire.tandem`.

| Unit | Does | Depends on |
|---|---|---|
| `schedule` (pure Kotlin, no Android) | `Alarm` model; `nextOccurrence(alarm, now, zone)` honoring weekdays, skip, one-shot; skip-expiry; command validation (`validate(command, alarm, now)` → apply/reject reason) | `java.time` only |
| `data` | Room DB + `AlarmRepository` (Flow of alarms, CRUD, `applySkip/clearSkip/setEnabled`) and `SettingsRepository` (DataStore: snooze length, default wallpaper, ringtone default) | `schedule` |
| `alarm` | `AlarmScheduler` (setAlarmClock for each enabled alarm's next occurrence; reschedule on any change), `AlarmReceiver`, `BootReceiver` (locked boot, boot, time/timezone change, app update), `RingingService` (foreground service, `USAGE_ALARM` MediaPlayer, vibration, snooze/dismiss, auto-silence), `RingingActivity` (full-screen intent, wallpaper, big Stop/Snooze) | `data`, `media`, `sync.PreFireCheck` (interface) |
| `media` | Ringtone choice: system picker (`RingtoneManager.ACTION_RINGTONE_PICKER`) or file (`ACTION_OPEN_DOCUMENT`, persistable URI permission, fallback to default alarm sound if the URI is unreadable at fire time). Wallpaper: photo picker → copied into app files, downscaled | Android only |
| `sync` | `AuthManager` (anonymous auth), `PairingRepository`, `StatePublisher` (mirror local alarms + ringing state to own device doc), `PartnerRepository` (Flow of partner's device doc), `CommandSender`, `CommandApplier` (validate via `schedule`, apply via `data`, mark applied/rejected, notify user), `TandemMessagingService` (FCM + token upload; the command payload names the sender `fromUid`), `PreFireCheck` (3 s timeout pull), `SyncWorker` | Firebase SDKs, `data`, `schedule` |
| `ui` | Compose + Material 3: Alarm list, Alarm edit, Partner tab, Settings (pairing, snooze, default wallpaper, **health check**) | `data`, `sync`, `media` |
| `firebase/` (repo dir, not Android) | `firestore.rules`, `functions/` (TypeScript: `onCommandCreated` → FCM to target's token), emulator tests | Firebase CLI, Node 22 |

No DI framework; one hand-written `AppContainer` in `Application`.

## Data model (Firestore)

```
users/{uid}                       { householdId }                            // index; written only by Cloud Functions
pairCodes/{code}                  { householdId, createdBy, expiresAt }      // 6 chars, 10 min TTL, single-use
households/{hid}                  { members: [uidA, uidB], createdAt }
households/{hid}/devices/{uid}    { displayName, fcmToken, updatedAt,
                                    alarms: [{ id, label, hour, minute, days[], enabled,
                                               nextOccurrenceAt, skippedOccurrenceAt,
                                               vibration, vibrationOn, soundOn,
                                               textPosition, wallpaperFrame? }],
                                    defaultWallpaperFrame: { zoom, x, y } | null,
                                    ringing: { alarmId, ringId, startedAt, snoozedUntil } | null }
households/{hid}/commands/{cid}   { from, to, type, alarmId, occurrenceAt?, ringId?,
                                    createdAt, expiresAt, status: pending|applied|rejected, reason? }
```

Ringtone and wallpaper URIs stay on the device; only display data is mirrored. `vibration` is the pattern's name, and `vibrationOn` and `soundOn` are booleans. A reader treats a missing field or an unknown pattern name as Pulse, on, on.

`textPosition` is the position's name (`TOP`, `MIDDLE` or `BOTTOM`); a missing or unknown value reads as `MIDDLE`. `wallpaperFrame` is `{zoom, x, y}` and is absent when the wallpaper is centred; a malformed one reads as centred and a valid one is sanitised (zoom 1 to 4, x and y 0 to 1, NaN or infinite parts replaced by their centred value). `defaultWallpaperFrame` is the same shape for the Settings default wallpaper, and an explicit null when none is set. All three sit outside the media references, so changing a frame or a position never changes a media hash and uploads nothing.

**Pairing:** Phone A taps "Pair" → the callable function `createPairCode` creates the household (if A has none) and a pair code, and A shows it (text and QR). Phone B enters/scans it → the callable `joinHousehold` runs a transaction that adds B to `members` and deletes the code. The callable `unpair` removes the household and both `users/{uid}` entries. Clients write none of these documents; `users/{uid}` is the index that lets a function find a caller's household and stops a double tap creating two.

**Auth and rules:** Firebase Anonymous Auth, one uid per install. Rules: only `members` read household data; a device writes only its own device doc; commands may be created only with `from == auth.uid` and `to` = the other member; only the `to` device may update `status`; `households`, `users` and `pairCodes` are not client-writable, and `pairCodes` is not client-readable; the pairing functions enforce that codes are single-use and rejected after `expiresAt`, and that a household holds at most two members. A user can read only their own `users/{uid}`.

## Key flows

- **Partner skips:** B taps Skip on A's 06:30 → command `{SKIP_NEXT, alarmId, occurrenceAt: <the 06:30 epoch B saw>}` → Function sends FCM → A's `CommandApplier` checks the occurrence still matches and is in the future → sets `skippedOccurrenceAt`, reschedules, marks applied, republishes, notifies A. B's UI shows "pending" until applied, "rejected: already rang" otherwise.
- **Skip expiry:** once now > `skippedOccurrenceAt`, `nextOccurrence` ignores it and the field is cleared on next reschedule. The alarm reactivates with no extra job.
- **Fire:** `AlarmReceiver` starts `RingingService` immediately (foreground, silent full-screen UI), runs `PreFireCheck` with a 3 s cap, then either rings or quietly finishes (and publishes "skipped by partner"). Publishes `ringing`.
- **Remote stop:** B taps Stop while A rings → command with A's current `ringId` → FCM and the ringing service's live listener both deliver → dismiss.
- **Errors:** rejected commands show their reason on B. Firestore offline writes queue automatically. Missing ringtone file → default alarm sound. Missing wallpaper → default/plain, and the text still sits at its chosen position.

## Android platform notes (verify at implementation; rules change between releases)

- Scheduling: `AlarmManager.setAlarmClock` with `USE_EXACT_ALARM` (allowed for alarm-clock apps; sideloaded anyway).
- Ringing: foreground service started from the alarm broadcast (exempt from background-start limits); confirm the right `foregroundServiceType` for alarm playback on API 37 (likely `mediaPlayback`, or `systemExempted` if alarm apps qualify) before writing the manifest.
- Full-screen intent: `USE_FULL_SCREEN_INTENT`. Sideloaded apps may need the user to grant it in Settings; the health check links there.
- Audio: `AudioAttributes.USAGE_ALARM` so it sounds through Do Not Disturb.
- Mixed OEMs: the non-Pixel phone may block background work. **Health check screen** shows: exact alarm, full-screen intent, notifications, battery-optimization exemption (with a button to request it), FCM token present, time of last FCM message received, partner's last-seen time.

## Testing

- `schedule` unit tests (JVM, fast): weekday rollover, one-shot, skip next, skip expiry, command validation (stale occurrence, wrong ringId, expired, duplicate), **DST** — US fall-back on 2026-11-01 (alarm at 01:30 occurs twice; pick the first) and spring-forward 2027-03-14 (02:30 doesn't exist; rings at 03:30, the shifted local time `ZonedDateTime` gives).
- `data` Room tests (Robolectric or instrumented).
- Firestore rules tests against the emulator (`@firebase/rules-unit-testing`): non-members denied, own-doc-only writes, command `from` spoofing denied, only target updates status, pair codes single-use and expiring.
- Cloud Function test against the emulator with a mocked messaging send.
- Manual on-device checklist (both phones): ring with screen off, in Doze (`adb shell dumpsys deviceidle force-idle`), offline, through DND, remote skip while target dozes, remote stop while ringing, reboot reschedules (without unlocking first), USER_UNLOCKED after a locked boot, and alarms still armed after an app update. The full list is in the README.

## Decisions made during the build

- **Spring-forward rings at 03:30.** `nextOccurrence` builds the time with `ZonedDateTime.of`, which moves a nonexistent 02:30 forward by the length of the gap. The original plan said 03:00. Fall-back picks the first occurrence as planned. (`schedule/Scheduling.kt`)
- **`FIRE_GRACE` = 2 min.** A command pinned to an occurrence that has just fired still applies for 2 minutes, so a skip sent as the alarm fires is not rejected as stale. (`schedule/Scheduling.kt`, `validate`)
- **Skipping a one-shot alarm turns it off.** A one-shot has no later occurrence to resume at, so SKIP_NEXT on one disables it instead of setting `skippedOccurrenceAt`. (`sync/CommandApplierImpl.kt`)
- **Pairing goes through callable functions and a `users/{uid}` index**, as described under Pairing. (`firebase/functions/src/pairing.ts`, `firebase/firestore.rules`)
- **FCM key `fromUid`.** `from` is a reserved key in FCM data messages, so the payload uses `fromUid` and the app maps it back to `Command.from`. (`firebase/functions/src/commands.ts`, `sync/FirestoreMapping.kt`)
- **Direct boot.** After a reboot the alarms must ring before the first unlock, when credential-encrypted storage (Room, Firebase, WorkManager) is unavailable. `AlarmReceiver`, `BootReceiver` (also on `LOCKED_BOOT_COMPLETED`), `RingingService` and `RingingActivity` are `directBootAware`; ring state and scheduler records live in device-protected storage (`alarm/DeviceStorage.kt`, `data/DataStores.kt`), and `AlarmEngine` schedules from them. `TandemApp` defers Firebase, WorkManager and the `AppContainer` until `USER_UNLOCKED` (`TandemStartup`), then applies writes deferred while locked.
- **Vibrate-only keeps I1.** A ring plays no sound and no bundled tone only while its vibration actually runs. It rings with sound when the phone has no vibrator (`hasVibrator()` is false), when the vibration start throws (a pattern other than Pulse is retried as Pulse first), when both switches are off, on the fallback ring, and when the alarm can't be read (Pulse plus sound). Early vibration is started as soon as the ring is decided, before the slot claim and any audio or disk work. It uses the alarm's pattern and does not start when the alarm's vibration is off. The fallback ring keeps a vibration that is already running and starts Pulse only if none is. The reverse also holds: when sound fails (the start throws, or nothing in the chain plays, the bundled tone included) and nothing vibrates, the ring vibrates with Pulse. (`alarm/RingingSession.kt`)
- **One vibrator, one arbiter.** The app has one vibrator and `Vibrator.cancel()` stops all of it, so every ringing session and the editor preview share one `VibrationArbiter` per process. It keeps a pattern for each session and plays the earliest session's pattern. It re-vibrates only when that pattern changes and cancels only when the last session stops, so a superseded or merged session's late stop doesn't silence the ring still going. If the pattern to play fails for a session that was already told it vibrates, the arbiter plays Pulse instead. The editor preview is one non-repeating pass through the same arbiter. It is refused while a ring vibrates, a ring's start replaces it, and its stop never cancels a ring. (`alarm/RingingAudio.kt`)
- **Waveforms.** Each pattern is a table in `alarm/VibrationWaveforms.kt`, free of `android.os` so it is JVM-testable. Pulse is `0, 800, 600` looped. Heartbeat is two 150 ms beats and an 800 ms pause. Rapid is 100 ms on and off. Slow wave is a swell up and down in amplitude steps, then a rest. Escalating raises the amplitude over 30 beats (36 s), then holds at full strength, and only the hold repeats. A device without amplitude control (`hasAmplitudeControl()` is false) gets on/off timings instead: Slow wave becomes one long buzz and a rest, and Escalating's on-times lengthen toward the hold's.
- **Both switches off normalizes to both on.** The editor locks the last switch that is still on, with the hint "Sound or vibration must stay on". A combination with both off anyway (for example the owner turning off the one switch a joiner override left on) rings as sound on and vibration on, with the alarm's own pattern; the linked sheet still shows both off. (`schedule/Model.kt`, `effectiveOutputs`)
- **Vibrate-only is silent when the phone's alarm vibration is off. Accepted risk.** Shawn's call, 2026-11-02. AOSP drops `USAGE_ALARM` vibration when "Use vibration & haptics" or "Alarm vibration" is off, and `vibrate()` doesn't report it. Apps can't read either setting, so the app can't tell that a vibrate-only alarm made no noise and can't ring with sound instead. The editor shows a hint under the switches while sound is off: if the phone's Alarm vibration setting is off, this alarm makes no noise. The on-device checklist covers it.
- **Storage and migration.** Room v5 (`MIGRATION_4_5`) adds `vibration`, `vibrationOn` and `soundOn`. The direct-boot mirror carries them and the joiner overrides, so a locked boot rings with the right outputs. Rows and mirror records from before read as Pulse, on, on.
- **Frame meaning.** A `WallpaperFrame(zoom, focusX, focusY)` is stored without the screen's shape so it survives phones with a different aspect ratio. The focus is the image point (0 to 1 on each axis) that lands at the screen's centre. The zoom is relative to the centre-crop cover scale, `max(screenW / imageW, screenH / imageH)`, so zoom 1 with the focus at the middle is the old `ContentScale.Crop`. That is also what no frame (null) means, so every alarm and default from before framing renders as it did. Maximum zoom is 4. At draw time the focus is pulled in per screen until the visible region stays inside the image, so the image always covers the screen. (`schedule/Model.kt`, `alarm/WallpaperFraming.kt`)
- **Text position.** `TextPosition { TOP, MIDDLE, BOTTOM }`, default MIDDLE, which is where the text was before. The text block (clock, alarm-time line, labels, "With <partner>", the remote notice) sits in a scrolling region above the buttons, so a long label scrolls instead of pushing anything off screen. The Snooze and Stop buttons never move. A locked boot has no wallpaper but places the text, because `AlarmMirror` carries the position. It does not carry the frame.
- **One layout for the ring and the preview.** `RingScreenLayout` draws the ringing screen for both the real ring and the editor's preview, so the preview shows what will ring. The preview is an edge-to-edge dialog with drag and pinch, a Top / Middle / Bottom selector, Reset, Cancel and Save. It opens by itself after a wallpaper pick. An alarm without its own image previews the Settings default, with that default's frame, which can't be changed there, so the text position can still be set. Frame and position are saved with the alarm on Save and discarded on Cancel.
- **Re-pick rule.** Picking the same image content (SHA-256 of the imported file) keeps the frame, so a re-pick to fix a crop doesn't lose it. A different image resets the frame to centred. Removing the wallpaper clears it. The comparison is of the imported file, which is already downscaled, not the photo the picker returned.
- **Each candidate carries its frame.** The resolution order is unchanged; the frame travels with the image it was set for. Own alarms: alarm wallpaper and its frame, else the Settings default and its frame. A frame never applies to an image other than its own.
- **Alarm-time line.** Under the ticking clock the ring shows "6:30 alarm", "6:30 alarm · snoozed 2×", "6:30 and 6:45 alarms" or "6:30, 6:45 and 7:00 alarms". The times are the occurrences that rang, primary first, and not the alarms' current hour and minute, so an alarm edited during the ring still shows when it rang. Members with the same minute are listed once. The line is left out when the ring has no occurrence, which is the fallback ring. (`alarm/AlarmTimeLine.kt`)
- **Snooze count.** `RingState.snoozeCount` (DataStore) rises by one for each snooze, local or remote. Both phones apply the same snooze, so they agree and no Firestore field is needed. Moving the end of an existing snooze is not a new snooze. The count rides in the snooze ledger entry (`until;occ;count`; an old entry reads 0, and a bad count never drops the entry), so a parked ring or a member split out of a merged ring keeps it. A member split out on snooze carries the ring's new count. A fresh ring starts at 0, and a merged ring shows the primary's count.
- **Storage and migration (framing).** Room v6 (`MIGRATION_5_6`) adds the frame (zoom and focus columns) and `textPosition` to alarms, and the same for the joiner's override. Rows from before read as centred and MIDDLE. The Settings default wallpaper's frame lives with the default in the settings DataStore.
- **Framing limits (accepted).** The importer downsizes a wallpaper to about screen size before it is framed, so zooming in upscales it and it softens; the frame doesn't re-read the original photo. With the text at the top and very long labels, the preview's control bar can overlap the text block. The preview's draft is lost on rotation. A re-pick compares the imported file's bytes, so the same photo imported on another phone or build may not match and then resets the frame.

## Toolchain and build

- JDK 21 (installed). The system SDK at `/opt/android-sdk` is root-owned with only android-22 and the deprecated `tools/`; install a user-owned SDK (cmdline-tools, platform 37, build-tools) under `~/Android/Sdk` and point `local.properties` at it, so every worker can run `./gradlew assembleDebug testDebugUnitTest`.
- Gradle wrapper, Kotlin DSL, version catalog, Firebase BoM.
- CI (GitHub Actions): build + unit tests on push; signed release APK attached to GitHub Releases on tag. Distribution = GitHub Releases, sideloaded (Obtainium can auto-update from them).

## Manual prerequisites (Shawn)

1. Create Firebase project; enable Anonymous Auth, Firestore, Cloud Messaging.
2. Switch the project to the Blaze plan (Cloud Functions need it; usage at this scale is within the free tier — set a $1 budget alert).
3. Register the Android app (`me.shawnsquire.tandem`), download its `google-services.json`, and store the contents only as the `GOOGLE_SERVICES_JSON` GitHub Actions secret. Do not copy it into `app/`: the tracked file there is the demo config.
4. Create a release keystore once and store it plus its passwords in Bitwarden "Machine Tokens" per `~/.dotfiles/secrets/README.md`; add them as GitHub Actions secrets. Without a stable key, updates won't install over the previous build.
5. `firebase deploy --only firestore:rules,functions` (Claude prepares; Shawn confirms before deploy).
6. Create GitHub repo (`shawnsquire/tandem-alarm`, private).

## Build order (for /plan)

1. **Scaffold (serial):** SDK install, Gradle project, version catalog, manifest + permissions, package skeleton, Firebase wiring, `firebase/` project, CI. Every later task depends on it.
2. Then largely parallel: `schedule` · `data` · `alarm` + `media` · `firebase/` rules + function · `sync` · `ui`, with `sync` and `ui` consuming the interfaces fixed in step 1.
3. Integration + on-device checklist.
