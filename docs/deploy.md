# Deploying the Firebase backend

This covers the first real deploy of Firestore rules and Cloud Functions to the `tandem-alarm` project. The `default` alias in `firebase/.firebaserc` is `demo-tandem` (emulator only); `prod` maps to `tandem-alarm`.

## Prerequisites

- A Firebase project with ID `tandem-alarm`.
- The Blaze (pay-as-you-go) plan. Cloud Functions for Firebase requires it to deploy (see https://firebase.google.com/docs/functions). The free no-cost tier still applies.
- Firestore enabled (Native mode).
- Anonymous sign-in enabled under Authentication.
- An Android app registered in the project with package name `me.shawnsquire.tandem`. Download its `google-services.json` and store the file contents as the `GOOGLE_SERVICES_JSON` GitHub Actions secret. The release workflow writes it over `app/google-services.json`.
- Node 22.12 or newer and npm. Use the npm `firebase-tools` through `npx`. A system-wide `/usr/bin/firebase` that bundles Node 20 cannot load the functions.

## Deploy

Build the functions first:

```
cd firebase/functions
npm ci
npm run build
```

Log in (a person does this in a browser; do not script it):

```
npx firebase-tools login
```

Then deploy from the `firebase/` directory. The deploy command is:

```
firebase deploy --project prod --only firestore:rules,functions
```

Here `firebase` means npm firebase-tools on Node 22.12 or newer, run through `npx firebase-tools`, not a system `/usr/bin/firebase`. Type this:

```
cd firebase
npx firebase-tools deploy --project prod --only firestore:rules,functions
```

This publishes `firestore.rules` and the functions in `firebase/functions`:

- `onCommandCreated`
- `createPairCode`
- `joinHousehold`
- `unpair`

## Check afterwards

- Firebase console, Build > Functions: all four functions are listed with no deploy errors.
- Firebase console, Firestore > Rules: the published rules match `firebase/firestore.rules` and show a recent publish time.
- `npx firebase-tools functions:list --project prod` shows the same four functions.
