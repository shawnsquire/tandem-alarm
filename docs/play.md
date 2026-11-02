# Shipping Tandem Alarm through Google Play (closed testing)

Tandem Alarm moves to Play as a new app, `com.kautiontape.tandem`. The old sideloaded app was `me.shawnsquire.tandem`. Android treats them as two different apps, so phones don't update from one to the other. Each phone moves over once (step 8).

## The release gate

From v0.5.0 on, a tag builds `com.kautiontape.tandem`, not the old app.

- **Register the new id in Firebase first (step 1).** Until `GOOGLE_SERVICES_JSON` holds a client for `com.kautiontape.tandem`, the release build fails: the google-services plugin finds no client for the package.
- **New app: unpair in the old app and uninstall it first.** If both are installed, both ring.
- **Obtainium:** an Obtainium entry for this repo is tied to the old id. Obtainium refuses the new APK ("Downloaded package ID does not match existing app ID", [Obtainium](https://github.com/ImranR98/Obtainium/blob/35c3e7fea4f341a716eca4bb3fc625319ce965ab/assets/translations/en.json#L48)), so it never installs the two side by side. Delete the entry in Obtainium, unpair and uninstall the old app, then add `https://github.com/shawnsquire/tandem-alarm` again, or install from Play instead. Obtainium only looks at `.apk`, `.xapk`, `.apkm` and `.apks` assets ([Obtainium](https://github.com/ImranR98/Obtainium/blob/35c3e7fea4f341a716eca4bb3fc625319ce965ab/lib/services/apk_filter_service.dart#L10-L15)), so the `.aab` on each release doesn't confuse it.

## One-time setup

### 1. Firebase

1. Firebase console, project `tandem-alarm`, Project settings, Add app, Android. Package name `com.kautiontape.tandem`, nickname "Tandem Alarm (Play)".
2. Skip the SHA-1/SHA-256 step. Firebase asks for it only for Google Sign-In, phone auth and App Check (Play Integrity). This app uses none of them. The setup pages for anonymous auth, Firestore, Cloud Messaging and Storage have no SHA step, so this is inferred from the docs leaving it out ([anonymous auth](https://firebase.google.com/docs/auth/android/anonymous-auth), [SHA fingerprints](https://developers.google.com/android/guides/client-auth)). If you add App Check later, register the **app signing key** SHA-256 from Play's App signing page.
3. Download `google-services.json` and replace the GitHub secret with its contents:
   ```
   gh secret set GOOGLE_SERVICES_JSON < google-services.json
   ```
   Then delete the file. Don't copy it into `app/`: the tracked `app/google-services.json` is the demo config.
4. Auth, Firestore, Functions, Cloud Messaging and Storage are project-wide, so nothing else changes. Keep the old `me.shawnsquire.tandem` app registered until both phones have moved. The old app needs it to unpair.

### 2. Play Console: create the app and give Play the existing key

The plan: the existing release key (the one in `TANDEM_KEYSTORE_B64`) is both Play's **app signing key** and the **upload key**. Then a GitHub APK and a Play install carry the same certificate and update each other.

The cost: Google recommends two different keys ("For maximum security, your upload key and app signing key should be different", [Play help](https://support.google.com/googleplay/android-developer/answer/9842756)). Play can reset a lost or leaked upload key. It can't do that for an app signing key. With one key, a leak of the GitHub secret leaks the app signing key itself.

**Check the key before anything else.** Get the `.jks` from your password manager, then:

```
keytool -list -v -keystore tandem.jks -alias <alias>
```

`<alias>` is the value of the `TANDEM_KEY_ALIAS` secret. Read three lines:

- `Subject Public Key Algorithm:` must be `2048-bit RSA key` or larger. Play: "Custom keys must be RSA 2048-bit or higher" ([Play help](https://support.google.com/googleplay/android-developer/answer/9842756)).
- `Signature algorithm name:` should be an RSA one, for example `SHA256withRSA`.
- `Valid from: … until:` the end date must be after 22 October 2033. "If you plan to publish your apps on Google Play, the key you use to sign your app must have a validity period ending after 22 October 2033" ([Android docs](https://developer.android.com/studio/publish/app-signing)).

Also copy the `SHA256:` fingerprint; you check it against Play below.

**If any of these fails, stop.** The same-key plan doesn't work with this key. Don't create a new key or upload anything; tell the manager.

Then:

1. [play.google.com/console](https://play.google.com/console), **Create app**. Name "Tandem Alarm", default language English (United States), App, Free. Accept the declarations.
2. **Before you upload any app bundle, set the signing key.** Play enrolls a new app in "quantum-ready, hybrid signing with Google-generated keys" by default, and the first upload uses it. Play says you can change this "before a release is rolled out in the open testing or production track" ([Play help](https://support.google.com/googleplay/android-developer/answer/9842756)). Don't rely on that window. A phone that installed a build signed with Google's key won't take an update signed with yours.
3. Test and release, Setup (or App integrity), App signing. Click **Change key**, then **Provide a copy of your app signing key**.
4. Follow the PEPK steps the Console shows. It links `pepk.jar` and an encryption public key (download both), and shows a command like this. Copy the Console's own command, since it fills in the key path:
   ```
   java -jar pepk.jar --keystore=tandem.jks --alias=<alias> --output=output.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
   ```
   It asks for the keystore password and the key password (`TANDEM_KEYSTORE_PASSWORD` and `TANDEM_KEY_PASSWORD`). Upload `output.zip`.
5. **Don't create or register a separate upload key.** If the dialog offers one as an optional step, skip it. "If you do not generate a new upload key, you continue to use your app signing key as your upload key to sign each release" ([Android docs](https://developer.android.com/studio/publish/app-signing)). The release workflow signs the AAB with the same key and fails if the AAB and APK certificates differ.
6. Delete `output.zip` and the local `.jks` copy.
7. **Check the result.** The App signing page should list one app signing key, and its SHA-256 must equal the `keytool` fingerprint. If it shows a different SHA-256, or extra keys (an ML-DSA or "quantum-ready" key, or a second classical key), stop and tell the manager. Play's help doesn't say what hybrid signing does when you supply your own key, and Android 17 checks the newer signature block. That decides whether Play and GitHub builds update each other.

### 3. App content

Policy and programs, App content. Answer each item:

- **Privacy policy:** `https://tandem.kautiontape.com/privacy/`
  The site (the privacy page, with its `#deleting-your-data` section) must be deployed and load before you submit for review. It is published separately from this repo.
- **Ads:** No, the app has no ads.
- **App access:** All functionality is available without special access. In the instructions box, add: "No login. Pairing needs a second phone with the app; the app works as a normal alarm clock without one."
- **Target audience:** 18 and over only. Any group under 13 brings in the Families policy.
- **Content rating:** category Utility, Productivity, Communication, or Other. No violence, sexual content, language, controlled substances or gambling. "Do users interact or exchange content?": **Yes**. Paired users exchange photos, sounds and alarm labels. "Shares the user's current location": No. "Digital purchases": No.
- **News app:** No. **Government app:** No. **Financial features:** None. **Health:** No.
- **Data safety:** see [Data safety](#data-safety) below.
- **Declarations:** see [Permission declarations](#permission-declarations) below.

#### Data safety

What leaves the phone, from the code. "Collected" means sent off the phone. Every transfer goes to Firebase (Google), which Play counts as a service provider, or to the paired phone.

| Data | Play type | What exactly | Collected / shared | Who else sees it | Required / optional | Purpose | Deleted when |
|---|---|---|---|---|---|---|---|
| Firebase Auth user ID | Device or other IDs | A random anonymous ID made by sign-in at every app start, paired or not (`TandemApp.kt:139`, `AuthManager.kt:43-47`). It appears in `users/{uid}`, the household's `members`, the device doc path, commands (`from`, `to`) and Storage paths. | Collected. Not shared. | The paired phone reads it in `members` and commands. The app never shows it. | Required | App functionality | **Never by the app.** Unpair leaves the Auth record. It stays in Firebase Authentication until you delete it in the console. Uninstalling loses the ID on the phone (`allowBackup="false"`), so a reinstall gets a new one and the old record is orphaned. |
| FCM registration token | Device or other IDs | Fetched at every start (`TandemApp.kt:149`). Written to the device doc's `fcmToken` only while paired. The Functions SDK also sends it with each callable call. | Collected. Not shared. | The paired phone can read the device doc, so it can read the token. The app doesn't use or show it there. | Required | App functionality (partner commands arrive by push) | The device-doc copy goes on unpair. FCM's own registration lasts until the app is uninstalled. |
| Firebase installation ID (FID) | Device or other IDs | Generated by Firebase Installations, which Cloud Messaging pulls in. | Collected. Not shared. | Nobody | Required | App functionality | Not deleted by the app. |
| IP address, user agent, Firebase app ID | Not a Play data type | Firebase Auth "collects IP addresses to provide added security and prevent abuse"; Functions collects the caller's IP on each call; the SDKs send a user agent and app ID ([Firebase](https://firebase.google.com/docs/android/play-data-disclosure)). Nothing derives a location from the IP. | Collected by Firebase | Nobody | Required | Security (Firebase's own) | Held by Google under Firebase's terms. |
| Display name | Personal info: Name | The name set in Settings. **If none is set, it's the phone's model name** (`Build.MODEL`, `AuthManager.kt:23`). Written to the device doc while paired. | Collected. Not shared. | The paired phone shows it. | Optional (pairing is optional; the name can be changed) | App functionality | On unpair. A rename overwrites it. |
| Alarms | App activity: Other user-generated content | For each own alarm: ID, label, hour, minute, days, on/off, next ring time, skips (shared and "just me"), the answer to a late-ring question, vibration pattern, vibration and sound switches, text position, wallpaper framing. For joined alarms: the owner's alarm ID, ring or notify mode, time and days. Written only while paired (`FirestoreMapping.kt:156-224`, `Household.kt:75-79`). | Collected. Not shared. | The paired phone shows them. | Optional | App functionality | On unpair. Each change overwrites the last; no history is kept. |
| Ring state | App activity: App interactions | While an alarm rings or is snoozed: which alarm, a ring ID, start time, snooze end, merged alarms (`FirestoreMapping.kt:202-214`). | Collected. Not shared. | The paired phone | Optional | App functionality | Cleared when the ring ends; the doc goes on unpair. |
| Commands | App activity: App interactions | Skip, undo skip, turn off, turn on, stop ringing, snooze, invite. Each holds sender and target IDs, the alarm ID, the ring time or ring ID, snooze end, created and expiry times, status and a rejection reason. Sent when someone acts on the other phone's alarm (Partner tab, invites, "both of us" choices), and automatically to mirror a snooze or dismiss of a joined alarm. A push carries the same fields to the target phone (`commands.ts:14-49`). | Collected. Not shared. | Both paired phones | Optional | App functionality | **Not on expiry.** After 24 hours a command is ignored, not deleted. The rules forbid deletes (`firestore.rules:81`). All commands go on unpair. |
| Photos | Photos and videos: Photos | The wallpaper of an alarm that is joined or has an open invite, and the default wallpaper, uploaded only while paired. Re-encoded as a screen-sized JPEG, so no EXIF or location. Stored at `households/{hid}/media/{uid}/{sha256}` (`MediaPublisherImpl.kt:41-50, 112-121`). | Collected. Not shared. | The paired phone downloads it and shows it when the joined alarm rings. | Optional | App functionality | When nothing refers to it any more, the owner's phone deletes it (`MediaPublisherImpl.kt:180-201`). All of a household's media goes on unpair. |
| Ringtones | Audio files: Music files and Other audio files | The sound of an alarm that is joined or has an open invite, sent as the original file (up to 15 MB) with its title. Same path and rules as photos. | Collected. Not shared. | The paired phone plays it. | Optional | App functionality | As photos. |
| Pairing records | (Covered by the user ID above) | `users/{uid}` (household ID), `households/{hid}` (member IDs, created time), and `pairCodes/{code}` (household ID, creator's ID, expiry) for 10-minute pairing codes. | Collected. Not shared. | Nobody | Optional | App functionality | `users` and `households` on unpair. A code is deleted when it's used. **An unused code is never deleted**, by unpair or by expiry, except that joining another phone's code deletes the codes you showed. Tapping Show pairing code creates a one-member household and `users/{uid}` at once (`pairing.ts:53-55`). If no one ever joins, the app still shows "not paired" and offers no Unpair, so those records stay; joining another phone's code later deletes them (`pairing.ts:98-119`). |
| Function logs | Not a Play data type | Cloud Functions logs hold user and household IDs (for example `commands.ts:41`, and the cleanup errors in `pairing.ts`). | Collected (server logs) | Nobody | Required | Operations | Cloud Logging keeps the `_Default` bucket 30 days ([Google Cloud](https://docs.cloud.google.com/logging/quotas)). |

No Analytics, Crashlytics, ads or App Check SDKs are in the app. The Firebase SDKs are Auth, Firestore, Cloud Messaging (with Installations), Functions and Storage.

**What unpair deletes** (`firebase/functions/src/pairing.ts:145-177`). Either phone tapping Unpair deletes, for both people:

- the household document (line 158),
- `users/{uid}` for both members (line 159),
- both device docs and every command, by a recursive delete of the household (line 165),
- all photos and sounds under `households/{hid}/media/` in Storage (line 172).

The two cleanups log and swallow errors (lines 166-168 and 173-175). Normally everything above is gone. After a backend error, orphans can remain until you delete them in the console.

**What stays after unpair:** each phone's anonymous Auth record, any pairing code that was never used, the FCM and installation registrations, and 30 days of function logs. None of these holds a name, alarm, photo or sound. A phone that showed a pairing code but never paired also keeps a one-member household and its `users/{uid}` record (member ID and creation time only), since the app offers no Unpair until two phones are paired. **Uninstalling without unpairing deletes nothing on the server.**

**Answers for the form:**

- Does your app collect or share any of the required user data types? **Yes.**
- Is all of the user data collected by your app encrypted in transit? **Yes.** "Firebase encrypts the data in transit using HTTPS" ([Firebase](https://firebase.google.com/docs/android/play-data-disclosure)).
- Account creation: choose the option that the app doesn't let users create an account. Sign-in is anonymous and invisible; there's no account to log in to. See [Account deletion](#account-deletion).
- Do you provide a way for users to request that their data is deleted? **Yes.** Unpair in the app deletes the shared data, and the privacy policy gives an email for the rest. If a URL is asked for, give `https://tandem.kautiontape.com/privacy/#deleting-your-data`.
- Data types, each **collected, not shared, not processed ephemerally, purpose App functionality**:
  - Personal info: Name (optional)
  - Photos and videos: Photos (optional)
  - Audio files: Music files, Other audio files (optional)
  - App activity: App interactions, Other user-generated content (optional)
  - Device or other IDs (required)
- Location: none. Firebase collects IP addresses for security, but Play asks to declare approximate location only when it is "inferred, such as via IP address", and nothing infers it ([Play help](https://support.google.com/googleplay/android-developer/answer/10787469)).

Why "not shared": Play doesn't count transfers to a service provider (Firebase), or a transfer "based on a specific user-initiated action, where the user reasonably expects the data to be shared" ([Play help](https://support.google.com/googleplay/android-developer/answer/10787469)). Pairing two phones is that action. Alphaplate declared its team location as "shared with other users". If you'd rather be conservative here too, mark Name, Photos, Audio files and App activity as shared, for app functionality.

"Optional" means the data leaves the phone only once two phones are paired, and pairing is optional. The IDs are required because sign-in and push registration run on every start.

#### Account deletion

Play: "If your app allows users to create an account from within your app, our User data policy requires that it must also allow users to request for their account to be deleted" ([Play help](https://support.google.com/googleplay/android-developer/answer/13327111)). Its FAQ describes an account as "a unique user identity that developers provide as a user-facing feature", which generally has "a mechanism to verify an identity, such as password, phone number OTP". Tandem's anonymous sign-in has no user-facing identity, nothing to log in with and nothing to verify, so the requirement doesn't apply. This is a judgment call.

The Data safety form still asks about deletion requests. Acceptable mechanisms include "in-app features, contact forms, or a dedicated email alias" ([Play help](https://support.google.com/googleplay/android-developer/answer/10787469)). Here: Unpair in the app, plus the email `shawn@shawnsquire.me` in the privacy policy's "Deleting your data" section. No separate deletion page.

#### Permission declarations

The Console asks for these when you upload the first bundle, or under App content.

- **Exact alarms (`USE_EXACT_ALARM`).** Play allows it when "The app is an alarm or timer app" ([Play help](https://support.google.com/googleplay/android-developer/answer/13161072)). Pick the alarm clock use. If there's a text box: "Tandem Alarm is an alarm clock. Every alarm the user sets is scheduled with AlarmManager.setAlarmClock so it rings at the exact minute, in Doze and after a reboot."
- **Full-screen intent (`USE_FULL_SCREEN_INTENT`).** Play pre-grants it to apps whose core functionality is "setting an alarm" ([Play help](https://support.google.com/googleplay/android-developer/answer/13161072)). Pick the alarm use.
- **Foreground service, type `systemExempted`.** Android allows this type for "Apps holding SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM permission" ([Android docs](https://developer.android.com/develop/background-work/services/fgs/service-types)). The form asks, per type, for a description, the user impact, a video link and a use case ([Play help](https://support.google.com/googleplay/android-developer/answer/13392821)). Play's list may not have a `systemExempted` row; pick the closest use case shown (an alarm, or "Other").
  - Description: "When an alarm goes off, RingingService runs as a foreground service while it sounds. It plays the alarm sound, vibrates, and keeps the ringing screen and its notification up until the user stops or snoozes the alarm, or for at most 10 minutes. It starts only from an exact alarm (the app holds USE_EXACT_ALARM) and stops as soon as the ring ends."
  - User impact: "If the system defers the service, the alarm doesn't sound at the set time. If the system interrupts it, the alarm goes quiet before the user is awake. Either way the user oversleeps, which is the one thing an alarm clock must prevent."
  - Video: upload the screen recording of an alarm firing and being stopped to YouTube as **Unlisted**, and paste its link. Play takes a link, not a file.
- **Battery optimization:** nothing to declare. The app no longer requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Play prohibits "requesting direct exemption from Power Management features… unless the core function of the app is adversely affected" ([Android docs](https://developer.android.com/training/monitoring-device-state/doze-standby)). The Health check opens the battery optimization list instead, where the user picks the app.
- **Target API:** "Starting August 31, 2026: New apps and app updates must target Android 16 (API level 36) or higher" ([Play help](https://support.google.com/googleplay/android-developer/answer/11926878)). The app targets 37.

### 4. Store listing

Grow, Store presence, Main store listing.

- **App name:** Tandem Alarm
- **Short description** (80 characters at most): `An alarm clock for two phones. See, skip or stop each other's alarms.`
- **Full description:**

  > Tandem Alarm is an alarm clock for two people, one phone each.
  >
  > Each phone keeps its own alarms and rings them itself, even offline, in Do Not Disturb, and after a reboot. Pair the two phones with a 6-character code, and each can see the other's alarms.
  >
  > From the paired phone you can skip the next ring, turn an alarm off or on, or stop one that's ringing. You can't change the other phone's alarm times.
  >
  > Join an alarm on the other phone and both phones ring together, with its photo and sound. Snooze or stop on either phone, and the other follows.
  >
  > No account and no ads. Sign-in is anonymous. Unpair at any time to delete the shared data.

- **App category:** Tools.
- **Contact email:** `<contact email>` (shown publicly on the listing). The privacy policy uses `shawn@shawnsquire.me`.
- **Website:** `https://tandem.kautiontape.com/`
- **Assets** live in `docs/play-assets/`:
  - Phone screenshots, 1080 × 1920, in `docs/play-assets/screenshots/`: `1-alarms.png`, `2-editor.png`, `3-ringing.png`, `4-partner.png`.
  - App icon, 512 × 512 32-bit PNG, full-bleed Night background (Play applies its own mask): `docs/play-assets/icon-512.png`.
  - Feature graphic, 1024 × 500 24-bit PNG, no alpha: `docs/play-assets/feature-graphic-1024x500.png`.

### 5. Testers

Test and release, Testing, **Closed testing**. Use the default track (Play names it Alpha) unless you have a reason not to.

1. **Testers** tab: create an email list with the testers' Google account addresses, tick it, save.
2. Copy the **opt-in URL** and send it. Testers accept once, then install from the Play Store; updates arrive like any app's.
3. Note the track's name. The workflow uploads to `alpha` unless the repository variable `PLAY_TRACK` says otherwise. Google: "Closed testing tracks are created manually and they have custom names" ([Play Developer API](https://developers.google.com/android-publisher/tracks)). If you made your own track, set `PLAY_TRACK` to its name (step 7).

Closed testing has no tester minimum. The 12-testers-for-14-days rule only gates production: "Developers with personal accounts created after November 13, 2023, must run a closed test for their app with a minimum of 12 testers who have been opted in continuously for at least 14 days" before applying for production ([Play help](https://support.google.com/googleplay/android-developer/answer/14151465)).

### 6. First release, by hand

Play's API can't make the first release of a new app ("you will have to upload at least one APK through the Play Console before you can use this API", [Play Developer API](https://developers.google.com/android-publisher/edits)). Until the app has a reviewed release, it also refuses anything but drafts ("Only releases with status draft may be created on draft app"). So the first release goes in by hand, with the Play secret unset.

1. Make sure `PLAY_SERVICE_ACCOUNT_JSON` is **not** set. The workflow then skips Play with a notice.
2. Tag v0.5.0 with an annotated tag. Its message becomes Play's release notes, which Play caps at "500 Unicode characters per language" ([Play help](https://support.google.com/googleplay/android-developer/answer/9859348)). Check the length with `wc -m`, not `wc -c`.
   ```
   git tag -a v0.5.0 -F notes.txt && git push origin v0.5.0
   ```
3. When the workflow finishes, download `tandem-v0.5.0.aab` from the [v0.5.0 GitHub Release](https://github.com/shawnsquire/tandem-alarm/releases/tag/v0.5.0).
4. Play Console, Closed testing, your track, **Create new release**. Upload `tandem-v0.5.0.aab`. Release name `v0.5.0`. Paste the tag message as the release notes. Play warns that no deobfuscation file was uploaded. Download `tandem-v0.5.0-mapping.txt` from the same GitHub Release and upload it as the deobfuscation file (in the Console's App bundle explorer for this version, or wherever it offers "Upload deobfuscation file"). Later releases upload the mapping automatically.
5. Fill in any declarations it asks for (step 3), then **Send for review** and roll out.
6. Google reviews the first release, usually within hours to a couple of days.

After the first install from Play, you can check that Play kept your key:

```
adb shell pm path com.kautiontape.tandem        # prints package:/data/app/…/base.apk
adb pull <that path> play.apk
apksigner verify --print-certs play.apk
apksigner verify --print-certs tandem-v0.5.0.apk
```

The `Signer #1 certificate SHA-256 digest` lines must match. If they don't, a GitHub APK and a Play install won't update each other; stop and tell the manager.

### 7. Service account for automatic uploads

Do this after the first release has passed review. From the next tag on, the workflow uploads the AAB and `mapping.txt` to the closed-testing track and rolls it out. Steps from [Play Developer API: getting started](https://developers.google.com/android-publisher/getting_started):

1. [Google Cloud console](https://console.cloud.google.com/): pick a project. The `tandem-alarm` Firebase project is a Cloud project and will do.
2. Enable the **Google Play Android Developer API** for that project.
3. IAM and admin, Service accounts, **Create service account**, for example `play-upload`. It needs no Cloud roles.
4. Open the account, Keys, Add key, Create new key, **JSON** ([Google Cloud](https://cloud.google.com/iam/docs/keys-create-delete)). A file downloads.
5. Play Console, **Users and permissions**, **Invite new users**. Enter the service account's email. Leave account permissions empty. Under **App permissions**, add Tandem Alarm and grant **Release apps to testing tracks** only. Invite.
6. Add the JSON as a secret, keep a copy in your password manager, then delete the file:
   ```
   gh secret set PLAY_SERVICE_ACCOUNT_JSON < play-upload.json
   ```
7. Only if your closed-testing track isn't `alpha`:
   ```
   gh variable set PLAY_TRACK --body "<track name>"
   ```

### 8. Moving the two phones over

Do this once, after the first Play release is live. Both apps installed at once would both ring.

1. In the old app, on either phone, Partner tab, **Unpair**. That deletes both phones' shared data on the server: the household, both device docs (names, alarm lists, ring state), every command, and every shared photo and sound (see [What unpair deletes](#data-safety)). It doesn't touch the alarms on either phone. Uninstalling without unpairing leaves all of that on the server.
2. On each phone, note your alarms: times, days, labels, sounds and wallpapers. A screenshot of the list is enough. Nothing carries over.
3. Uninstall the old app. Old Tandem Alarm builds are `me.shawnsquire.tandem`. If it came from Obtainium, delete its entry there too.
4. Install from Play (the opt-in link in step 5), or the APK from the GitHub Release.
5. Open Settings, Health check, and fix anything that isn't green.
6. Pair again: Partner tab, Show pairing code on one phone, Enter code on the other.
7. Rebuild your alarms, then join the other phone's alarms again.

## Every release

1. Tag with an annotated tag; the message is the release notes (500 characters at most):
   ```
   git tag -a v0.5.1 -m "…" && git push origin v0.5.1
   ```
2. The workflow publishes the GitHub Release (APK and AAB) first, then uploads to Play. The version code is the workflow run number.
3. **If the workflow fails, push a new tag; don't re-run it.** A re-run keeps the same run number, so the same version code, and Play rejects a version code it has already seen.
