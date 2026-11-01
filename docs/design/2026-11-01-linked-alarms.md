# Linked alarms — design

Date: 2026-11-01 · Status: agreed, edge cases decided, not yet planned

Builds on [2026-10-31-tandem-alarm.md](2026-10-31-tandem-alarm.md). Its invariants I1–I3 still hold; this doc says where each one is extended.

## Goal

A partner can *join* one of the other person's alarms, so both phones go off for it. Snoozing or dismissing on either phone snoozes or dismisses the other. The joiner sees the owner's message, photo and ringtone by default and can override each one. Joined alarms show on the joiner's Alarms screen next to their own, in their own format.

Words used below: the **owner** is the phone whose alarm it is; the **joiner** is the partner's phone that linked to it. A **link** is one joiner following one owner alarm.

## Requirements

1. Linking works in both directions: the joiner taps Join on any of the owner's alarms, or the owner invites the partner to one. No approval is needed to join; an invite needs the joiner to accept. Only the joiner removes a link.
2. Each link has a mode: **Ring** (full ringing screen and sound) or **Notify** (heads-up notification with Snooze and Stop). Both modes mirror snooze and dismiss.
3. Snooze on either phone snoozes the other to the same end time. Dismiss on either phone dismisses the other. Auto-silence counts as a dismiss.
4. The joined alarm rings on the joiner's phone without the network (I1), at the owner's time and days as last seen.
5. The joiner can't edit the owner's time or days. Skip and on/off on a joined alarm ask "Just me or both of us?": "just me" changes only the joiner's copy; "both" sends the existing `SKIP_NEXT` / `DISABLE` / `ENABLE` command to the owner. On the owner's side, Skip on an alarm the partner has joined asks the same question: "just me" skips only the owner's ring and leaves the joiner's copy armed; "both" is today's skip.
6. The owner's per-alarm wallpaper and ringtone (custom file or system sound) and default wallpaper are shipped to the joiner. Resolution on the joiner: joiner's override → owner's per-alarm media → owner's default wallpaper / system alarm sound → joiner's defaults. The message is the joiner's override, else the owner's label.
7. The Alarms screen lists own and joined alarms together, sorted by time. Joined rows are visually distinct and open a Linked alarm sheet instead of the editor.
8. If an own alarm and a joined alarm sound at the same time, they merge into one ring.

Out of scope for now: ring offset ("ring me 15 min after"), a "they're up" notification, a ringing notification for alarms that aren't joined, snooze escalation, joiner and owner in different time zones (the joiner computes occurrences from the owner's hour and minute in its own zone).

## Invariants, extended

- **I1** is unchanged and now covers joined alarms: the joiner schedules its own copy with `setAlarmClock` and rings from local state. Shipped media is cached locally before it is used; a missing file falls through the resolution order, never blocks a ring.
- **I2** still holds. The joiner's linked row is the joiner's own data (a copy plus its own skip and overrides). The owner's alarm is only ever changed by the owner, through commands.
- **I3** gains occurrence pinning for ring commands. `STOP_RINGING` may name `occurrenceAt` instead of `ringId`, and the new `SNOOZE` always names `occurrenceAt`. The two phones' rings start independently and have different `ringId`s, so `(ownerAlarmId, occurrenceAt)` is the shared key.
- **I4 (new) — mirroring never echoes.** Only a local action (button, notification action, auto-silence) sends a mirror command. A snooze or dismiss applied from a received command is tagged `origin = REMOTE` and sends nothing.

## Data model

Joiner's Room `Alarm` gains a nullable `link`:

```
Alarm.link = AlarmLink(
  ownerUid, ownerAlarmId,
  mode: RING | NOTIFY,
  ownerSkippedOccurrenceAt,          // copied from the owner's device doc
  joinerEnabled,                     // the joiner's own on/off
  pendingBoth?,                      // {type, occurrenceAt?, commandId}: a "both of us" command
                                     // not yet reflected in the owner's doc
  overrideLabel?, overrideRingtoneUri?, overrideWallpaperPath?)
```

A linked row has its own local `id` (a new UUID, like any alarm); `link.ownerAlarmId` names the owner's alarm and is the key in commands and `links`. Its `hour`, `minute`, `days` and `enabled` are copied from the owner's published alarm and are only ever written by `LinkSync`. The joiner's own state lives in separate fields so a sync never overwrites it:

- "Skip for me" is the row's existing `skippedOccurrenceAt`. `nextOccurrence` skips it, `link.ownerSkippedOccurrenceAt`, and a pending "both" skip.
- "Off for me" is `link.joinerEnabled`. The row is armed only when `joinerEnabled` is true and the owner side is on: the copied `enabled` is true (or a "both on" is pending) and no "both off" is pending.
- `pendingBoth` makes a "both of us" choice take effect on the joiner at once, even while the owner is offline. It is cleared when `LinkSync` sees the owner's state match (skip copied, `enabled` copied), or when the command is rejected or expires; a rejection notifies the joiner like any rejected command. "Both on" also sets `joinerEnabled = true`; "both off" leaves `joinerEnabled` alone, so the joiner comes back when the owner turns the alarm on again.
- A one-shot that turns itself off after ringing, or after a "just me" skip, sets `joinerEnabled = false`, not the copied `enabled`. When a one-shot's copied `enabled` later goes from false to true, `LinkSync` resets `joinerEnabled` to true, since the owner has set it up again. Repeating alarms never reset it, because there only the joiner's own switch changes it.

**Owner's "just me" skip.** The owner's `Alarm` gains `soloSkippedOccurrenceAt`. It is published in `alarms[]` for display ("Shawn's skipping, you're still on"), but `LinkSync` never copies it into `ownerSkippedOccurrenceAt`, so the joiner's copy stays armed. On the owner, `nextOccurrence` skips both it and `skippedOccurrenceAt`. A one-shot with a solo skip turns off once X has passed (on the suppressed fire), not when the skip is set, because an earlier `enabled = false` would be copied and silence the joiner. A partner `SKIP_NEXT` for the same X upgrades it to a shared skip; a partner `UNSKIP` clears only the shared skip; the owner's own un-skip clears either. On an alarm nobody has joined, Skip doesn't ask and sets the shared skip as today.

Because a linked alarm is an ordinary row, the scheduler, the direct-boot mirror (`AlarmMirror`), boot re-arming, the ringing service and the Health check need no separate path. The mirror must carry `link` so a locked boot still knows a ring is linked.

Linked rows live in the same table, so every reader of `AlarmRepository.alarms` must say whether it wants them:

| Consumer | Linked rows |
|---|---|
| `AlarmScheduler`, `AlarmMirror`, boot re-arm, ringing | included |
| `deviceDocMap` / `StatePublisher` (`alarms` array) | excluded; links are published only through `links` |
| `CommandApplierImpl` | a partner command whose `alarmId` matches a linked row's `id` is rejected; owner-to-joiner commands are matched by `link.ownerAlarmId` |
| Partner tab, Join | excluded (no link to a link) |
| Alarm editor | blocked; the row opens the Linked alarm sheet |
| `MediaOrphanSweeper` | overrides count as references; cached owner media is handled through `linked/` |
| Alarms screen list | included, drawn as joined rows |

The repository exposes `ownAlarms` and `linkedAlarms` flows next to `alarms`, so each consumer picks explicitly.

`RingState` gains `occurrenceAt` (the scheduled occurrence this ring belongs to) and `merged: List<{alarmId, occurrenceAt}>` (other alarms folded into this ring). Both are published in the device doc's `ringing`.

Device doc additions:

```
households/{hid}/devices/{uid}
  links: [{ ownerAlarmId, mode }]                 // written by the joiner
  defaultWallpaper: { sha, size } | null
  alarms[].media: { wallpaper: {sha, size}?, ringtone: {sha, size, title}? }
  alarms[].soloSkippedOccurrenceAt                  // display only; never copied by LinkSync
  ringing.occurrenceAt, ringing.merged
                                                    // a linked row's ring (alarmId, merged[].alarmId) is
                                                    // published under its link.ownerAlarmId
```

Commands:

| Type | New? | Fields | Direction |
|---|---|---|---|
| `SNOOZE` | new | `alarmId`, `occurrenceAt`, `snoozedUntil` | either |
| `STOP_RINGING` | extended | `alarmId`, `ringId` **or** `occurrenceAt` | either |
| `INVITE_LINK` | new | `alarmId` (owner's) | owner → joiner |

On commands sent by the owner to the joiner, `alarmId` is the owner's alarm id; the joiner maps it to the linked row by `link.ownerAlarmId`. `snoozedUntil` is absolute, so both phones resume together regardless of each one's snooze length setting.

### Ring command validation

A received `STOP_RINGING{occurrenceAt: X}` or `SNOOZE{X, T}` is checked in this order. Validation looks at the slot's ring, its `merged` members and the snooze ledger, not only the slot's single ring as `validate` does today.

1. **Expired or duplicate:** reject (I3, unchanged).
2. **Not ringing here** ("skipped here"): this phone's own state says X wouldn't ring: its `skippedOccurrenceAt` or `soloSkippedOccurrenceAt`, `link.ownerSkippedOccurrenceAt`, a pending "both" skip or off, copied `enabled = false`, or `joinerEnabled = false`. Without this, the partner's snooze would arm a ring on a phone that chose not to ring, in either direction.
3. **Already resolved** ("already rang or changed"): X was dismissed, auto-silenced or dismissed in advance on this phone. Checked against `FiredLedger` plus the live ring state, so it holds inside `FIRE_GRACE` too and a late `SNOOZE` can't re-arm a dismissed ring.
4. **Live:** X is sounding, snoozed (current or parked), or a member of a merged ring: apply. A `SNOOZE` whose T is not later than X's current snooze end is rejected ("already snoozed later"), so two crossing snoozes settle on the later T.
5. **Early:** X is the next occurrence and at most `FIRE_GRACE` away. `STOP_RINGING` dismisses it in advance (no ring; a one-shot turns off as on a normal dismiss). `SNOOZE` arms the snooze with no ring first.
6. Otherwise reject.

## Components

| Unit (package) | Does | Depends on |
|---|---|---|
| `schedule` (extended) | `nextOccurrence` with the joiner's skips, the owner's solo skip and `pendingBoth`; `validate` for occurrence-pinned `STOP_RINGING`/`SNOOZE` in the order above; `INVITE_LINK` validity | `java.time` only |
| `sync.PreFireCheck` (extended) | For a linked row, also reads the owner's device doc inside the same 3 s cap (see Flows) | `PartnerRepository` |
| `sync.LinkSync` (new) | Watches the owner's device doc via `PartnerRepository` and upserts each linked row's copied fields; removes a row whose owner alarm is gone and notifies ("Shawn deleted his 6:30, you've left it"); removes all links on unpair; asks `LinkedMediaCache` for new hashes | `PartnerRepository`, `AlarmRepository`, `LinkedMediaCache` |
| `sync.LinkRepository` (new) | Join, accept/decline invite, leave, set mode and overrides; publishes `links` through `StatePublisher` | `AlarmRepository`, `StatePublisher` |
| `sync.RingMirror` (new) | On a local snooze/dismiss of a ring that is linked (joiner side) or joined by the partner (owner side, from the partner's `links`), sends `SNOOZE`/`STOP_RINGING` via `CommandSender`; respects I4; queues in a device-protected outbox while locked | `CommandSender`, `PartnerRepository`, outbox |
| `sync.CommandApplierImpl` (extended) | Applies `SNOOZE`, occurrence-pinned `STOP_RINGING` and `INVITE_LINK`; tags applied ring actions `REMOTE` | `RingingController`, `CommandNotifier` |
| `sync.RingingCommandListener` (extended) | Also listens while a linked ring sounds, for commands from the owner | `CommandRemote` |
| `sync.MediaPublisher` (new, owner side) | Uploads media for alarms with a link or open invite; writes `media` refs; deletes own hashes no longer referenced | Cloud Storage, `StatePublisher` |
| `media.LinkedMediaCache` (new, joiner side) | Downloads hashes into `filesDir/linked/` with a network-constrained WorkManager job; resolves the media for a linked ring in the order above | Cloud Storage, WorkManager |
| `media.MediaOrphanSweeper` (extended) | Treats `linked/` files referenced by a linked row or pending download as in use | — |
| `alarm.RingSlot` / `RingingSession` (extended) | Merge instead of supersede (below); carry `origin` on snooze/dismiss; Notify-mode fire posts a notification instead of the ringing screen | — |
| `firebase/` | Rules for new command types and fields and `links`; `storage.rules`; `unpair` also deletes `households/{hid}/media/` | — |
| `ui` | Alarms list, Linked alarm sheet, Partner tab Join, Invite in alarm edit, ringing screen labels, Health check rows | `sync`, `data` |

## Flows

- **Join:** joiner taps Join on the Partner tab → `LinkRepository` creates the linked row from the owner's published alarm (mode Ring) → scheduler arms it → `links` published → owner shows "joined" and `MediaPublisher` uploads that alarm's media → `LinkSync` on the joiner fetches it.
- **Invite:** owner taps Invite in alarm edit → `INVITE_LINK` command → joiner notification with Join / Decline → Join runs the Join flow; Decline or no answer within 24 h rejects or expires it. Join is idempotent per `ownerAlarmId` and accepts any open invite for that alarm. Accepting an invite for an alarm no longer in the owner's doc fails with "Shawn deleted this alarm" and rejects the command.
- **Both ring:** each phone fires its own alarm on schedule. Owner's ring has `occurrenceAt = X`; the joiner's linked ring has the same X.
- **Snooze on either phone:** local snooze until T → `RingMirror` sends `SNOOZE{alarmId, X, T}` → the other phone's applier snoozes its matching ring until T, tagged `REMOTE`, and sends nothing back. Both resume at T and mirror again from there. Two snoozes that cross settle on the later T (validation rule 4).
- **Dismiss on either phone:** same shape with `STOP_RINGING{alarmId, occurrenceAt: X}`. Partner-tab Stop and the Notify-mode Stop action send the same command.
- **Partner-tab Stop on a joined alarm:** when the joiner's own copy of X is sounding or snoozed, Stop is a local dismiss of that copy, which sends the one `STOP_RINGING`. Otherwise it sends `STOP_RINGING` as before.
- **Early arrival:** phones fire seconds apart (the pre-fire check can delay a ring up to 3 s), so a command for X can land before X fires. It follows validation rule 5.
- **Notify mode:** the same ringing session with a different surface. It auto-silences after 10 min (a local dismiss, so it mirrors); Snooze clears the notification and re-posts it at T; it merges like any ring (below).
- **"Both of us" on the joiner:** sends `SKIP_NEXT` / `DISABLE` / `ENABLE` and records it in `link.pendingBoth`, which takes effect on the joiner at once (see Data model).
- **Owner skip on a joined alarm:** asks "Just me or both of us?". Both sets `skippedOccurrenceAt`, which the joiner copies. Just me sets `soloSkippedOccurrenceAt`, and the joiner still rings; when the joiner's ring is dismissed or snoozed, its command reaches the owner and is rejected by validation rule 2.
- **Pre-fire check on a linked row:** inside the same 3 s cap, also reads the owner's device doc. If the read succeeds and shows X no longer rings for the owner's alarm (shared skip, off, deleted, or the time moved so X isn't an occurrence), the ring is suppressed and `LinkSync` applies the doc. The owner's solo skip doesn't suppress. A failed or slow read rings as usual (I1). Doze makes this the common path: a device-doc change sends no FCM message, so without it a dozing joiner would miss an 11 pm skip until it next woke.
- **Merge:** when a fire finds the slot sounding, the new alarm is added to `RingState.merged` instead of superseding. One sound plays (the one already playing); the screen lists both labels. A *local* Snooze or Dismiss resolves every alarm in the ring, and `RingMirror` mirrors only the linked ones (and, on the owner side, only the joined ones). A *received* `STOP_RINGING` or `SNOOZE` for X removes only X from the ring; the ring keeps sounding while any member is left, and the screen shows "Shawn dismissed his 6:30". So the partner's dismiss never silences the joiner's own alarm. A received `SNOOZE` for one member splits it out: X moves to the snooze ledger as a parked snooze until T, and the others keep sounding. A snooze that resumes while the slot is sounding merges into that ring, like a fresh fire, instead of superseding it. A fire that finds the slot snoozed keeps today's behavior (the snooze is parked, the new alarm rings). The merged ring uses the full ringing screen if any member is in Ring mode, and is a Notify notification only when every member is a Notify-mode link.
- **Owner changes or deletes the alarm:** `LinkSync` updates the copy and re-arms, or removes it and notifies. Turning a joined alarm off turns it off for both (the owner's switch never asks). When a linked row's copied `enabled` goes from true to false, `LinkSync` notifies the joiner ("Shawn turned off his 6:30, so it's off for you too"), but only if `joinerEnabled` is true, the change didn't come from the joiner's own pending "both off" (checked before `pendingBoth` is cleared in the same pass), and the alarm is repeating or a one-shot whose occurrence is still in the future. A one-shot that turned itself off after ringing or after a solo skip posts nothing. Offline, the joiner rings on the last copy it saw. A time change while the joiner's copy is snoozed lets that snooze run out on the old X (both phones still match on it); the new time applies from the next occurrence.
- **Leave or unpair while a joined ring is sounding:** the ring finishes as a normal local ring with no further mirroring in or out; the row is removed afterwards. A mode change applies from the next fire.
- **Unpair:** all linked rows removed; household media deleted by the `unpair` function.
- **Locked boot:** Firebase waits for `USER_UNLOCKED` (`TandemStartup`), so before the first unlock a phone can ring its joined alarm but can't send or receive mirrors. `RingMirror` writes outgoing commands to a device-protected outbox (same pattern as `DeferredAlarmWrites`) and sends them after unlock; any that went stale are rejected by I3 on the other phone.

## Media shipping

- **What:** for each alarm with a link or an open invite, the owner uploads its wallpaper (already downscaled by `WallpaperImporter`) and ringtone audio (an imported file, or a system sound's bytes read through `ContentResolver`, since the joiner's phone may not have the same system sounds), and the Settings default wallpaper. Cap 15 MB per file; an oversized file is left out and the joiner falls through the resolution order.
- **Where:** Cloud Storage for Firebase, `households/{hid}/media/{ownerUid}/{sha256}`. Content-addressed, so re-uploads are no-ops and the joiner caches by hash.
- **Rules:** read only by household members; write only by `ownerUid` under its own folder; size and content type (`audio/*`, `image/*`) checked.
- **Joiner cache:** `filesDir/linked/{sha256}`, CE storage like the owner's own ringtones and wallpapers, so direct-boot behavior is the same as for own alarms (wallpapers are never available before first unlock; see `AlarmMirror`).
- **Cleanup:** after each upload pass the owner deletes its own hashes that nothing references.
- **Unreadable override:** a joiner override file that can't be read at fire time falls to the next step in the resolution order (the owner's per-alarm media), not straight to the system sound.

## UI

- **Alarms screen:** one list, own and joined, sorted by time. A joined row shows a partner chip ("Shawn's"), a link icon, a Ring/Notify tag, a time that can't be edited, and states such as "Shawn skipped" or "Skipped for you". Tap opens the **Linked alarm sheet**: Ring/Notify, message / sound / photo overrides (each defaulting to "Use Shawn's"), Skip ("Just me or both of us?"), Leave. The row's switch asks the same question.
- **Own rows:** a "<partner> joined" badge when linked. Alarm edit gets "Invite <partner>", or "<partner> joined" when already linked. Skip on a joined own alarm asks "Just me or both of us?"; a solo skip shows "Skipped for you". The joiner's row and Partner tab show it as "Shawn's skipping, you're still on".
- **Partner tab:** each partner alarm gets Join, or Joined (opens the sheet). Existing Skip / Off / Stop are unchanged. When the partner's ring is one of your own alarms that they joined, the banner adds "Your 6:30, joined". Stop then dismisses your own copy locally if it is sounding or snoozed (the mirror sends the one STOP), and otherwise sends STOP_RINGING with your alarm id and their ringId.
- **Ringing screen:** a joined ring shows "With Shawn" and the resolved label and photo; a merged ring lists every label. When the other phone stopped or snoozed it, the screen shows "Shawn dismissed" or "Shawn snoozed to 6:40" briefly before closing.
- **Notifications:** invite (Join / Decline), owner deleted a joined alarm, owner turned off a joined alarm, a "both of us" command rejected, Notify-mode ring (alarm category, Snooze / Stop).
- **Health check:** "Linked media downloaded", "Storage reachable".

## Error handling

- Owner's doc unreadable or offline: the joiner keeps ringing on its last copy (I1); mirroring catches up when the network returns.
- A mirror command that arrives after the other phone's ring ended (auto-silence, already dismissed) is rejected as stale and changes nothing.
- Media download fails: retried by WorkManager; the ring uses the next item in the resolution order. Upload fails: retried on the next publish pass; the joiner meanwhile falls through.
- Owner deletes a joined alarm while the joiner's copy is ringing: the ring finishes normally; the row is removed afterwards.

## Known limitations

- A phone that hasn't been unlocked since a reboot can't receive mirror commands, so it keeps ringing until dismissed locally or auto-silenced. Its own snooze and dismiss are sent after unlock.
- The joiner rings on the last copy of the owner's alarm it saw; a time change made while the joiner is offline takes effect when it next syncs, or at the pre-fire read if that succeeds.

## Testing

- `schedule` unit tests: next occurrence with the joiner's skips, `pendingBoth` and the owner's solo skip; occurrence-pinned `STOP_RINGING` and `SNOOZE` through every validation rule (skipped here in both directions, resolved inside `FIRE_GRACE`, crossing snoozes settle on the later T, merged member, early within 2 min, early beyond 2 min, stale, duplicate); `INVITE_LINK` expiry.
- Solo skip: published but not copied; joiner still rings and its STOP/SNOOZE is rejected on the owner; a solo-skipped one-shot turns off only after X; partner `SKIP_NEXT` upgrades it, partner `UNSKIP` leaves it.
- `pendingBoth`: takes effect at once, cleared on match, reject or expiry; "both off" then owner on brings the joiner back.
- Pre-fire owner-doc read: suppresses on a successful read showing a shared skip, off, delete or moved time; rings on failure or timeout; ignores a solo skip.
- Merge: a received SNOOZE splits one member into a parked snooze; a resuming snooze merges into a sounding ring; Ring wins over Notify in a merged ring. Notify mode auto-silences and re-posts at T.
- Linking: Join is idempotent and accepts an open invite; accept after the owner deleted the alarm fails; Leave or unpair mid-ring finishes the ring without mirroring.
- Ringing tests: merge into a sounding ring; local merged dismiss resolves all and mirrors only linked alarms; received STOP for one member leaves the others sounding; `REMOTE` origin sends nothing (I4); Notify-mode fire posts the notification and its actions send commands.
- `LinkSync`: owner changes time/days/enabled/skip; owner deletes; unpair; a sync never overwrites `joinerEnabled` or the joiner's skip, except that a re-enabled one-shot resets `joinerEnabled` (a repeating alarm doesn't). Turn-off notification: posted for a repeating alarm and for a future one-shot; not posted when `joinerEnabled` is false, when the joiner's own "both off" is pending, or for a one-shot that turned itself off after ringing or a solo skip. `deviceDocMap` excludes linked rows; applier rejects partner commands aimed at a linked row. `LinkedMediaCache`: resolution order, missing file, sweeper keeps referenced `linked/` files.
- Emulator tests: Firestore rules for new command types, `links` and `media` fields; Storage rules (member-only read, owner-only write under own folder, size cap, content type).
- `TwoDeviceSmokeTest` additions: join → both ring → B snoozes and A snoozes to the same T → A dismisses and B stops; invite → accept; media shipped and used.
- On-device checklist additions: mirrored dismiss while the other phone dozes; joined alarm rings with both phones offline; merged rings at the same minute; a joined alarm still rings after a reboot before unlock; dismiss a joined ring before first unlock, then unlock, and the other phone's ring stops (or the command is rejected as stale).

## Manual prerequisites (Shawn)

1. Enable Cloud Storage for Firebase on the `tandem-alarm` project (Blaze is already on).
2. Deploy: `firebase deploy --project prod --only firestore:rules,storage,functions` from `firebase/`.

## Build order (for /plan)

1. **Contract (serial):** model changes (`AlarmLink`, `RingState.occurrenceAt`/`merged`, command types), Room migration, `ownAlarms`/`linkedAlarms` flows, device doc mapping, Firestore and Storage rules with tests. Storage tooling: `storage` emulator in `firebase.json`, the `firebase-storage` dependency, `Firebase.storage.useEmulator` next to the others in `TandemApp`.
2. Then largely parallel: `schedule` validation · ringing merge + origin + Notify mode · `LinkSync` + `LinkRepository` · `RingMirror` + applier · media publish/cache · UI.
3. Integration: smoke test additions, README and checklist updates.
