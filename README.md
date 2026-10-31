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

Ports: auth 9099, Firestore 8080, functions 5001.

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

This runs one test, `TwoDeviceSmokeTest`. It uses two named FirebaseApps in one process to act as two devices: pair, the partner sees the alarm, SKIP_NEXT through the pre-fire pull, a stale skip is rejected, STOP_RINGING by ring ID, unpair. It grants the local network permission itself. CI does not run it.

## Setup before first use

These are one-time steps. Details for the backend are in [docs/deploy.md](docs/deploy.md).

1. Create a Firebase project. Enable Anonymous Auth, Firestore and Cloud Messaging.
2. Switch the project to the Blaze plan. Cloud Functions need it. Usage at this scale stays in the free tier; set a $1 budget alert.
3. Register the Android app `me.shawnsquire.tandem` and download `google-services.json` into `app/`. It is not a secret, but keep it out of the repo if the repo is public.
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

Run on both phones. Alarms are the point of the app, so do not skip the reboot and Doze items.

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

## Known limitations

- If you delete an alarm in the first moments after the app starts and then tap Undo, the restored alarm may use the default sound and wallpaper. The startup cleanup of unused ringtone and wallpaper files can already have removed that alarm's custom files. The cleanup spares files under one hour old, so this only affects older files.
- Right after the first unlock, the app replays changes that were made while the phone was locked. An alarm that fires during that replay waits up to 2 seconds to register as ringing, then rings regardless. A partner skip or stop sent in that moment may not take effect.
- If the alarm list can't be read when alarms are re-armed (for example after a reboot), each alarm is re-armed for its recorded time from a small record. A repeating alarm armed this way rings that once, and its next occurrence isn't armed until a later run that can read the alarm list, normally the next app start after unlock.
- Daylight saving: an alarm set for a time that does not exist (02:30 on the spring-forward night) rings at 03:30. An alarm set for a time that happens twice rings at the first one.
