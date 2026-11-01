# Linked alarms — implementation plan

## Context

Linked alarms let one partner join the other's alarm, so both phones ring for it and a snooze or dismiss on either phone reaches the other. The spec is `docs/design/2026-11-01-linked-alarms.md` on branch `design/linked-alarms`. It has commit `55083c2` plus the uncommitted edge-case decisions O1–O14 from today. The flow map is https://claude.ai/artifact/TwxTdSazbaJyRrbo9HmZiz.

There is one addition since then: **turning off a joined alarm turns it off for both, and the joiner is told** ("Shawn turned off his 6:30, so it's off for you too").

Invariants **I1–I4** from the design go verbatim into every worker brief. The run follows the same pattern as the original build (`docs/plans/2026-10-31-tandem-alarm.md`): herdr workers each run `/sdd` on a unit, and this session is the `/orchestrate` manager.

## Step 0 — manager, before any worker

1. Add the turn-off notification to the design doc.
   - **Flows → Owner changes or deletes:** when `LinkSync` sees a linked row's copied `enabled` go from true to false, it posts "Shawn turned off his 6:30, so it's off for you too". It does this only when all three hold:
     - `joinerEnabled` is true.
     - The change didn't come from the joiner's own pending "both off".
     - The alarm is repeating, or it's a one-shot whose occurrence is still in the future. A one-shot that turned itself off after ringing, or after a solo skip, posts nothing.
   - **UI → Notifications:** add it to the list.
   - **Testing:** add a LinkSync case for each of the three conditions.
2. Commit the doc as `docs: Decide linked alarm edge cases`. Copy this plan to `docs/plans/2026-11-01-linked-alarms.md` and commit it as `docs: Add linked alarms plan`. Push `design/linked-alarms`, open a PR, and merge it so workers branch from `main`.
3. Run setup is the same as last time:
   - Run folder: `~/documents/apps/tandem-alarm-runs/linked-alarms/`, holding `plan.md` and `workers.md`.
   - At most 3 workers at once, and at most 2 running Gradle. Run `free -g` before each spawn.
   - Every worker sets `org.gradle.jvmargs=-Xmx1536m` and `kotlin.daemon.jvmargs=-Xmx1g`, and runs `./gradlew --stop` at the end.
   - Workers open PRs and never merge. The manager checks each PR against the spec. PRs that touch rules, functions, ringing or the applier also get an Opus quality review. Then the manager runs `gh pr merge --squash`. Commits use `topic: message`.

## L0 — Contract (serial; branch `feat/links-contract`)

This unit freezes the signatures, with fakes, so later units compile on their own. Changing a signature after L0 counts as BLOCKED, and the worker reports it to the manager.

- **`schedule/Model.kt`**
  - New `AlarmLink(ownerUid, ownerAlarmId, mode: LinkMode, ownerSkippedOccurrenceAt, joinerEnabled, pendingBoth: PendingBoth?, overrideLabel?, overrideRingtoneUri?, overrideWallpaperPath?)`.
  - New `LinkMode { RING, NOTIFY }` and `PendingBoth(type, occurrenceAt?, commandId)`.
  - `Alarm` gains `link: AlarmLink? = null` and `soloSkippedOccurrenceAt: Instant? = null`.
  - `RingState` gains `occurrenceAt: Instant?` and `merged: List<RingMember>`, with `RingMember(alarmId, occurrenceAt)`.
  - `SnoozeEntry` (in `alarm/SnoozeLedger.kt`) gains `occurrenceAt`, so validation rule 4 can match a parked snooze or a split-out merged member by `(alarmId, X)`.
  - `CommandType` gains `SNOOZE` and `INVITE_LINK`. `Command` gains `snoozedUntil: Instant?`.
  - New `RingOrigin { LOCAL, REMOTE }`.
- **`schedule/Scheduling.kt`**
  - `validate` gains a `RingContext` (slot ring, merged members, snooze ledger entries, `FiredLedger.lastRang`). It defaults to today's behavior.
  - New `isArmed(alarm)` stub.
- **`data/`**
  - Room v2 with an explicit `Migration(1, 2)` that adds the link and solo-skip columns, and the exported `schemas/.../2.json`.
  - Add the schemas folder to the test assets so a `MigrationTestHelper` test can run.
  - `AlarmRepository` gains `ownAlarms`, `linkedAlarms`, `setSoloSkip`, `upsertLinked` and `updateLink`.
  - `RingStateStoreImpl` gains keys for `occurrenceAt` and `merged`, with `merged` stored as JSON.
- **`alarm/`**
  - `RingingController.dismiss(ringId, origin = LOCAL)` and `snooze(origin = LOCAL, until: Instant? = null)`.
  - New `dismissOccurrence(alarmId, occurrenceAt, origin)` and `snoozeOccurrence(alarmId, occurrenceAt, until, origin)`.
  - A `RingMirrorPort.onLocal(kind, members, snoozedUntil)` hook that the controller calls on local resolutions only.
  - **Resolving X before it fires.** When X hasn't fired yet, `dismissOccurrence` records X in `FiredLedger`, and `snoozeOccurrence` records it and also arms the snooze. `executeFire` already re-checks `FiredLedger` before it claims the slot, so the fire is suppressed even if it's partway through the pre-fire check. Validation rule 3 reads the same record.
- **`sync/`**
  - `snoozedUntil` is threaded through `CommandSender.send`, `CommandRemote.create`, `commandCreateMap`, `commandFromDoc` and `commandFromFcm`.
  - `deviceDocMap` gains `links`, `defaultWallpaper`, `alarms[].media`, `alarms[].soloSkippedOccurrenceAt`, `ringing.occurrenceAt` and `ringing.merged`. The parsers into `PartnerState` and `PartnerAlarm` get the matching fields.
  - New interfaces: `LinkRepository`, `LinkSync`, `RingMirror`, `MediaPublisher` and `LinkedMediaCache`.
  - `CommandNotifier` gains `notifyInvite`, `notifyOwnerDeleted`, `notifyOwnerTurnedOff` and `notifyRejected`.
  - `PreFireCheck.pullAndApply(timeout, linkedOwnerAlarmId: String? = null)`. For a linked row it also fetches the owner's device doc with `Source.SERVER`, never the cache. A cached read can "succeed" while still showing a skip the owner has since cleared, and suppressing on it would break I1. It applies the doc through `LinkSync`, and the caller then re-reads local state, as today.
- **Build:** the `firebase-storage` dependency, and `Firebase.storage.useEmulator(host, 9199)` in `TandemApp.connectToEmulators()`.
- Fakes for every new interface go in `fakes/Fakes.kt`, wired in `AppContainer` at clearly marked lines.
- **Done when:** `./gradlew testDebugUnitTest assembleDebug` is green, including the migration test, and nothing behaves differently yet.

## Wave A (parallel, after L0 merges)

**L1 — Scheduling and validation** (`feat/links-schedule`; pure JVM, test-first in `schedule/NextOccurrenceTest`, `ValidateTest`)
1. `nextOccurrence` and `isArmed` cover:
   - The joiner's own skip, `ownerSkippedOccurrenceAt`, and a pending "both" skip.
   - For a linked row, armed means `joinerEnabled` and (copied `enabled` or a pending "both on") and no pending "both off".
   - On the owner, the solo skip plus `skippedOccurrenceAt`.
   - Solo skip expiry in `SkipExpiry`.
2. `validate` checks ring commands (`STOP_RINGING` by occurrence and `SNOOZE`) in the design's rule order:
   1. Expired or duplicate.
   2. Skipped here, in both directions.
   3. Already resolved, including inside `FIRE_GRACE`.
   4. Live: sounding, snoozed, parked or a merged member. A SNOOZE needs a later T.
   5. Early.
   6. Otherwise reject.
3. Other command checks:
   - `SKIP_NEXT` upgrades a solo skip to a shared skip; `UNSKIP` clears only the shared skip.
   - `INVITE_LINK` is checked for validity.
   - A partner command aimed at a linked row's `id` is rejected.

**L2 — Ringing engine** (`feat/links-ringing`; tests in `RingingSessionTest` and `RingingControllerImplTest`). This is the heaviest unit. Its pieces share `RingingControllerImpl` and `RingingSession`, so it stays one worker; say so in the brief and let it plan its own `/sdd` tasks.
0. `RingingSession.executeFire` decides suppression with `isArmed` and every skip, including the linked skips and `pendingBoth`, not just `enabled` and `skippedOccurrenceAt`. `preFire` passes `linkedOwnerAlarmId` for a linked row.
1. Merge instead of supersede, in `RingingControllerImpl.claimFire` and `claimSnoozeResume`, replacing `supersedeLocked` for sounding rings:
   - A snooze that resumes while the slot is sounding merges into it.
   - A slot that's snoozed is still parked.
   - In a merged ring, Ring mode wins over Notify.
2. Origin plumbing:
   - Local button, notification action and auto-silence (`ensureAutoSilence`) are `LOCAL` and call `RingMirrorPort`.
   - The occurrence methods are `REMOTE`, never call the port, and use the absolute T.
   - A received SNOOZE for one merged member splits it into the `SnoozeLedger` as a parked snooze.
   - A received STOP removes only its member.
   - A local resolution resolves every member and reports them all to the port.
3. `RingState.occurrenceAt` is set on fire and kept on snooze resume.
4. `suppressFire` turns off a one-shot with a solo skip only after X has passed.
5. Notify mode:
   - A heads-up notification on a new alarm-category channel, with Snooze and Stop.
   - Snooze re-posts it at T.
   - It auto-silences at 10 minutes.
6. `AlarmMirror` encodes `link` and `soloSkippedOccurrenceAt`, so a locked boot knows a ring is linked.
7. Ringing screen:
   - "With Shawn" on a joined ring, and every label on a merged ring.
   - When the other phone ends the ring, a brief "Shawn dismissed" or "Shawn snoozed to 6:40" before the screen closes. `RingingScreenModel` delays the close for a `REMOTE` end.
8. Media for a linked ring is resolved through `LinkedMediaCache.resolve(...)`. An unreadable override falls to the next step in the order, not the system sound. Sound candidates still end on the bundled tone, as in `SoundSequencer`.

**L3 — Backend** (`feat/links-backend`; Node only, light)
1. `firestore.rules`: add `snoozedUntil` to the allowed command keys, and `SNOOZE` and `INVITE_LINK` to the allowed types.
2. New `storage.rules`:
   - Only household members read `households/{hid}/media/**`.
   - Only `{ownerUid}` writes under its own folder.
   - Files are capped at 15 MB, with content type `audio/*` or `image/*`.
3. `firebase.json` gains a storage section and a storage emulator on port 9199.
4. `commands.ts`: `commandPayload` carries `snoozedUntil`.
5. `pairing.ts`: `unpairFor` also deletes the household's Storage prefix.
6. Tests in `rules.test.ts`, with a storage block in `initializeTestEnvironment`, and in `commands.test.ts` and `pairing.test.ts`.
- Notes: the membership check in `storage.rules` uses `firestore.get(/databases/(default)/documents/households/$(hid)).data.members`, a cross-service rule. In prod, the first deploy asks for permission to connect Storage to Firestore (see Shawn's steps). The emulator needs no grant.

## Wave B (parallel, after wave A merges)

**L4 — Sync** (`feat/links-sync`)
1. **`LinkSync`**
   - Watches `PartnerRepository` and upserts each linked row's copied fields.
   - Removes a row whose owner alarm is gone and calls `notifyOwnerDeleted`. A ring that's sounding finishes first.
   - Calls `notifyOwnerTurnedOff` under the Step 0 rule.
   - Resets `joinerEnabled` when a one-shot is turned back on (O9).
   - Clears `pendingBoth` when the owner's state matches. Within one pass, the turn-off check reads `pendingBoth` before it is cleared, so a joiner's own "both off" never notifies the joiner.
   - Removes all links on unpair.
2. **`LinkRepository`**
   - Join is idempotent per `ownerAlarmId` and accepts any open invite.
   - Accept and decline invites. Accepting for a deleted alarm fails.
   - Leave, set mode and set overrides.
   - "Both of us" writes `pendingBoth` and sends the command. A rejection clears it and calls `notifyRejected`.
   - Publishes `links`.
3. **`RingMirror`** (implements `RingMirrorPort`)
   - On the joiner, it always sends for a linked member. On the owner, it sends only when the partner's `links` includes the alarm.
   - Partner-tab Stop on a joined alarm is a local dismiss of the joiner's own copy, and sends one STOP.
   - While the phone is locked, commands go to a device-protected outbox modeled on `alarm/DeferredAlarmWrites.kt` and are sent after unlock.
4. **`CommandApplierImpl`**
   - Applies `SNOOZE`, `STOP_RINGING` by occurrence and `INVITE_LINK` through the new controller methods, as `REMOTE`.
   - Commands from the owner are mapped to the linked row by `link.ownerAlarmId`.
   - It passes `RingContext` to `validate`.
5. **`PreFireCheckImpl`:** for a linked row, it also reads the owner's device doc inside the same 3 s cap. It suppresses only on a successful read that shows X won't ring, and a solo skip doesn't count. `RingingSession.preFire` needs to use the result for linked rows.
6. **`StatePublisherImpl`** switches to `ownAlarms` (`:62` and `:112`) and publishes `links`.
7. **`RingingCommandListener`** detaches while snoozed today, so crossing snoozes (O1) and a STOP during a snooze arrive only through FCM or the pull. Decide whether to keep it attached while a linked ring is snoozed, and record the decision in the PR.

**L5 — Media** (`feat/links-media`)
1. **`MediaPublisher`** (owner)
   - Uploads, for each alarm with a link or an open invite, its wallpaper and its ringtone bytes. A system sound is read through `ContentResolver`. It also uploads the Settings default wallpaper.
   - Files are named by SHA-256, and anything over 15 MB is skipped.
   - It writes the `media` refs and deletes its own hashes that nothing references.
2. **`LinkedMediaCache`** (joiner)
   - A network-constrained WorkManager job downloads into `filesDir/linked/{sha}`.
   - `resolve()` follows the design's resolution order.
3. `MediaOrphanSweeper` also sweeps `linked/` and keeps files that a linked row or a pending download points at.
4. Health check rows: "Linked media downloaded" and "Storage reachable" (`HealthItem` and `HealthSignals`).

**L6 — UI** (`feat/links-ui`; ViewModel tests on the contract fakes)
1. Alarms list (`buildAlarmsState`):
   - Uses own and linked rows, sorted by time.
   - A joined row has a partner chip, a link icon, a Ring or Notify tag, a time that can't be edited, and states such as "Shawn skipped", "Skipped for you" and "Shawn's skipping, you're still on".
   - Tapping a joined row opens the Linked alarm sheet. It has Ring/Notify, overrides with "Use Shawn's", Skip ("Just me / Both of us") and Leave.
   - The joined row's switch asks "Just me / Both of us".
2. Own rows:
   - A "<partner> joined" badge.
   - Skip on a joined own alarm asks "Just me / Both of us".
   - The switch stays a single tap and turns it off for both.
   - Alarm edit gets "Invite <partner>", or shows "<partner> joined".
3. Partner tab: Join, or Joined (which opens the sheet). Today `PartnerViewModel.stopRinging` sends `STOP_RINGING` by ringId. On a joined alarm whose copy is sounding or snoozed, Stop instead becomes a local dismiss of that copy, so `RingMirror` sends the one STOP (O5).
4. New notification texts: invite (Join / Decline), owner deleted, owner turned off, "both" rejected.

## L7 — Integration (serial, after wave B; `feat/links-integration`)
1. Swap the fakes for the real implementations in `AppContainer`, and start `LinkSync` and `MediaPublisher` in `TandemApp.fullStartup`.
2. Additions to `TwoDeviceSmokeTest`, against the emulators with Storage:
   - Join, then both ring, then B snoozes and A snoozes to the same T, then A dismisses and B stops.
   - Invite, then accept.
   - Owner solo skip: B still rings, and B's STOP is rejected on A.
   - Owner turns it off: B is off and notified.
   - Media is shipped and resolved.
3. Add to the README on-device checklist:
   - Merged rings at the same minute.
   - A mirrored dismiss while the other phone dozes.
   - Both phones offline.
   - A reboot before unlock, then dismiss, then unlock.
   - An 11 pm skip with the joiner dozing, caught by the pre-fire read.
   - Notify mode.
4. Add "Decisions made during the build" to the design doc.

## Shawn's steps (each needs a go-ahead at the time)
- Enable Cloud Storage for Firebase on `tandem-alarm`. Blaze is already on.
- Deploy with `firebase deploy --project prod --only firestore:rules,storage,functions` from `firebase/`. On this first deploy, accept the CLI prompt that lets Storage rules read Firestore; without it every media read is denied. Then tag a release.

## Verification
- **Per PR:** CI green (Android unit tests and assemble, Firebase emulator tests). The worker reports the exact commands it ran.
- **L0:** the Room migration test passes v1 → v2 with existing alarms intact.
- **Wave A:** the `ValidateTest` cases for each rule and both directions of "skipped here". The `RingingSessionTest` cases for merging, origin (no echo), a SNOOZE that splits one member out, Notify auto-silence, and a solo-skip one-shot.
- **After L7:** `./gradlew testDebugUnitTest connectedDebugAndroidTest` on an AVD while `firebase emulators:start` (now including Storage) is running.
- **Final:** Shawn installs on both phones and works through the new checklist items.
