# Tandem Alarm — Privacy Policy

_Effective date: November 2, 2026_

Tandem Alarm is an alarm clock for two people, one phone each. Each phone keeps its own
alarms and rings them itself. Once you pair two phones, each can see the other's alarms
and skip, turn off or stop them. This policy describes the data the app handles.

## What stays on your phone

Your alarms, settings and sounds live on your phone. If you never pair, nothing about
them is sent anywhere. Nothing else is sent until you show a pairing code or enter one.
The three items under "On every start" in the next section are sent regardless.

## What leaves your phone, and why

**On every start, paired or not:**

- **Anonymous account ID.** The app signs you in anonymously with Firebase
  Authentication. This random ID is how the app tells the two phones apart. It is not
  connected to your name, email or any real-world identity, and the app never shows it.
- **Push token.** The app asks Firebase Cloud Messaging for a token, a random ID for this
  install, so the paired phone's commands can reach you while the app is closed.
- **Firebase installation ID.** Firebase Cloud Messaging uses Firebase Installations,
  which creates a random ID for this install.

**Only once you show a pairing code or pair** (pairing is optional):

- **Pairing records.** Showing a pairing code creates a household record (the member IDs
  and a creation time), a user record that holds the household ID, and the code itself,
  which can be used for 10 minutes and holds the household ID, your ID and its expiry time. Joining
  the other person's code adds you to their household.
- **Display name.** The name set in Settings. If none is set, it is the phone's model
  name. The paired phone shows it.
- **Alarms.** For each of your alarms: label, time, days, on or off, next ring time,
  skips, the answer to a late-ring question, vibration and sound settings, text position
  and wallpaper framing. For an alarm you join from the other phone, a link record holds
  the owner's alarm ID, ring or notify mode, and the time and days copied from it. The
  paired phone shows them. It cannot change your alarm times.
- **Ring state.** While an alarm rings or is snoozed: which alarm, when it started and
  when the snooze ends, so the other phone can follow a joined alarm.
- **Commands.** Skip, undo skip, turn off, turn on, stop ringing, snooze and invite. Each
  holds the sender and target IDs, the alarm, the ring time, and a status. The target
  phone also gets the command as a push notification.
- **Photos.** The wallpaper of an alarm that is joined or has an open invite, and your
  default wallpaper. The paired phone shows it when the joined alarm rings.
- **Sounds.** The sound of an alarm that is joined or has an open invite, with its title.
  The paired phone plays it.

Photos are re-encoded as a screen-sized JPEG before upload, which removes location and
other metadata. Sounds are sent as the original file, so any tags inside the file go
with it.

Each change to an alarm or ring state overwrites the last. The app keeps no history of
them. Commands expire after 24 hours, which means the app ignores them. They are not
deleted until you unpair.

## Who can see it

- **The paired phone.** Everything in the list above that starts at pairing. Nobody else
  using the app can see it.
- **Google Firebase.** The app stores and delivers this data with Firebase (Cloud
  Firestore, Cloud Storage, Cloud Functions, Cloud Messaging, Authentication), as a
  service provider. Data is encrypted in transit with HTTPS.

## What Firebase itself collects

Firebase collects some technical data when the app talks to it: your IP address, the
app's user agent, and the app ID. Firebase uses it for its own security and to prevent
abuse. The app does not use it, and nothing derives a location from the IP address. See
[Firebase's description](https://firebase.google.com/docs/android/play-data-disclosure).

Firebase also keeps server logs of the app's backend functions. These hold anonymous and
household IDs, and Google Cloud keeps them for up to 30 days.

## What the app does NOT do

- No ads.
- No analytics and no crash reporting.
- No selling of data, and no sharing with anyone beyond Firebase, which hosts it, and the
  paired phone.
- No location and no contacts.

## Deleting your data

**Unpair, in the app.** On either phone, Unpair deletes this from the server for both
people:

- the household and both people's user records,
- both phones' device records, which hold the display names, alarms, ring state and the
  push-token copies,
- every command, and
- every shared photo and sound.

Normally all of it is gone. If the server hits an error during cleanup,
some of it can stay behind. Email me (below) and I will delete it.

**What stays after Unpair:**

- Each phone's anonymous sign-in record in Firebase Authentication. It holds a random ID,
  no name, and the app never deletes it.
- Pairing codes that were never used. The app does not delete them, and they do not
  expire out of the database, except that joining another phone's code deletes the codes
  you showed. A code holds the household ID, the creator's ID and an expiry time.
- Firebase's own registrations: the push token and the installation ID. The app does not
  delete them.
- Server logs, for up to 30 days.
- Records from a pairing code that was shown but never used. Showing a code creates a
  one-member household record (your member ID and a creation time) and a user record
  (the household ID only). Until two
  phones are paired the app offers no Unpair, so these stay. If you later join the other
  person's code, they are deleted.

**Uninstalling the app without unpairing deletes nothing on the server.** Unpair first.

**To have anything that is left deleted,** email
[shawn@shawnsquire.me](mailto:shawn@shawnsquire.me). Tell me what you want removed, and,
if you can, the display name or pairing details that identify it. The anonymous ID is
not shown in the app, so I may not be able to find a record without them.

## Children

Tandem Alarm is a general-purpose alarm clock and is not directed at children under 13.
The app asks for no age and collects nothing beyond the data described here. If you
believe a child has used it with data you want removed, email the address below.

## Changes to this policy

If the app starts handling data differently, this page changes first and the effective
date at the top is updated.

## Contact

Kautiontape — [shawn@shawnsquire.me](mailto:shawn@shawnsquire.me)
