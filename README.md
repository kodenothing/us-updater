# Us — Android couples app

Us is a private shared space for two people, built with Kotlin, Jetpack Compose, Firebase Authentication, Cloud Firestore, WorkManager, and Firebase Cloud Messaging. Shared photos are stored as small Firestore binary documents; Firebase Storage is not used. The project is designed to keep the shared-data and photo features on Firebase Spark. Reliable server-sent push notifications through Cloud Functions remain optional and require Blaze.

## Android configuration

The supplied toolchain is preserved:

- `compileSdk = 34`
- `targetSdk = 34`
- `minSdk = 26`
- Kotlin `2.0.20`
- Android Gradle Plugin `8.5.2`
- Firebase BoM `33.4.0`
- Gradle distribution `8.7`

The latest published Android release is `2.6.0` (`versionCode = 24`). The next release candidate is UsApp `2.7.0` (`versionCode = 25`).

### Included in UsApp 2.7.0

- More durable background synchronization for the Our Instant home-screen widget.
- Per-member gender settings in **Settings → My Profile**.
- Persistent Our Instant photo history.
- An Instagram-style Our Instant gallery with personal/partner tabs, date grouping, and a swipeable photo viewer.
- Independent **My Instant** and **Partner Instant** widgets alongside the existing shared **Our Instant** widget.

See `RELEASE_NOTES_2.7.0.md` for details and Android background-work limitations. The previous release notes remain in `RELEASE_NOTES_2.6.0.md`.

## Visual direction

The interface uses a warm, editorial style: soft ivory/blush surfaces, lavender/rose/mint/peach/sky accents, rounded cards, generous spacing, quiet transitions, and original copy and components. Main navigation contains Home, Calendar, Notes, Memories, Lists, and Activity. A compact hamburger drawer provides Our Instant, Our Map, and Settings, while Us Chat remains the right-side header action. The Home screen includes the days-together counter, next shared plan, Daily Us entry point, and shortcuts to the couple's spaces.

## Features

### Existing Us foundation

- Anonymous Firebase sign-in, email/password account recovery, and one-to-one pairing.
- Secure 12-character invite codes; legacy 8-character codes remain accepted.
- Pairing QR/code appears only on a dedicated private invite screen while waiting. After the second phone joins, the invite screen crossfades into Home. The invite code is not shown on Home or in Settings. The pending invite screen has both an in-app back action and Android system Back; cancelling removes only the still-unpaired invite, never a paired shared space.
- QR pairing, days-together counter, to-do and bucket lists, shared calendar, love notes, local reminders, themes, per-member nicknames, and branded splash screen.
- **Our Map:** MapLibre Native Android with OpenFreeMap/OSM basemap. Each partner independently opts in to live location sharing. Only the latest location is stored; no location history is recorded. Foreground sharing stops when Us leaves the foreground unless the user explicitly enables screen-off sharing. Distance is straight-line, not road distance.
- A separate partner-device web tester that uses its own anonymous Firebase identity. It cannot create a couple, generate the primary invite, or unpair/delete the shared space.
- Firestore-only storage for new memory photos; no Firebase Storage SDK or bucket is required.

### Roadmap 1 — Reliability and recovery

- **Encrypted export:** creates a password-protected `.usbackup` using PBKDF2-HMAC-SHA256 key derivation and AES-256-GCM authenticated encryption. Export includes couple settings, lists, calendar events, love notes, activity records, Daily Us prompt days and your own private answers/check-ins, and synced memory images/thumbnails.
- **Restore preview:** decrypts and validates the backup before changing Firestore. It checks the backup schema, couple code, and member IDs. Existing record IDs are skipped to reduce accidental duplicates. Photo bytes are restored to Firestore Blob documents.
- **Backup safeguards:** password required (10+ characters), bounded backup size, ZIP path validation, decompression limits, and image size checks. Keep the password separately; it cannot be recovered.
- **Mutual deletion approval:** the shared couple and its data are not permanently deleted until both members approve. The second approval allows the app to clean up lists, plans, notes, memories and their image documents, activity history, Daily Us records, and device records.
- **Bounded Firestore cache:** configured to 100 MiB. App-private original/thumbnail files support offline photo viewing without an unlimited Firestore cache.
- **Firestore rules tests:** emulator-based tests cover member access, member-only profile changes, answer privacy, memory Blob/thumbnail limits, append-only activity, and two-partner deletion consent.

### Roadmap 2 — Memories 2.0

- **Thumbnails:** compressed JPEG previews (up to 80 KiB) are stored separately from the full image Blob. The gallery loads previews first and fetches the original only when opened.
- **Month/year organization:** memories are grouped by month and year.
- **Favorites:** either partner can star a memory; the Favorites filter is shared.
- **Caption search:** search loaded memory captions, with a prompt to load older pages when you want to search farther back.
- **Pagination:** the gallery loads 36 records at a time and fetches older pages as you scroll.
- **Photo export:** save a shared photo to the phone through Android's document picker.
- **Free-tier constraints:** full images are compressed to at most 700 KiB and thumbnails to 80 KiB to stay under Firestore's 1 MiB per-document limit. This is a modest shared album, not unlimited full-resolution or video storage.

### Roadmap 3 — Activity Center

- A sixth main tab shows a shared timeline of the latest 200 activity records.
- Filters group entries into Lists, Plans, Notes, Memories, and Daily Us.
- Each entry shows which partner acted, a short title/summary, and a local timestamp.
- Tapping an entry opens its related app area. Activity records are append-only from the client; only the mutual-deletion flow can remove them.
- Existing list, calendar, note, and memory writes create activity entries. Daily Us answers and check-ins are added to the same timeline.
- The partner-device web tester has a matching Activity Center view.

### Roadmap 4 — Daily Us

- Both partners see the same date-based question. The prompt is selected deterministically from the date, and the daily prompt document is shared in Firestore.
- Each partner submits their own answer. An answer remains hidden from the other person until that reader has submitted an answer of their own. Each member can edit their own answer later.
- A daily mood check-in supports Happy, Loved, Calm, Tired, Stressed, or Low, with an optional short note. Each partner can see the other's check-in.
- Completion indicators show when both partners have answered and checked in.
- Answers and check-ins are written with activity records. The partner-device web tester can submit and view the same shared Daily Us data.
- Daily Us uses Firestore only; it does not require Cloud Functions, Firebase Storage, or Blaze. When the app opens offline, it can still attach listeners and pending writes will sync when connectivity returns. Creating a new date's prompt may need a connection the first time it is initialized.

### Roadmap 5 — Production security and polish

- **Firebase App Check on Android:** debug builds use the Firebase App Check debug provider; release builds use Play Integrity. The release provider is installed before the app initializes Firestore.
- **Web tester App Check:** an optional reCAPTCHA Enterprise site-key field is available. If you plan to keep using the tester after enabling Cloud Firestore App Check enforcement, register its Firebase Web App and supply its site key; otherwise the tester's Firestore requests may be rejected.
- **Transport hardening:** Android cleartext traffic is disabled in the manifest.
- **Firestore authorization:** rules restrict reads and writes to the two paired members, enforce the per-member nickname/answer/check-in boundaries, validate Firestore photo byte limits, keep activity append-only, and require both deletion approvals for permanent cleanup.
- **Rollout is intentionally not auto-enforced:** App Check enforcement is controlled in Firebase Console. Register and test every legitimate Android/Web client, monitor valid traffic first, then enable enforcement for the relevant Firebase products. See `PRODUCTION_CHECKLIST.md` before changing enforcement.



## Our Map (2.4.0)

The Android app includes an optional shared live-location map.

- Map rendering: MapLibre Native Android `13.7.1`.
- Basemap: OpenFreeMap Liberty vector style (`https://tiles.openfreemap.org/styles/liberty`), using OpenStreetMap data. No map API key or registration is required for the public OpenFreeMap instance.
- Location provider: Google Play services Fused Location Provider.
- Each partner must separately grant Android foreground location permission and turn on **Share my live location**.
- The map animates markers between location fixes, automatically frames the first available location/pair, and includes a manual **Fit both locations** button.
- Each person's card displays live/last-seen status and reported GPS accuracy. Partner status is shown as unavailable rather than assuming the partner has disabled sharing when no location document exists. Locations older than 90 seconds are removed from the map and are not used for the live distance calculation, but the latest timestamp can remain visible as a last-seen indicator.
- Normal location updates are requested around every 20 seconds with balanced accuracy. When battery saver is active or battery is at 20% or below, update requests slow to around every 60 seconds and require more movement before another fix is requested. The foreground service retries a failed location request after 30 seconds. Android and device power management may still throttle updates.
- **Foreground-only mode:** If screen-off sharing is disabled, location updates run only while Us is visible. Leaving the foreground stops the publisher and removes the member's live location document.
- **Screen-off mode (optional):** The user must turn on **Keep sharing with screen off** from the visible map screen. Us starts a user-initiated location foreground service and shows a persistent Android notification with a **Stop sharing** action. The notification action stops all sharing. Turning off only the screen-off switch returns to foreground-only sharing.
- Android 10+ declares the `location` foreground-service type. Android 14+ also requires `FOREGROUND_SERVICE_LOCATION`. Live screen-off sharing itself does not require `ACCESS_BACKGROUND_LOCATION`: the user starts the foreground service while Us is visible. Version 2.4.0 separately requests `ACCESS_BACKGROUND_LOCATION` only when a member explicitly enables saved-place geofence alerts. If the user force-stops Us, revokes location permission, stops the service, or the OS/OEM terminates it, screen-off live updates stop.
- The ongoing notification is part of this feature, not optional decoration. On Android 13+, the app asks for notification permission before enabling screen-off sharing. The **Stop sharing** notification action disables location sharing entirely. Turning off only the screen-off switch returns to foreground-only sharing.
- The shared location document is stored at `couples/{code}/locations/{uid}` and contains only the latest latitude, longitude, accuracy, and server update timestamp. A member can write/delete only their own document; only paired members can read the locations. Version 2.4.0 also adds Firestore rules for shared places and their transition events; deploy the current `firestore.rules` file before using those features.
- The map hides a location after 90 seconds without a fresh update. Distance is straight-line geographic distance, not road/routing distance. GPS accuracy is displayed separately so the distance is not presented as exact.
- Location documents are intentionally excluded from backup exports because they are temporary, sensitive presence data.

Screen-off sharing is opt-in on each phone. Either partner can stop their own sharing at any time. No route or location history is collected or stored by the app.

Deploy the updated Firestore rules before using the map:

```bash
firebase deploy --only firestore:rules
```


## Firebase setup and release steps

Use the existing Firebase project configured in `.firebaserc` and `google-services.json`.

1. In Firebase Console → Authentication → Sign-in method, enable **Anonymous** and **Email/Password**.
2. Deploy the updated Firestore rules from the project root:

   ```bash
   firebase deploy --only firestore:rules
   ```

3. Configure Firebase App Check before distributing a release build:
   - In Firebase Console → App Check, register the Android app using its package name and the SHA-256 signing certificate fingerprint for the build that you will distribute.
   - Configure the Android app to use **Play Integrity**. Complete the Play Integrity API / Play Console linking steps requested by Firebase.
   - For local debug builds, run the app once, read the App Check debug token from Logcat, and register that token under the Android app's App Check debug-token settings. Never ship a debug token or the debug provider in a release build.
   - If you will use the partner web tester after enabling Firestore App Check enforcement, register a Firebase Web App in App Check, configure reCAPTCHA Enterprise, and paste that Web App's site key into the tester connection form.
   - Watch App Check metrics and verify successful requests from every app version/device you support before enabling enforcement. Cloud Firestore enforcement affects supported clients across the project; do not enable it while a legitimate Android or web client remains unregistered or unverified.
4. Android 13+ asks for notification permission. Grant it for calendar reminders and local partner-activity notifications.
5. Build and test the debug APK, release APK/AAB, backup export/restore, two-device pairing, Activity Center, Daily Us privacy, offline sync, and deletion approval before distributing.

Firebase Storage is not used. Do not create a Storage bucket or deploy Storage rules for this version.

### Optional Cloud Functions

The included Cloud Functions implementation can send FCM notifications when the app is closed/backgrounded. Deploying Cloud Functions and server-side FCM sending requires the Firebase **Blaze** billing plan. Skip this step to remain on Spark; shared Firestore data, Activity Center, Daily Us, Firestore photo storage, local notifications while the Android app process is alive, and on-device calendar reminders do not require Cloud Functions.

```bash
cd functions
npm install
cd ..
firebase deploy --only functions
```

### Firestore rules test suite

From the project root, install the test dependencies and run the tests through the Firebase Emulator:

```bash
cd rules-tests
npm install
cd ..
firebase emulators:exec --only firestore "npm test --prefix rules-tests"
```

The tests use a dedicated emulator project ID and do not modify production Firestore. The test suite has been expanded for Activity Center and Daily Us rules. It still needs to be run in an environment where the npm dependencies and Firebase Emulator are available.

## Partner-device web tester

Open `web-tester/index.html` through a local web server (for example, VS Code Live Server) and use the Firebase web config for the same project.

- It signs in only with its own anonymous Firebase identity. It never asks for or links the Android account's email/password.
- It uses the named Firebase app `us-partner-device` and a separate local pairing-code key.
- It can only join an invite generated in the primary Android app. It has no generate-invite, create-couple, or permanent-delete/unpair action.
- The tester member can set its own nickname separately from the Android member.
- It supports shared lists, calendar events, love notes, memory thumbnails/search/favorites/paging, Daily Us, and Activity Center. It does not initialize or call Firebase Storage.
- Its anonymous identity persists in that browser profile. Use a separate browser profile/incognito session or another phone to simulate another device.
- Enable **Anonymous** sign-in in Firebase Authentication. If Cloud Firestore App Check enforcement is enabled and you use this tester, register its Web App and configure the reCAPTCHA Enterprise site key before connecting.

## Firestore data structure

```text
couples/{code}
  items/{itemId}
  events/{eventId}
  notes/{noteId}
  memories/{memoryId}
    content/image
    content/thumbnail
  activity/{activityId}
  daily/{yyyy-MM-dd}
    answers/{memberUid}
    checkins/{memberUid}
  devices/{memberUid}
```

The memory `image` and `thumbnail` documents contain JPEG bytes. Firestore rules limit originals to 700 KiB and thumbnails to 80 KiB. Daily Us answer documents are only readable by their owner until that reader has submitted their own answer; check-ins are visible to both couple members.

## Important behavior and limitations

- Firestore document changes can be queued offline. Memory-photo uploads are queued with WorkManager and start when the phone reconnects. A new Daily Us date document is initialized through a Firestore transaction, so its first initialization needs a network connection; the UI still attaches its listeners if that attempt fails.
- App-private photo copies are not visible to other apps. The cached copies are removed when local pairing is cleared. Deleting a memory removes its Firestore image subdocument before deleting the metadata document.
- Firestore enforces a 1 MiB maximum document size. The app compresses each photo to at most 700 KiB. Firestore quotas are shared by the Firebase project; monitor usage and keep photo uploads modest.
- On Firebase Spark, listener-based partner alerts work while the Android app process is running and Firestore listeners remain connected. Android may stop the process in the background, so delivery is not guaranteed when the app is force-stopped or the process is killed.
- Reliable server-sent notifications when the app is closed/backgrounded require the optional Cloud Functions deployment, which requires Blaze. To remain on Spark, do not deploy functions.
- The days-together widget refreshes when the app syncs the anniversary and periodically through Android's widget update mechanism.
- Biometric lock requires an enrolled biometric on Android 8–10. On Android 11 and newer, a device screen-lock credential can also be used.
- Existing memories from the earlier Storage-based version may still have a legacy `photoUrl`. This release keeps that URL as a display fallback, but it does not upload to, migrate from, or delete Firebase Storage objects. Older photos may not load if the old bucket is inaccessible; re-add those original photos through this version to store them in Firestore.

## Personal nicknames, alerts, and branded splash

- Each member has an independent nickname in **Settings → Your nickname**. Both members can edit only their own entry; each phone also shows the other member's nickname.
- Partner activity notification titles identify the person who acted. Notification categories include list updates, calendar updates, love notes, photo memories, and Daily Us prompts/check-ins.
- **Private previews** hide notification text until the app is opened. Quiet hours can suppress local partner alerts during a configured time range.
- The shared-space title is a separate setting under **Settings → Our shared space** and is not used as either member's personal identity.
- The heart splash animation is fixed at **40 dp** and **2,000 ms**; heart size and animation-duration controls are not exposed in Settings. The animation can be switched on or off.
- The launch overlay uses a theme-aware background, heart mark, and **“FOR MY SUGARPLUM”** lettering. Notification taps also show the branded overlay before returning to the app.
- The Settings footer remains: **“Made only for my Most Beautiful Girl (Mae)”**.

## Build in Android Studio

1. Open the `UsApp` directory in Android Studio.
2. Allow Gradle to sync and download dependencies.
3. Build → Make Project.
4. Run on Android 8.0 (API 26) or newer.

The archive does not include the Gradle wrapper launchers (`gradlew`, `gradlew.bat`, or `gradle-wrapper.jar`). Use Android Studio configured for Gradle 8.7 or restore the standard Gradle wrapper files before using `./gradlew`. A full Android Gradle build has **not** been verified in this environment; static source/configuration checks are not a substitute for a successful Android Studio build. See `PRODUCTION_CHECKLIST.md` for the test sequence.

### Optional Do Not Disturb bypass

The Android app can optionally allow important partner activity alerts and calendar reminders to interrupt Do Not Disturb. On the first launch when DND is already active and the app does not have policy access, Us shows a one-time explanation dialog with a button to open Android's **Notification Policy Access** settings. This dialog cannot appear during package installation; Android only permits the app to request this after the user opens it. The user must explicitly enable Us in Android Settings. The app then creates DND-bypass channels when it resumes with access granted. The user can also enable **Allow important alerts during Do Not Disturb** manually in Us Settings. The app does not change or turn off the phone's DND mode. Android controls the final interruption behavior, and device/OEM DND rules may still suppress sound or heads-up display. Ordinary shared-update notifications remain on their normal channel.


## Version 2.2.2 — Bottom navigation layout fix

Replaced the default six-item Material 3 navigation row with an equal-weight custom bottom bar. Each icon uses a fixed 24 dp size and a fixed 48 × 30 dp selection area; labels use a single line with ellipsis on narrow phones. The bar accounts for Android navigation-bar insets. No destinations or navigation behavior were changed.
## Version 2.2.3 navigation update

The bottom navigation now follows a floating center-heart layout: Home is a raised green heart-shaped button, with Calendar and Notes on its left and Memories and Lists on its right. Activity is available from the History action in the top app bar; Settings remains beside it. Side navigation items use icon-only states with a selected dot to preserve the compact pill layout on narrow phones.



## Version 2.2.4 changes

- Pairing invitations now use a dedicated full-screen waiting state instead of appearing inside the Home dashboard.
- When Firestore confirms that both members have joined, the invite screen fades out and the shared Home screen fades in.
- The raw invite code was removed from the Settings pairing card.
- Settings sections are ordered as Appearance, Your nickname, Our shared space, Our story, Home screen, Privacy, Notifications, Backup & restore, Account recovery, and Pairing.

## Version 2.2.5 — Pairing screen navigation and cleanup

- Added a visible back arrow and Android system Back handling on the pending invite screen. Both ask for confirmation before cancelling the generated invite.
- Cancelling a pending invite transactionally deletes only the creator's one-member invitation and returns to the pairing/authentication screen. If the partner joins at the same time, cancellation is rejected and the app proceeds to the shared space instead.
- Simplified the QR screen to the QR code, copyable pairing code, and a short waiting status. Removed repeated explanatory copy.
- Updated Firestore rules and rules tests so only the owner can cancel a still-pending invite; permanent deletion of a paired space still requires both partners' approvals.

Deploy the updated rules with `firebase deploy --only firestore:rules` before using the cancel-invite action.


## Version 2.3.0 — Our Map foundation

- Added a dedicated Map action in the top app bar without changing the heart-shaped bottom navigation destinations.
- Added a MapLibre map using the OpenFreeMap Liberty style and OpenStreetMap data.
- Added independent location-sharing opt-in for each partner, real-time Firestore location sync, accuracy display, stale-location hiding, and straight-line distance calculation.
- Added foreground-only location updates and member-owned Firestore location rules.

## Version 2.3.1 — Live map reliability

- Added smooth animated movement between new marker coordinates.
- Added last-seen timestamps and GPS accuracy quality indicators for both partners.
- Added initial automatic framing for one or both locations and a manual **Fit both locations** control.
- Added optional screen-off location sharing through a user-started Android location foreground service.
- Added a persistent sharing notification with a Stop sharing action. Either partner can independently stop their own live location.
- Added battery-aware location update intervals: approximately 20 seconds normally and 60 seconds when battery saver is active or battery level is 20% or lower.
- Added a 30-second retry when Android rejects a location update request, and status notification updates when location sync is waiting or temporarily unavailable.
- Added stale-fix filtering to the foreground publisher and lifecycle synchronization for the MapLibre view when it is created after the Activity is already resumed.
- Added Android foreground-service declarations for location. The app does not request `ACCESS_BACKGROUND_LOCATION`; screen-off sharing starts only from the visible app after the user enables it.
- Kept only the latest location per member. No route history is collected or included in backups.


## Version 2.3.2 — Temporary location sharing

- Added share durations: 15 minutes, 1 hour, or until manually stopped.
- A WorkManager expiration task stops foreground/background location sharing, clears the live location document, and refreshes the UI when a timed share expires.
- Changing the duration while sharing resets the expiration from the time of selection.



## Version 2.4.0 — Shared places and arrival/departure alerts

- Added up to 25 shared saved places per couple. Long-press the MapLibre map to save a name, coordinate, and geofence radius; both partners see the shared place marker.
- Added optional arrival/departure alerts using Android GeofencingClient. Each phone registers the shared places locally after that partner explicitly enables place alerts.
- Added a clear permission flow: precise foreground location is requested first; Android 10 can request background location at runtime; Android 11+ sends the user to the app's settings page to choose **Allow all the time**. Notifications permission is also requested on Android 13+.
- Arrival and departure transitions are stored as separate Firestore events, attributed to the phone that detected the transition. The event feed is limited to the latest 50 events and the app attempts to clean its own events older than 30 days.
- Added heart-shaped live partner markers: purple for this phone and pink for the other member. Saved places keep distinct place markers.
- Added shared places to encrypted backup export, preview, and restore. Restored places are attributed to the member performing the restore while retaining the shared location, name, and radius.
- Firestore rules now validate shared-place coordinates, radius (100–1,500 m), name length, and event actor identity; an event must reference an existing saved place.

### Saved-place and notification limitations

- Android geofence transitions are best-effort. Android can defer background geofence delivery, often by a few minutes, and location accuracy, network conditions, battery restrictions, and OEM power management affect results. A saved-place alert is not a guaranteed real-time safety or arrival service.
- Each partner must independently enable **Arrival & departure alerts** and grant precise location plus **Allow all the time**. Android 11+ requires the user to grant all-time location from app settings. Disabling the switch removes geofences on that device.
- On Firebase Spark, the phone that detects the geofence can show its own local alert. The other partner receives an in-app listener notification while their app process and Firestore listener are active. A reliable push notification to a terminated/background-restricted partner app needs a server-side FCM sender (for example, Cloud Functions), which requires Firebase Blaze. No server function or Firebase Storage dependency was added in this release.
- Saved places are shared Firestore data. Current live coordinates remain separate, are not included in backups, and are still deleted when sharing is stopped.

### Map interaction

- Tap **Fit both locations** to frame available live locations. The map initially frames the available live location(s) once both pins are available.
- Long-press an empty spot on the map to open the shared-place form. The place appears on both phones after Firestore sync.
- Enable **Arrival & departure alerts** separately on each phone after granting the requested permissions. Choose a radius appropriate for the place; a wider radius may reduce GPS drift but can trigger farther from the exact entrance.


## Version 2.4.1 — Pairing persistence fix

- Waits for Firebase Authentication to restore its persisted user before starting member-only Firestore listeners.
- Firestore permission, network, and other listener errors are treated as temporary verification failures, never as proof that a couple was deleted.
- Local pairing information and app-private memory caches are retained when the couple cannot be verified.
- Shows a dedicated retry screen instead of returning to the Pair screen on a verification error.
- Only a server-confirmed missing couple document clears the saved local pairing.
- If an earlier app build already cleared the local code, the Pair screen can recover it through the current phone's existing Firebase UID; a previously linked email/password account can also be restored.
