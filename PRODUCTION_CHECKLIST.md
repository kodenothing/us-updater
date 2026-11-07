# Us 2.6.0 — Production checklist

Complete these steps before enforcing App Check or distributing the release build.

## 1. Firebase configuration

- [ ] Confirm the intended Firebase project is selected.
- [ ] Authentication has Anonymous and Email/Password providers enabled.
- [ ] Deploy `firestore.rules`:

  ```bash
  firebase deploy --only firestore:rules
  ```

- [ ] Run the Firestore rules tests with the emulator:

  ```bash
  cd rules-tests
  npm install
  cd ..
  firebase emulators:exec --only firestore "npm test --prefix rules-tests"
  ```

- [ ] Verify Firestore indexes are created if the console reports a missing-index link during testing.
- [ ] Confirm Firestore usage and photo-storage limits are acceptable for the project.

## 2. Android App Check

- [ ] Register the Android app in Firebase Console → App Check.
- [ ] Add the SHA-256 fingerprint for the actual release signing certificate. Use the Play App Signing certificate fingerprint when distributing through Google Play; use the upload/local signing fingerprint only for the build path it actually signs.
- [ ] Enable/configure the Play Integrity API for the same Google Cloud / Play Console project as requested by Firebase.
- [ ] Build and test a **debug** APK. Register its App Check debug token from Logcat in Firebase Console. Debug tokens are secrets: do not publish them, commit them, or put them in release builds.
- [ ] Install a **release-signed** build through the intended distribution channel and confirm Play Integrity requests are accepted.
- [ ] Review App Check metrics for the Android app. Keep enforcement off while unregistered legitimate clients still need access.

## 3. Partner web tester App Check

- [ ] If the tester will be used after Cloud Firestore App Check enforcement, register a Firebase Web App in App Check.
- [ ] Configure reCAPTCHA Enterprise for that Web App and use the exact site key issued for it.
- [ ] Open `web-tester/index.html` over localhost/HTTPS, enter the Firebase web config and the reCAPTCHA Enterprise site key, then connect as the separate anonymous partner identity.
- [ ] Confirm tester sign-in, pairing, lists, calendar, notes, memories, Daily Us, and Activity Center all work.
- [ ] If you do not intend to register the Web App, keep Cloud Firestore App Check enforcement off while expecting the tester to work.

## 4. Two-device functional test

- [ ] Create the couple on the primary Android device and pair the second device using the generated invite code.
- [ ] Confirm each member can edit only their own nickname.
- [ ] Add, complete, and remove list items; add and edit a calendar event; create a note; upload a memory photo.
- [ ] Confirm each action appears in Activity Center with the correct actor and target screen.
- [ ] Confirm the Activity Center filters show Lists, Plans, Notes, Memories, and Daily Us correctly.
- [ ] Open Daily Us on both devices. Submit an answer on only one device and verify the other member cannot read it until they submit their own answer.
- [ ] After both have answered, verify both answers become visible and can be edited by their respective owners.
- [ ] Submit both mood check-ins and verify each member can see the other's mood/note.
- [ ] Test a local notification from the other partner and each notification category preference.
- [ ] Test airplane mode/offline edits and verify they sync after reconnecting. Test initial Daily Us date creation while offline, then reopen while online to ensure the date question is initialized.

## 4A. Pairing persistence and recovery (2.4.1)

- [ ] Cold-start the app on a phone that already has a paired Firebase identity. Confirm Firebase Authentication restores its user before the couple listener starts.
- [ ] Simulate or observe a Firestore `PERMISSION_DENIED` / unavailable listener error. Confirm the app keeps the local pairing code and app-private memory caches and displays the verification recovery screen instead of returning to Pair.
- [ ] Tap **Retry verification** after restoring network / Firebase access. Confirm the same couple opens without generating a new code.
- [ ] Clear only the local `code` preference in a test build while keeping the Firebase Auth identity. On Pair, tap **Recover pairing on this phone** and confirm the existing couple code is recovered by the authenticated member query.
- [ ] With a test account that previously linked email/password, verify **Restore an existing account** signs into the original Firebase UID and restores its couple.
- [ ] Delete a test couple through the intended two-member approval flow. Confirm a server-confirmed missing couple document clears the local pairing, while permission/network errors do not.

## 5. Backup, restore, and deletion safety

- [ ] Export an encrypted backup with a new password of at least 10 characters.
- [ ] Verify that a wrong password fails without changing Firestore.
- [ ] Preview and restore the backup; confirm existing IDs are skipped and new data/photos restore correctly.
- [ ] Confirm one partner's deletion approval does **not** delete shared data.
- [ ] Confirm the second partner's approval completes deletion and removes shared Firestore data only after both approvals.

## 6. Release build

- [ ] Build the release APK/AAB in Android Studio with the intended signing config.
- [ ] Check the generated artifact's package name and version (`com.maenalyn.couples`, version `2.6.0`, code `24`).
- [ ] Confirm the release variant uses the Play Integrity App Check provider, not the debug provider.
- [ ] Install and test the actual release-signed package on a clean device.
- [ ] Verify account recovery, pairing, photo compression, memory paging, Daily Us privacy, Activity Center, notification permissions, biometric lock, and widget behavior.
- [ ] Check Firebase Crashlytics/Logs if configured and inspect Firebase App Check / Firestore usage dashboards.

## 7. Enable enforcement gradually

Only after all supported clients are registered and tested:

- [ ] In Firebase Console → App Check, enable enforcement for Cloud Firestore.
- [ ] If applicable, separately enable enforcement for the Web App after confirming its reCAPTCHA Enterprise provider works.
- [ ] Monitor rejected requests and App Check metrics after rollout.
- [ ] Do not enable Cloud Storage enforcement for this app; new memory media is stored in Firestore, not Storage.

## Billing note

The Activity Center, Daily Us, Firestore rules, Firestore photo storage, and Android App Check implementation do not add a Cloud Functions requirement. The optional server-side FCM notifications still require the Firebase Blaze plan. reCAPTCHA Enterprise use is subject to the Google Cloud pricing/quotas configured for your project; review its current pricing before using it broadly.

## Do Not Disturb bypass testing

1. Install the updated app and open Settings → Notifications.
2. Enable **Allow important alerts during Do Not Disturb**.
3. Grant **Do Not Disturb access** to Us in Android's special app access screen.
4. Return to Us and confirm the setting reports **Ready**.
5. With DND active and before granting Us policy access, launch Us on a fresh install. Confirm the one-time explanation dialog appears after the splash screen.
6. Tap **Open DND settings**, enable Us in Android's Notification Policy Access page, and return to the app. Confirm channels are refreshed.
7. Enable Do Not Disturb and tap **Send test alert**. Confirm it can interrupt according to the phone's DND policy.
8. Trigger a partner activity notification and a calendar reminder; confirm both use the bypass channels.
9. Turn the feature off and verify the app uses its normal notification channels again. Revoke the special access and verify the app falls back safely.

The setting does not turn DND off or modify the user's global DND policy. The partner activity and reminder channels use Android's `setBypassDnd(true)` API after special access is granted. Android notification channel settings and OEM-specific DND behavior remain under user/system control.


## Location sharing validation

- Test with two paired Android phones and real GPS/network location enabled.
- Verify each partner must enable sharing independently and grant foreground location access.
- Verify only the owner's own `locations/{uid}` document can be written or deleted.
- With **Keep sharing with screen off** disabled, background Us and verify the publisher stops and the owner's live marker expires.
- Enable **Keep sharing with screen off** from the visible Map screen. Confirm Android displays the persistent "Us is sharing your location" notification.
- Lock the screen for at least three minutes. Confirm Firestore location timestamps continue updating and the partner's marker remains fresh.
- Tap **Stop sharing** in the persistent notification. Confirm the service ends, both the notification and active marker disappear, and the Map screen reflects sharing disabled.
- Re-enable live sharing, turn off only **Keep sharing with screen off**, and confirm foreground-only sharing resumes while Us is open.
- Test with battery saver enabled and with battery at or below 20%; updates should use the slower low-power interval (approximately 60 seconds).
- Wait over 90 seconds without updates; verify stale pins and distance are hidden while last-seen time remains visible.
- Confirm GPS accuracy and last-seen indicators match the current location document.
- Confirm the initial available location/pair is framed automatically and the Fit button reframes both pins.
- Confirm the distance is described as straight-line distance, not driving distance.
- Verify no location documents are included in backups and that both-member deletion removes them.
- Do not request `ACCESS_BACKGROUND_LOCATION` in this release. The location foreground service must be started while Us is visible and remains user-controlled through its ongoing notification.
- Remember that Android force-stop, revoked permission, disabled location services, OS restrictions, and some manufacturer battery controls can stop updates.



## 2.3.2 temporary sharing validation

- [ ] Select 15 minutes, enable sharing, and verify the expiry countdown begins.
- [ ] Verify sharing and the foreground service stop after the expiry task runs, and the live location document is deleted.
- [ ] Repeat with the 1-hour option. Confirm **Until stopped** has no expiration.
- [ ] Change duration during an active share and confirm the expiration is reset from that selection time.
- [ ] Confirm disabling sharing cancels scheduled expiration work.

## 2.4.0 saved places and arrival/departure validation

- [ ] Confirm long-pressing the map opens the Save shared place dialog and saved places sync to both phones.
- [ ] Confirm a place has a valid name and radius between 100 and 1,500 meters; verify the UI refuses a 26th saved place.
- [ ] Verify shared places are included in encrypted backup export, preview, and restore; current live locations are not included.
- [ ] On each phone, explicitly enable Arrival & departure alerts and grant precise location, background location (Allow all the time), and notifications.
- [ ] On Android 11+, verify the app explains why it needs all-time location, opens app settings, and enables alerts only after the permission is granted.
- [ ] Walk or drive into and out of a test geofence. Verify that the detecting phone shows a local notification and that a partner app with an active Firestore listener receives the transition.
- [ ] Verify the place event records the correct actor, place, and transition. Confirm deleting a place unregisters its geofence after the shared-place listener updates.
- [ ] Disable alerts and verify geofences are removed. Re-enable and confirm registration returns without duplicating alerts.
- [ ] Verify the map uses heart-shaped live markers (own phone vs partner) and distinct markers for saved places.
- [ ] Verify place alerts can be delayed by Android geofencing. Do not represent geofence alerts as guaranteed instant or as an emergency/safety service.
- [ ] Test notification delivery with the partner app backgrounded and force-stopped. On Firebase Spark, do not expect reliable partner push while its listener is disconnected; use Blaze/server-side FCM only if reliable terminated-app push is required.

## Android background-location policy reminder

- The app requests `ACCESS_BACKGROUND_LOCATION` only for the optional saved-place geofence feature, not for continuous live coordinate streaming. Explain this purpose before opening Android app settings.
- Android 11+ users must choose **Allow all the time** in the app's location permission settings. The app cannot grant this special permission itself.
- Review Google Play's current background-location policy and complete any required declaration/review before publishing a build that requests this permission.
