# Tandem Alarm

An Android alarm clock for two people. Each phone keeps its own alarms and rings them locally. The paired phone can see them and skip, turn off, turn on, or stop one that is ringing. It cannot create or edit alarm times.

Typical use: whoever gets up first skips the other's morning alarm, or silences it once their own has gone off.

The design is in [docs/design/2026-10-31-tandem-alarm.md](docs/design/2026-10-31-tandem-alarm.md). Backend deploy steps are in [docs/deploy.md](docs/deploy.md).

## How pairing works

1. On phone A, open the Partner tab and tap Show pairing code. A Cloud Function (`createPairCode`) creates a household and a 6-character code, valid for 10 minutes and usable once. The app shows it as text and a QR code.
2. On phone B, open the Partner tab, tap Enter code, enter the code and tap Join. `joinHousehold` adds B to the household. A household holds two people at most.
3. Both phones now read each other's alarm list from Firestore. Unpairing (`unpair`) removes the household for both.

Each phone signs in anonymously, so there are no accounts. A phone writes only its own alarms. A partner action is a command document that the owner validates and applies. Commands name the exact ring time or ring they were issued against and expire after 24 hours, so a skip sent late never skips the next day's alarm.

Alarms ring without the network. Before ringing, the phone asks Firestore for pending commands, but gives up after 3 seconds and rings.

## Build and test

Needs JDK 21 and an Android SDK with platform 37.

```
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Release builds are unsigned unless the signing variables below are set.

Firebase rules and function tests (they start the emulators themselves):

```
cd firebase/functions
npm ci
npm test
```

### Running against the emulators

Debug builds talk to the Firebase emulators, not the real project.

Start the emulators. Use the npm `firebase-tools` on Node 22.12 or newer. A system-wide firebase binary that bundles Node 20 cannot load the functions.

```
cd firebase/functions && npm ci && npm run build
cd ..
npx firebase-tools emulators:start --project demo-tandem
```

Ports: auth 9099, Firestore 8080, functions 5001, Storage 9199. The same `emulators:start` command starts Storage too. Joined alarms ship the owner's photo and ringtone through it.

Debug builds reach the host at `10.0.2.2`, the Android emulator's alias for it. For a physical phone on the same network, build with the host's LAN address:

```
./gradlew installDebug -Ptandem.emulatorHost=<ip>
```

Android 17 (API 37) requires the runtime permission `android.permission.ACCESS_LOCAL_NETWORK` for apps targeting it to reach private addresses such as `10.0.2.2`. Debug builds declare it but do not ask for it, so grant it by hand after installing. See the [Android docs](https://developer.android.com/privacy-and-security/local-network-permission). Release builds talk to public Firebase hosts and do not need it.

```
adb shell pm grant me.shawnsquire.tandem android.permission.ACCESS_LOCAL_NETWORK
```

### Android emulator

```
sdkmanager "system-images;android-37.0;google_apis;x86_64"
avdmanager create avd -n tandem37 -k "system-images;android-37.0;google_apis;x86_64" -d pixel_7
emulator -avd tandem37 -no-window -no-audio -no-snapshot -no-boot-anim -memory 2048 -gpu swiftshader_indirect
```

`sdkmanager` and `avdmanager` need `JAVA_HOME` set to a JDK 21. If your shell exports `ANDROID_SDK_ROOT` or `ANDROID_HOME` for a different SDK than the one holding the image, point both at the right one, or the emulator fails with "Broken AVD system path".

### Two installs on one device

The `partner` build type installs as `me.shawnsquire.tandem.partner`, with the same debug and emulator setup, so one device or emulator can play both people. Both show the launcher label "Tandem Alarm".

```
./gradlew installDebug installPartner
adb shell pm grant me.shawnsquire.tandem.partner android.permission.ACCESS_LOCAL_NETWORK
```

### Instrumented smoke test

With the Firebase emulators and an Android emulator running:

```
./gradlew connectedDebugAndroidTest
```

This runs `TwoDeviceSmokeTest`. It uses two named FirebaseApps in one process to act as two devices, each with its own Room, DataStores and files, and needs all four emulators, Storage included. Its cases:

- Pair, the partner sees the alarm, SKIP_NEXT through the pre-fire pull, a stale skip is rejected, STOP_RINGING by ring ID, unpair.
- Join, then both phones ring. B snoozes and A snoozes to the same time. A dismisses, B's ring ends, and B sends nothing back.
- Invite, then accept.
- The owner skips "just me": B still rings, and B's stop is rejected on A as "skipped here".
- The owner turns the alarm off: B's copy is off and B gets the turn-off notification.
- The owner's photo and sound are uploaded, downloaded on B, and resolved for B's ring.

Rings go through the real ringing controller and the ring mirror, without sound, notifications or the ringing service. It grants the local network permission itself. CI does not run it.

## Setup before first use

These are one-time steps. Details for the backend are in [docs/deploy.md](docs/deploy.md).

1. Create a Firebase project. Enable Anonymous Auth, Firestore and Cloud Messaging.
2. Switch the project to the Blaze plan. Cloud Functions need it. Usage at this scale stays in the free tier; set a $1 budget alert.
3. Register the Android app `me.shawnsquire.tandem` and download its `google-services.json`, and store the contents only as the `GOOGLE_SERVICES_JSON` GitHub Actions secret. Do not copy it into `app/`: the tracked `app/google-services.json` is the demo config that debug, partner and the emulators use. The release workflow writes the prod file at build time.
4. Create a release keystore once, keep it and its passwords in a password manager, and add them as GitHub Actions secrets (below). Without a stable key, updates will not install over the previous build.
5. Deploy the rules and functions: `firebase deploy --project prod --only firestore:rules,functions`, run from `firebase/`. The `prod` alias maps to `tandem-alarm` in `firebase/.firebaserc`.
6. Create the GitHub repo, `shawnsquire/tandem-alarm`.

## Releases and installing

Pushing a tag that starts with `v` runs `.github/workflows/release.yml`. It builds a signed, minified release and attaches `tandem-<tag>.apk` to a GitHub Release. The version name is the tag without the `v`. The version code is the workflow run number.

Required repository secrets:

- `TANDEM_KEYSTORE_B64`: the `.jks` file, base64-encoded
- `TANDEM_KEYSTORE_PASSWORD`
- `TANDEM_KEY_ALIAS`
- `TANDEM_KEY_PASSWORD`
- `GOOGLE_SERVICES_JSON`: the production `google-services.json` for `me.shawnsquire.tandem`

To build a signed APK locally, set `TANDEM_KEYSTORE_PATH`, `TANDEM_KEYSTORE_PASSWORD`, `TANDEM_KEY_ALIAS` and `TANDEM_KEY_PASSWORD`, and optionally `TANDEM_VERSION_NAME` and `TANDEM_VERSION_CODE`, then run `./gradlew assembleRelease`.

To install, download the APK from the [Releases page](https://github.com/shawnsquire/tandem-alarm/releases) and open it on the phone. Or add `https://github.com/shawnsquire/tandem-alarm` to [Obtainium](https://obtainium.imranr.dev/), which tracks GitHub Releases and installs new versions. Updates must be signed with the same key as the installed app, or Android refuses them.

After installing, open Settings, then Health check, and fix anything that is not green (exact alarms, full-screen notifications, notification permission, battery optimization).

## On-device checklist

Run on both phones. The linked-alarm items need two phones paired with each other, with phone A's alarm joined from phone B. Alarms are the point of the app, so do not skip the reboot and Doze items.

- [ ] Health check (under Settings) shows all green.
- [ ] An alarm rings with the screen off.
- [ ] An alarm rings in Doze. Run `adb shell dumpsys deviceidle force-idle`, wait for the alarm, then `adb shell dumpsys deviceidle unforce`.
- [ ] An alarm rings with the phone offline (airplane mode).
- [ ] An alarm rings through Do Not Disturb.
- [ ] An alarm rings after a reboot without unlocking the phone.
- [ ] After that reboot and the first unlock, `adb logcat` or the app shows startup completed (USER_UNLOCKED fired after the locked boot) and the periodic sync is scheduled (WorkManager).
- [ ] After installing an update over the old build, the alarms are still armed (MY_PACKAGE_REPLACED). Check with `adb shell dumpsys alarm | grep me.shawnsquire.tandem`.
- [ ] Skip from the partner while the target phone is dozing: the alarm does not ring.
- [ ] Stop from the partner while the target phone rings: it goes quiet.
- [ ] Snooze an alarm, then stop it from the partner: the pending snooze is cancelled.
- [ ] A one-shot alarm turns itself off after it rings.
- [ ] A custom ringtone file plays, and the per-alarm wallpaper shows on the lock screen.
- [ ] Merged rings at the same minute: set an alarm on B for a minute from now and join A's alarm for the same minute. Both phones ring. On B, the ringing screen lists both labels and one sound plays. A rings its one alarm. Dismiss on B: A's alarm stops too.
- [ ] Mirrored dismiss while the other phone dozes: force-idle phone A (`adb shell dumpsys deviceidle force-idle`), ring the joined alarm, dismiss it on B. A goes quiet. A shows "B dismissed" briefly, then closes. A sends nothing back.
- [ ] Both phones offline (airplane mode): the joined alarm still rings on both at the owner's time. Dismiss on B, then turn the network back on. A keeps ringing until it is dismissed locally or auto-silences after 10 minutes. On reconnect, B's queued stop ends A's ring if it is still sounding. If A's ring has already ended, the stop is rejected as already resolved.
- [ ] Reboot before unlock: reboot phone B and leave it locked. The joined alarm rings. Dismiss it, then unlock. B sends the dismiss after unlock and A's ring stops, or the command is rejected as stale.
- [ ] An 11 pm skip with the joiner dozing: the night before, set a 6:30 alarm on A and join it from B. At 11 pm skip it for both of us on A, then force-idle B. In the morning B does not ring. The pre-fire read caught the skip. If the read fails or takes more than 3 seconds, B rings.
- [ ] Notify mode: set the link on B to Notify. The alarm shows a heads-up notification with Snooze and Stop, plays the channel's sound once and does not open the ringing screen. Stop on B stops A. It goes quiet by itself after 10 minutes.
- [ ] Notify heads-up while locked and while unlocked: while unlocked, the notification appears as a heads-up pop-up. While locked, it sits on the lock screen and never launches the ringing screen. Android shows the pop-up only on an unlocked phone.
- [ ] Notify channel set to Silent: in system settings, set the "Linked alarm notifications" channel to Silent. The linked alarm rings in full, since a Notify alarm that cannot alert rings.
- [ ] A Ring alarm merging into a Notify ring: while a Notify ring is sounding on B, let one of B's own Ring alarms fire. B switches to the full ringing screen and sound, with both labels listed.
- [ ] Three alarms 5 minutes apart: set three alarms on one phone at X, X+5 and X+10 minutes. Own and joined alarms both merge into one ring. Each alarm gets 10 minutes counted from when it joined, so the ring ends 10 minutes after the last one.
- [ ] Relabel without relaunch: while a ring is sounding, let another alarm merge in. The labels and notification update, and the ringing screen does not relaunch or flicker.

## Known limitations

- If you delete an alarm in the first moments after the app starts and then tap Undo, the restored alarm may use the default sound and wallpaper. The startup cleanup of unused ringtone and wallpaper files can already have removed that alarm's custom files. The cleanup spares files under one hour old, so this only affects older files.
- Right after the first unlock, the app replays changes that were made while the phone was locked. An alarm that fires during that replay waits up to 2 seconds to claim the ring slot. If the claim times out, the alarm rings regardless, and a partner's skip or turn-off may show as applied while the alarm still rings. Stop from the partner won't reach that ring.
- If the alarm list can't be read when alarms are re-armed (for example after a reboot), each alarm is re-armed for its recorded time from a small record. A repeating alarm armed this way rings that once, and its next occurrence isn't armed until a later run that can read the alarm list, normally the next app start after unlock.
- Daylight saving: an alarm set for a time that does not exist (02:30 on the spring-forward night) rings at 03:30. An alarm set for a time that happens twice rings at the first one.
- A merged ring that is snoozed shows one snoozed notification for the whole group. If the group record fails to save and the process dies before a later save succeeds, a stale record can make one Dismiss end several snoozes. This needs a SharedPreferences write failure.
- If a member joins a Notify ring in the instant after a late member re-arms auto-silence, before the ring is first re-read, that member's alarm joins quietly: its label shows, but it gets no heads-up or chime of its own.
- If a snooze claim fails or times out for a merged ring's member other than its first, the group's "Snoozed until" notification stays up (it is ongoing, with a Dismiss button) while the alarm rings. Tap Dismiss, or it clears when the group's first member resumes or the next cleanup runs.
