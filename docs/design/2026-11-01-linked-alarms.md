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

**Owner's "just me" skip.** The owner's `Alarm` gains `soloSkippedOccurrenceAt`. It is published in `alarms[]` for display ("Shawn's skipping, you're still on"), but `LinkSync` never copies it into `ownerSkippedOccurrenceAt`, so the joiner's copy stays armed. On the owner, `nextOccurrence` skips both it and `skippedOccurrenceAt`. A one-shot with a solo skip turns off once X plus the 2-minute grace has passed (through skip expiry, see Decisions made during the build), not when the skip is set, because an earlier `enabled = false` would be copied and silence the joiner. A partner `SKIP_NEXT` for the same X upgrades it to a shared skip; a partner `UNSKIP` clears only the shared skip; the owner's own un-skip clears either. On an alarm nobody has joined, Skip doesn't ask and sets the shared skip as today.

**Owner's late-ring answer.** The owner's `Alarm` gains `lateRing = {occurrenceAt, ring}`, the answer to the "hasn't picked up the change" prompt (see Decisions made during the build). It is the owner's own data, stored only before `occurrenceAt`, and `LinkSync` copies it to the joiner as an owner-copied field. An own row's `lateRing` never affects the owner's own fires.

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
  links: [{ ownerAlarmId, mode, hour, minute, days }]  // written by the joiner; hour, minute and days are
                                                    // the schedule it copied
  defaultWallpaper: { sha, size } | null
  alarms[].media: { wallpaper: {sha, size}?, ringtone: {sha, size, title}? }
  alarms[].soloSkippedOccurrenceAt                  // display only; never copied by LinkSync
  alarms[].lateRing: { occurrenceAt, ring }         // the owner's answer to the late-ring prompt; copied by LinkSync
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

A received `STOP_RINGING{occurrenceAt: X}` or `SNOOZE{X, T}` is checked in the order below, except that rule 2 runs after rule 4 (see Decisions made during the build). Validation looks at the slot's ring, its `merged` members and the snooze ledger, not only the slot's single ring as `validate` does today.

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
| `sync.LateRingPrompts` (new, owner side) | After an edit moves a joined alarm earlier, decides when to ask whether the partner should still ring late; posts and cancels the prompt; stores the answer | `PartnerRepository`, `AlarmRepository`, WorkManager |
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
- **Pre-fire check on a linked row:** inside the same 3 s cap, also reads the owner's device doc. If the read succeeds and shows X no longer rings for the owner's alarm (shared skip, off, deleted, the time moved later, or the day removed), the ring is suppressed and `LinkSync` applies the doc. If it shows the time moved earlier the same day, to a time already passed and not skipped here, X rings late as a normal ring (see Decisions made during the build). The late ring is suppressed only when the owner's `lateRing` on the row is for exactly X and says `ring = false`; `ring = true`, no answer, or an answer for another X rings late. The owner's solo skip doesn't suppress. A failed or slow read rings as usual (I1). Doze makes this the common path: a device-doc change sends no FCM message, so without it a dozing joiner would miss an 11 pm skip until it next woke.
- **Owner moves a joined alarm earlier:** a save that changes hour, minute or days and moves the next occurrence X earlier on its own date enqueues a one-off check two minutes later. The check reads the partner's doc from the server. If the joiner has not picked up the change, the owner gets a prompt: "Sam's phone hasn't picked up the change to 6:00 yet. If it still hasn't by 6:30, should it ring then?", with **Ring at 6:30** and **Skip**. The answer is stored on the owner's alarm as `lateRing` and published, and the joiner's ring-late rule reads it (see Pre-fire check above). Any schedule-changing save removes a posted prompt and clears the answer; a save that is not an earlier move also cancels the pending check, so the newest edit wins. A label-only save touches nothing.
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
- **Partner tab:** each partner alarm gets Join, or Joined (opens the sheet). Existing Skip / Off / Stop are unchanged. When the partner's ring is one of your own alarms that they joined, the banner adds "Your 6:30, joined". Stop then targets that alarm, whether it is their primary ring or a merged member: it dismisses your own copy locally if it is sounding or snoozed (the mirror sends the one STOP), and otherwise sends STOP_RINGING with your alarm id and the member's occurrence. Their own alarms in the ring keep sounding.
- **Ringing screen:** a joined ring shows "With Shawn" and the resolved label and photo; a merged ring lists every label. When the other phone stopped or snoozed it, the screen shows "Shawn dismissed" or "Shawn snoozed to 6:40" briefly before closing.
- **Notifications:** invite (Join / Decline), owner deleted a joined alarm, owner turned off a joined alarm, a "both of us" command rejected, the owner's late-ring prompt (Ring at / Skip), Notify-mode ring (alarm category, Snooze / Stop).
- **Health check:** "Linked media downloaded", "Storage reachable".

## Error handling

- Owner's doc unreadable or offline: the joiner keeps ringing on its last copy (I1); mirroring catches up when the network returns.
- A mirror command that arrives after the other phone's ring ended (auto-silence, already dismissed) is rejected as stale and changes nothing.
- Media download fails: retried by WorkManager; the ring uses the next item in the resolution order. Upload fails: retried on the next publish pass; the joiner meanwhile falls through.
- Owner deletes a joined alarm while the joiner's copy is ringing: the ring finishes normally; the row is removed afterwards.

## Decisions made during the build

These were settled while the units were built and integrated. Where one differs from the text above, this section wins.

**Ring command validation**

- The checks run in the order 1 expired, 3 already resolved, 4 live, 2 skipped here, 5 early, 6 reject. Rule 2 comes after rule 4 because a ring that is sounding must stay stoppable even when the row has changed under it: the owner's one-shot disables itself on dismiss, and a joiner's copy can pick up `enabled = false` before the owner's STOP arrives. "Skipped here" only applies to an X that isn't live.
- The early window is X − `FIRE_GRACE` up to, but not including, X + `FIRE_GRACE`. A dismiss sent at X + 1 s still reaches a phone whose fire is in its pre-fire check; one at X + `FIRE_GRACE` does not.
- An `INVITE_LINK` is valid until it expires, even when the joiner has no row for the alarm, since a joiner never has one before joining. The applier checks the owner's alarm against the partner's published state.
- `SKIP_NEXT` and `DISABLE` pinned to an occurrence accept either view of the owner's alarm: the shared one, which ignores the solo skip, and the published one, which the Partner tab sends. A partner `UNSKIP` counts only the shared skip, and an `UNSKIP` at X masks only the owner's skip on X, never the joiner's own.
- Any non-ring command aimed at a linked row is rejected as "alarm not found".
- Ring commands that map onto a row pending removal by Leave are rejected with "left this alarm". Rows removed because the owner deleted the alarm or the pair ended still accept them, since such a row may still be ringing.

**Scheduling**

- The weekly search window is 29 days. Three skips (own, owner's, a pending "both") can stack on one weekday, and the worst case lands on day 28.
- A solo-skipped one-shot turns off through skip expiry, which runs once X + `FIRE_GRACE` has passed. The fire path doesn't touch the solo skip. Until the skip expires, `nextOccurrence` returns nothing for that one-shot, so tomorrow's time doesn't ring.
- A linked one-shot that turns itself off after ringing uses `setJoinerEnabled(false)` and leaves the copied `enabled` alone. Otherwise `LinkSync` would see the owner's alarm still on and re-arm it.
- Re-enabling a one-shot with an expired solo skip runs the expired-skip cleanup first, so the alarm doesn't come back on already off.
- A linked fire is suppressed when X no longer falls on the row's schedule (hour, minute and days), checked in the zone X was armed in. The fire intent carries that zone. A fire without a zone rings (I1). Own alarms are never suppressed this way: a skip is only a stored instant, and a zone change or DST shift alone must not silence a ring.
- The exception is a time moved earlier the same day. When the row's schedule has an occurrence on X's local date that is earlier than X (built the way the scheduler builds it, so a DST-gap time is shifted the same way), the joiner learned of the move too late, and X rings late as a normal ring with `occurrenceAt = X`. A time moved later the same day rings at the new time, and a removed day or an alarm turned off still suppresses. A one-shot whose owner copy already rang at the new time and turned off is suppressed by `enabled = false` before this check. A stored skip at the moved-to time suppresses the late ring: the joiner's own skip, the owner's shared skip (`ownerSkippedOccurrenceAt`) or a pending "both" skip. The owner's solo skip still never counts. A late ring isn't the owner's occurrence, so it doesn't use up a linked one-shot: the session records it in the fired ledger's device-protected late-ring key, and dismissing it, auto-silencing it, or stopping it as a merged, snoozed or parked member leaves `joinerEnabled` alone. The row then follows the owner's `enabled` and next occurrence. A ledger written before this key existed has no late rings. The late ring's mirrored STOP or SNOOZE is stale on the owner, which rejects it ("already rang or changed") without a notification; the joiner only logs the rejection. Shawn's call, 2026-11-01. The owner is asked whether the partner should still ring (see Late-ring prompt under Sync).

**Ringing**

- A Notify ring that can't alert rings in full (I1). That covers notifications off, the permission denied, the Notify channel blocked or set below default importance, its sound removed or the phone's default notification sound set to None, and a post that throws. A channel setting is neither a stored command nor a successful pre-fire check, so I1 requires a ring.
- The Notify channel plays the default notification sound once, with alarm usage and the alarm category. Tapping it opens the app. A Notify alarm joining a sounding Notify ring alerts again; a Ring member joining upgrades the ring to the full screen and sound.
- Each alarm in a merged ring gets its own full 10 minutes of auto-silence, counted from when it joined, so the ring ends 10 minutes after the last merge. Auto-silence checks the ring is unchanged before it dismisses, so a member that joined while the dismiss waited isn't resolved without having sounded.
- A `STOP_RINGING` by `ringId` received for a merged ring resolves only the primary, which is the alarm the partner saw. The rest are promoted under the same `ringId` and keep sounding. A local action resolves the whole group.
- A merged ring that is snoozed locally keeps one ledger entry per member but shows one notification listing every label. Its Dismiss resolves the whole group as one local action. A remote action, or a local one on a single occurrence, resolves only its own member.
- Linked sound candidates from the media cache pass through unchanged when the ring's primary is a linked alarm and they already contain the system alarm sound; the adapter then adds nothing. Otherwise the audio adapter appends the system defaults, as it does before first unlock.
- The media cache puts the system alarm sound before the joiner's default itself, so the design's order holds.
- A STOP by occurrence posts no notification on the receiving phone, since the ringing screen shows "Shawn dismissed". That covers a mirrored dismiss and the owner's Partner-tab Stop on its own alarm. A Partner-tab STOP by `ringId` does post one.
- The joiner publishes a linked ring under the owner's alarm id, in `ringing` and in `merged`. On the owner, the Partner tab shows "Your 6:30, joined". Stop targets that alarm A whether it is the joiner's primary or a merged member: a local dismiss if the owner's own copy of A is live (its mirror sends `STOP_RINGING{A, X}`), otherwise `STOP_RINGING` with A and the member's occurrence. The joiner maps A to its linked row and resolves only that member, so its own alarms in the ring keep ringing. A repeat tap after that STOP still dismisses the owner's copy locally if it has started sounding; the mirror's second `STOP_RINGING{A, X}` reaches a member already resolved and is rejected. Several of the owner's alarms in one ring stop one per tap. A partner ring with none of the owner's alarms is still stopped by `ringId`.
- A joiner that left while ringing leaves the owner's Stop rejected with "left this alarm".

**Sync**

- `RingingCommandListener` stays attached whenever any `RingState` exists, sounding or snoozed, on both phones. Two snoozes that cross (rule 4 settles on the later T) and a partner's STOP during a snooze have to land within seconds, not at the next FCM message or pull. The owner needs it too, because the joiner's `SNOOZE` and `STOP_RINGING` target the owner's ring. The cost is one Firestore listener for the length of the snooze. Parked snoozes with no `RingState` still rely on FCM and the pull.
- A "both" command stays pending on the server until the owner answers it, applying or rejecting it, and `pendingBoth` stays set on the joiner until `LinkSync` sees the owner's state match. An `INVITE_LINK` stays pending until the joiner answers Join or Decline, or it expires; it is notified once per invite, even across a restart, until the command expires. A command listener that fails is not an answer: the wait listens again after 5 s, doubling to a 60 s cap, until the command is answered or expires, for invites and "both" commands alike. It stops early when the household changes, and a "both" watch also stops when its row no longer holds the command; neither posts a rejection, with one exception. When the phones re-pair into a new household with the same partner, the linked row survives but the old command can't be answered, so the watch clears `pendingBoth` and posts the rejection "the pairing changed". An applied "both" command clears once a newer owner doc arrives.
- A "both" command's 24 h window runs from `PendingBoth.createdAt`, so a restart doesn't extend it. The Room migration to v3 adds that column and stamps rows already pending with the upgrade time.
- The joiner writes `pendingBoth` before it creates the command, using the command's own id, and undoes it if the create fails.
- Open invites persist in the sync DataStore (command id, alarm id, expiry), so the owner still has them after a restart. Media upload for an invited alarm resumes with them. The set of invites already notified on the joiner persists the same way.
- Household and partner listeners resubscribe after an error, waiting from 2 s and doubling to a 60 s cap. The wait resets to 2 s only after a listener has stayed up for the current wait. A value alone doesn't reset it, because Firestore can raise a cached snapshot before the server rejects the listen. These numbers apply to the household and partner listeners only; the ringing listener retries every 1 s and command waits from 5 s, doubling to 60 s. A null from either means "unknown" and removes nothing.
- Unpairing is confirmed on the server before any linked row is removed: a server read must show either no household on the user doc, or a household that is not a valid pair with this user. A cached answer, a timeout or an error counts as unknown and is retried. The check runs only after a household was seen and then stayed null for 30 s. A null household alone removes nothing. A re-pair removes only the old partner's rows.
- Leave is a deferred, durable removal and is never mirrored. Removal after the owner deletes the alarm waits while the row is ringing, snoozed or merged, and survives process death.
- A pre-fire read of the owner's doc uses `Source.SERVER`, so a cache-only answer can't suppress a ring. It runs at the same time as the command pull under the one 3 s cap.
- Notification texts avoid pronouns, because the app doesn't know the partner's. They read "Sam turned off the 6:30 you joined, so it's off for you too" where this doc says "his 6:30".
- **Late-ring prompt.** When the owner moves a joined alarm earlier and the joiner's phone has not picked up the change, the owner chooses whether the joiner should still ring at the old time. Shawn's call, 2026-11-01. The build details:
  - The joiner confirms its copy in `links[]` (`hour`, `minute`, `days`), parsed all-or-nothing. A missing or invalid part means unknown, which is a partner on an older build, never "not confirmed". Unknown never prompts, since an older joiner would not read the answer either.
  - The check runs two minutes after the edit, unique per alarm with REPLACE and a network constraint. It reads the partner's doc with `Source.SERVER`. A failed read retries a few times (fewer than 4 attempts) and then gives up, which leaves the default: the joiner rings.
  - The occurrence asked about is Xj, the next occurrence of the joiner's copied schedule, not the old X on the owner's side. With one edit they are the same. After a second edit before the joiner syncs, Xj is the time the joiner will actually fire. The prompt appears only when the owner's current schedule has an earlier time on Xj's date and no answer is stored for Xj. No prompt when the copy already matches.
  - The answer is `Alarm.lateRing = {occurrenceAt: Xj, ring}` in Room (migration to v4, two nullable columns). Skip stores `ring = false` and Ring stores `ring = true`. A dismissed prompt stores nothing, and the default is that the joiner rings.
  - It expires at Xj + `FIRE_GRACE`. It is published while that instant is in the future, skip expiry clears it at the same cutoff, and the state publisher republishes at that boundary.
  - A label-only save leaves the answer and the prompt alone. A save that changes hour, minute or days clears the answer and removes a posted prompt, so the next prompt asks again.
  - The prompt is removed by the system at Xj (`setTimeoutAfter`). A watcher on the partner's doc also removes it when the partner's link shows the owner's current schedule, or the link or alarm is gone. The watcher runs only while the owner's app process is alive, because a device-doc change sends no FCM message.
  - The joiner copies `lateRing` through `LinkSync`. At the old X it suppresses the late ring only for `ring = false` on exactly X. Moved later, day removed and off still suppress as before.
  - The direct-boot mirror (`AlarmMirror`) does not carry `lateRing`. Before first unlock there is no pre-fire read anyway, and a late ring rings (I1).
- The state publisher republishes at a solo skip's X and again at X + `FIRE_GRACE`, applying skip expiry when it publishes, so the partner's display clears without an edit.

**Media and backend**

- Storage uploads need a paired household (two members). Without that, any signed-in user could create a solo household and use Storage as free hosting.
- Re-uploading an existing hash is a `create`, so it passes through the owner, size and type checks. Metadata updates are denied.
- The joiner treats hashes from the partner's doc as untrusted: 64 lowercase hex characters, and a downloaded file must match its hash. A mismatch isn't retried; a missing file is retried five times. Owner media is used only when the row's owner is the partner whose refs were stored.
- `unpair` deletes the household's media after the Firestore cleanup, and a failure there is logged without failing the unpair.
- Override files count as references in `MediaOrphanSweeper`, so a joiner's override isn't swept after an hour.
- Room columns for the link are flat and nullable, because `ALTER TABLE ADD COLUMN` can't add an embedded NOT NULL column without a default.
- Sound overrides that can't be read fall to the next candidate, and the bundled tone is always last.

## Known limitations

- A phone that hasn't been unlocked since a reboot can't receive mirror commands, so it keeps ringing until dismissed locally or auto-silenced. Its own snooze and dismiss are sent after unlock.
- The joiner rings on the last copy of the owner's alarm it saw; a time change made while the joiner is offline takes effect when it next syncs, or at the pre-fire read if that succeeds.
- This applies to a one-shot the owner skipped just for themselves. If the owner's clock runs 2 minutes or more fast, the owner's skip expiry has already turned it off by the joiner's X. The joiner's pre-fire read, or its normal listener copy, then disarms the joiner's ring.
- A joiner phone that was off at X and syncs after X + `FIRE_GRACE` gets the "turned off" notice for a one-shot that has simply expired.
- After a reboot within the overdue window, a replay of an occurrence that the owner has since moved rings once at the old X, because replays carry no zone and skip the schedule check.
- If the owner moves the time earlier and the joiner only learns of it at the old X, the joiner rings late at X when the owner answers Ring or doesn't answer. The two rings aren't mirrored: the joiner's STOP or SNOOZE is rejected on the owner as stale.
- The late-ring prompt is removed on the partner's confirmation only while the owner's app process is running. Otherwise it stays until Xj, and answering then is harmless.
- A joiner on an older build is treated as unknown, and the owner isn't asked.
- A joiner that hasn't been unlocked since a reboot ignores a Skip, because the direct-boot mirror doesn't carry `lateRing` and the late ring rings.

## Testing

- `schedule` unit tests: next occurrence with the joiner's skips, `pendingBoth` and the owner's solo skip; occurrence-pinned `STOP_RINGING` and `SNOOZE` through every validation rule (skipped here in both directions, resolved inside `FIRE_GRACE`, crossing snoozes settle on the later T, merged member, early within 2 min, early beyond 2 min, stale, duplicate); `INVITE_LINK` expiry.
- Solo skip: published but not copied; joiner still rings and its STOP/SNOOZE is rejected on the owner; a solo-skipped one-shot turns off only after X; partner `SKIP_NEXT` upgrades it, partner `UNSKIP` leaves it.
- `pendingBoth`: takes effect at once, cleared on match, reject or expiry; "both off" then owner on brings the joiner back.
- Pre-fire owner-doc read: suppresses on a successful read showing a shared skip, off, delete, a time moved later or a removed day; rings late at X on a time moved earlier the same day, including a one-shot and a DST day, unless a stored skip covers the moved-to time; a late one-shot stays on after its dismiss or auto-silence; rings on failure or timeout; ignores a solo skip.
- Late-ring prompt: the edit hook enqueues a check only for an earlier move and cancels a stale one (label-only saves touch nothing); the check prompts on an unconfirmed copy, not on a match, an unknown copy or a moved-later time, and pins the prompt to the joiner's copy after a second edit; Ring and Skip store the answer only before Xj and an edit clears it; the joiner suppresses the late ring only for `ring = false` on exactly X; `links[]` copy parsing is all-or-nothing.
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
