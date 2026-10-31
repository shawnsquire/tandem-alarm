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
                                               nextOccurrenceAt, skippedOccurrenceAt }],
                                    ringing: { alarmId, ringId, startedAt, snoozedUntil } | null }
households/{hid}/commands/{cid}   { from, to, type, alarmId, occurrenceAt?, ringId?,
                                    createdAt, expiresAt, status: pending|applied|rejected, reason? }
```

Ringtone and wallpaper URIs stay on the device; only display data is mirrored.

**Pairing:** Phone A taps "Pair" → the callable function `createPairCode` creates the household (if A has none) and a pair code, and A shows it (text and QR). Phone B enters/scans it → the callable `joinHousehold` runs a transaction that adds B to `members` and deletes the code. The callable `unpair` removes the household and both `users/{uid}` entries. Clients write none of these documents; `users/{uid}` is the index that lets a function find a caller's household and stops a double tap creating two.

**Auth and rules:** Firebase Anonymous Auth, one uid per install. Rules: only `members` read household data; a device writes only its own device doc; commands may be created only with `from == auth.uid` and `to` = the other member; only the `to` device may update `status`; `households`, `users` and `pairCodes` are not client-writable, and `pairCodes` is not client-readable; the pairing functions enforce that codes are single-use and rejected after `expiresAt`, and that a household holds at most two members. A user can read only their own `users/{uid}`.

## Key flows

- **Partner skips:** B taps Skip on A's 06:30 → command `{SKIP_NEXT, alarmId, occurrenceAt: <the 06:30 epoch B saw>}` → Function sends FCM → A's `CommandApplier` checks the occurrence still matches and is in the future → sets `skippedOccurrenceAt`, reschedules, marks applied, republishes, notifies A. B's UI shows "pending" until applied, "rejected: already rang" otherwise.
- **Skip expiry:** once now > `skippedOccurrenceAt`, `nextOccurrence` ignores it and the field is cleared on next reschedule. The alarm reactivates with no extra job.
- **Fire:** `AlarmReceiver` starts `RingingService` immediately (foreground, silent full-screen UI), runs `PreFireCheck` with a 3 s cap, then either rings or quietly finishes (and publishes "skipped by partner"). Publishes `ringing`.
- **Remote stop:** B taps Stop while A rings → command with A's current `ringId` → FCM and the ringing service's live listener both deliver → dismiss.
- **Errors:** rejected commands show their reason on B. Firestore offline writes queue automatically. Missing ringtone file → default alarm sound. Missing wallpaper → default/plain.

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
