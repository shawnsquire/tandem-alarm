# Tandem Alarm — implementation plan

## Context

Shawn wants an Android alarm clock where he and his wife pair phones, see each other's alarms (set / skipped / ringing), and skip, turn off, or stop the other's alarm. The design is agreed and committed at `~/documents/apps/tandem-alarm/docs/design/2026-10-31-tandem-alarm.md` (commit `07ce79a`). That doc is the spec. In particular, invariants **I1–I3** go verbatim into every brief. This plan splits the build into units that herdr workers run in parallel. Each worker runs `/sdd` over its own task list, and this session is the `/orchestrate` manager.

Greenfield repo: nothing to reuse except the design doc. Toolchain: JDK 21 is installed. `/opt/android-sdk` is root-owned and has only android-22, so a user SDK is needed. The `firebase`, `gh`, and Node 24 CLIs are present. Memory is tight: 15 GB available, with 26 GB of swap already in use.

## Run setup (manager, before any worker)

1. `gh repo create shawnsquire/tandem-alarm --private --source ~/documents/apps/tandem-alarm --push`. Add `.claude-brief.md` to `.gitignore`.
2. Copy this plan into the repo as `docs/plans/2026-10-31-tandem-alarm.md` and commit it (`docs: Add implementation plan`).
3. Create the run folder `~/documents/apps/tandem-alarm-runs/`, holding `plan.md` (units, status log) and `workers.md` (worker table).
4. Concurrency: at most **3 workers at once**, and at most 2 running Gradle. Run `free -g` before each spawn. Every worker sets `org.gradle.jvmargs=-Xmx1536m` and `kotlin.daemon.jvmargs=-Xmx1g`, and runs `./gradlew --stop` when it is done.
5. Merge policy (approved): workers push their branch and run `gh pr create`. They never merge. The manager runs a spec check, then a quality review (Opus) on PRs that touch rules, auth, or functions, and then merges with `gh pr merge --squash`. Commits use the style `topic: message`.

## Shared contract (created in U0, frozen afterwards)

Package `me.shawnsquire.tandem`. U0 creates these signatures, with fakes, so that wave A and wave B compile independently. Changing a signature after U0 counts as BLOCKED, and the worker reports it to the manager.

- `schedule/Model.kt`
  - `Alarm(id, label, hour, minute, days: Set<DayOfWeek>, enabled, skippedOccurrenceAt: Instant?, ringtoneUri: String?, wallpaperPath: String?)`
  - `RingState(alarmId, ringId, startedAt, snoozedUntil: Instant?)`
  - `CommandType {SKIP_NEXT, UNSKIP, DISABLE, ENABLE, STOP_RINGING}`
  - `Command(id, from, to, type, alarmId, occurrenceAt: Instant?, ringId: String?, createdAt, expiresAt)`
  - `sealed CommandDecision { Apply; Reject(reason: String) }`
- `schedule/Scheduling.kt`
  - `nextOccurrence(alarm, now, zone): Instant?`
  - `validate(cmd, alarm: Alarm?, ring: RingState?, now): CommandDecision`
  - Both are stubs in U0.
- `data/`
  - `AlarmRepository` with `alarms: Flow<List<Alarm>>`, `get`, `upsert`, `delete`, `setEnabled`, `setSkip(id, Instant?)`.
  - `RingStateStore` with `state: Flow<RingState?>` and `set(RingState?)`.
  - `SettingsRepository` holding snooze minutes, default wallpaper path, and default ringtone URI.
- `alarm/`
  - `AlarmScheduler.rescheduleAll()`
  - `RingingController.dismiss(ringId)` and `RingingController.snooze()`
- `sync/`
  - `PreFireCheck.pullAndApply(timeout: Duration): Boolean`. It returns whether the pull succeeded. Callers then re-read local state, which enforces I1.
  - `CommandApplier.apply(cmd)`
  - `CommandSender.send(type, alarmId, occurrenceAt?, ringId?)`
  - `PartnerRepository.partner: Flow<PartnerState?>`
  - `PairingRepository` with `createCode`, `join(code)`, and `unpair`.
  - `StatePublisher.start()`
- `AppContainer` (in `Application`) wires fakes. Each unit swaps in only its own implementations, at clearly marked lines.

## Units

### U0 — Scaffold (serial; one worker; branch `feat/scaffold`)
1. Install a user SDK at `~/Android/Sdk` (cmdline-tools latest, `platforms;android-37`, `build-tools;37.0.0`, `platform-tools`). Write `local.properties` (git-ignored).
2. Gradle wrapper, Kotlin DSL, `gradle/libs.versions.toml`. The `:app` module uses compileSdk/targetSdk 37 and minSdk 33. Dependencies: Compose M3 + Navigation, Room (KSP), DataStore, WorkManager, coroutines, Firebase BoM (auth, firestore, messaging, functions), JUnit4, Robolectric, Turbine. Also `.gitignore`.
3. Manifest:
   - Permissions: `USE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` plus the type permission, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `VIBRATE`, `WAKE_LOCK`.
   - Register empty components: AlarmReceiver, BootReceiver, RingingService, RingingActivity, TandemMessagingService.
   - **Verify the foreground-service type for alarm playback on API 37 against developer.android.com/Context7 and cite the source in the PR.**
4. Shared contract above, with fakes. Add a Compose shell with three tabs (Alarms / Partner / Settings), each showing placeholder text.
5. Firebase dev setup on the emulator only:
   - A demo `app/google-services.json` with project `demo-tandem`.
   - Debug `BuildConfig.USE_EMULATOR` connects Auth, Firestore, and Functions to the emulator host.
   - `firebase/` contains `firebase.json` (auth, firestore, and functions emulators), `.firebaserc` set to `demo-tandem`, a deny-all `firestore.rules`, and a TypeScript `functions/` skeleton with a Jest + `@firebase/rules-unit-testing` harness and `npm test` run under `firebase emulators:exec`.
6. `.github/workflows/ci.yml` with two jobs: `android` (`testDebugUnitTest assembleDebug`) and `firebase` (`npm ci && npm test` in `firebase/functions`).
- **Done when:** `./gradlew testDebugUnitTest assembleDebug` is green locally, `npm test` is green, and CI is green on the PR.

### Wave A (parallel, after U0 merges)

**U1 — Core logic + persistence** (`feat/core`; Gradle)
1. `nextOccurrence`, test-first. Tests cover:
   - weekday rollover
   - one-shot alarms (no days)
   - a disabled alarm returns `null`
   - skip moves to the following occurrence, and a skip that has passed is ignored
   - DST: fall-back on 2026-11-01, where 01:30 occurs twice, so take the first; spring-forward on 2027-03-14, where 02:30 doesn't exist, so ring at 03:00
2. `validate`, test-first:
   - SKIP_NEXT, UNSKIP, and DISABLE reject when `occurrenceAt` ≠ the current next occurrence, or when it is in the past
   - STOP_RINGING rejects when `ringId` mismatches or nothing is ringing
   - any command rejects if expired
   - ENABLE is always valid while the alarm exists
   - an unknown alarm is rejected
3. Room entities, DAO, and `AlarmRepositoryImpl`, plus `RingStateStore` (DataStore). When the alarm list is read, clear any `skippedOccurrenceAt` that has passed. Robolectric tests.
4. `SettingsRepository` on DataStore, with tests.

**U2 — Firebase backend** (`feat/backend`; Node only, light)
1. Callable functions, using the Firestore paths and shapes from the design doc:
   - `createPairCode` makes the household if needed and a 6-char code with a 10-minute TTL.
   - `joinHousehold(code)` runs as a transaction: the code must be valid and unused, and the household must have fewer than 2 members. It adds the caller and deletes the code.
   - `unpair` deletes the household subtree.
2. `onCommandCreated` (Firestore trigger) sends a high-priority FCM data message to the `to` device's `fcmToken`. It skips silently if there is no token.
3. `firestore.rules`:
   - Only members read the household.
   - A device writes only `devices/{auth.uid}`.
   - A command create requires `from == auth.uid`, `to` = the other member, and `status == 'pending'`.
   - Only `to` may update a command, and then only `status`/`reason`.
   - `pairCodes` is closed to clients.
4. Emulator tests for every rule above and for each function, with messaging stubbed. Include spoofed `from`, a non-member read, an expired code, a reused code, and a third member.

**U3 — Alarm engine + media** (`feat/alarm-engine`; Gradle)
1. `AlarmSchedulerImpl`: one `setAlarmClock` per enabled alarm's next occurrence. The show-intent opens the app. Cancel stale alarms. Robolectric test with `ShadowAlarmManager`.
2. `AlarmReceiver` starts `RingingService` in the foreground right away. `BootReceiver` (boot, time set, timezone changed) calls `rescheduleAll`.
3. `RingingService`:
   - Show the full-screen notification and activity silently.
   - Call `PreFireCheck.pullAndApply(3s)`, then re-read the alarm. If it is now skipped or disabled for this occurrence, finish quietly.
   - Otherwise play the sound with `USAGE_ALARM` and vibrate. If the ringtone URI is unreadable, fall back to the default alarm sound.
   - Generate a `ringId`, write `RingStateStore`, and implement `RingingController` (dismiss, snooze using `SettingsRepository` minutes, auto-silence after 10 min).
   - After dismissal, turn off one-shot alarms and reschedule.
   - Unit-test the state machine with fakes. **I1 test:** a PreFireCheck that times out or throws still results in ringing.
4. `RingingActivity`: full-screen, shown over the lock screen. It shows the alarm wallpaper, falling back to the default wallpaper and then to plain. It shows the time and label and has large Stop and Snooze buttons.
5. `media/`:
   - A ringtone picker contract (system `ACTION_RINGTONE_PICKER` plus file via `ACTION_OPEN_DOCUMENT` with `takePersistableUriPermission`).
   - A wallpaper import: photo picker, then copy into `filesDir/wallpapers/` downscaled to the screen size.

### Wave B (parallel, after wave A merges)

**U4 — Sync client** (`feat/sync`; Gradle)
1. `AuthManager` (anonymous sign-in at startup).
2. `PairingRepository`, which calls the U2 functions.
3. `StatePublisher`:
   - Observe alarms and ring state, and write the own device doc: display fields, `nextOccurrenceAt`, `skippedOccurrenceAt`, `ringing`.
   - Never write ringtone or wallpaper data.
4. `PartnerRepository`, a snapshot listener on the partner's device doc. Expose `lastSeen`.
5. `CommandSender`: fill `occurrenceAt` from the partner's mirrored `nextOccurrenceAt`, set expiry to 24 h, and expose the command's status as a Flow.
6. `CommandApplierImpl`:
   - Run `validate` and dedupe by command ID.
   - Apply through the repository, scheduler, and `RingingController`.
   - Set the status to applied or rejected, with a reason.
   - Post the "Partner skipped your 06:30" notification.
7. `TandemMessagingService`: upload the token, record the last-FCM timestamp, and apply the command from the data payload.
8. `PreFireCheckImpl`: fetch pending commands addressed to me, under a timeout. `SyncWorker` does the same every 15 min. `RingingService` also attaches a commands listener while ringing.
- Tests: CommandApplier with fakes (applied, rejected-stale, duplicate). PreFireCheck timeout with a fake that never returns.

**U5 — UI** (`feat/ui`; Gradle; runs in parallel with U4 on top of the contract fakes)
1. Alarm list: time, days, an on/off switch, a "Skipped — next 07:00 Tue" chip, and a Skip/Unskip action.
2. Alarm edit: time picker, weekday chips, label, ringtone (U3 contract), wallpaper (U3 import), delete.
3. Partner tab:
   - Unpaired: show the pair flow (show code / enter code).
   - Paired: the partner's alarms with their state, plus a ringing banner with **Stop**.
   - Per-alarm actions Skip next / Unskip / Turn off / Turn on, each showing the command status (pending / applied / rejected reason).
   - Last-seen time.
4. Settings: snooze minutes, default wallpaper, default ringtone, unpair.
5. Health check: exact alarm, full-screen intent, notifications, battery optimization, FCM token present, last FCM received, partner last seen. Each row has a fix button that deep-links to the relevant system settings screen.
6. A ViewModel per screen, with ViewModel tests against the fakes. Add Compose previews.

### U6 — Integration + release (serial; one worker, after wave B; `feat/integration`)
1. Remove all fakes from `AppContainer`. Confirm the app runs end to end against the emulator suite on one AVD with two app installs (debug and a `.partner` debug flavor or applicationIdSuffix), as two "devices".
2. Instrumented smoke test: create an alarm, the partner skips it, the owner's next occurrence moves.
3. Release signing from env/CI secrets. Add `release.yml`: on tag `v*`, build a signed APK and attach it to the GitHub Release.
4. `README.md`:
   - setup
   - the manual prerequisites from the design doc
   - the on-device checklist (Doze `adb shell dumpsys deviceidle force-idle`, offline, DND, reboot, remote skip while dozing, remote stop while ringing)

## Manager-only steps (each needs Shawn's go-ahead at the time)
- Shawn creates the real Firebase project (Blaze, anonymous auth, Firestore, FCM) and supplies `google-services.json`. It goes in a CI secret, and only the demo one is committed.
- Release keystore: hand Shawn the `keytool` command and an `rbw add` command per `~/.dotfiles/secrets/README.md`. He adds the GitHub secrets.
- `firebase deploy --only firestore:rules,functions` to the real project, and tagging `v0.1.0`. Both only after Shawn confirms.

## Verification
- Per PR: CI green (Android unit tests + assemble, Firebase emulator tests). The worker reports the exact commands it ran.
- After U6: `./gradlew testDebugUnitTest connectedDebugAndroidTest` on an AVD while `firebase emulators:start` is running, and a manual run of the two-install flow.
- Final: Shawn installs the release APK on both phones and works through the README on-device checklist. The health check is all green on both phones, including the non-Pixel one.
