# UsApp v2.7.0

**Android package:** `com.maenalyn.couples`  
**Version code:** `25`  
**Minimum Android version:** Android 8.0 (API 26)

## What's new

### Background widget synchronization

- Pair restoration now ensures an immediate sync and a persistent periodic WorkManager sync are scheduled for the shared Our Instant widget.
- Periodic work uses Android WorkManager's 15-minute minimum interval and a network-connected constraint.
- Android may defer work because of Doze, battery optimization, or device/OEM policies. A force-stopped app cannot run scheduled work until it is opened again. This improves durable polling; it is not an instant push-delivery guarantee.

### Personal profile gender

- Added **Settings → My Profile → Gender**.
- Each member can edit only their own gender setting: Male, Female, or Not set.
- Both paired members can read the saved profile value. Existing accounts without a saved gender default to Not set; no migration is required.

### Our Instant photo history and gallery

- Capturing a newer photo no longer removes the previous ready capture from history.
- Added a gallery shortcut beside the camera shutter.
- Added **I** and partner-perspective tabs. The partner tab uses **She** or **He** when the partner's saved gender is known, and **Partner** otherwise.
- Photos appear in a newest-first, three-column grid grouped by local capture date.
- Tapping a photo opens a full-screen viewer with horizontal swipe navigation and capture date/time.
- Historical gallery photos are read-only in this release. The existing Remove shared photo action remains separate.
- Captures already deleted by an earlier app version cannot be restored.

### Independent Instant widgets

- Added **My Instant**, showing the signed-in member's latest ready capture.
- Added **Partner Instant**, showing the other member's latest ready capture.
- Each member has an owner-only Firestore pointer and an independent widget cache.
- A new capture updates the capturing member's My Instant and appears as the partner's Partner Instant, without replacing the other member's own My Instant.
- The existing **Our Instant** shared widget remains supported and continues to show the latest shared capture.
- All three widget types use the existing background refresh schedule and retain independent frame-style customization.

## Firestore and storage

- Added owner-only authorization for personal widget pointers.
- A personal pointer can reference only a ready photo created by its owner.
- Photo deletion is protected while the capture is referenced by the legacy shared pointer or its owner's personal pointer.
- Photos continue to use Firestore Blob documents. Firebase Storage, a composite index, and a schema migration are not required for these changes.
- The Firestore rules emulator suite passed **22 tests, 0 failures** during release preparation.

## Validation recorded

- Debug build: `.\gradlew.bat clean assembleDebug` — successful.
- Release build: `.\gradlew.bat assembleRelease` — successful after the v2.7.0 version metadata and final resource correction were synced to `main`.
- GitHub release: `v2.7.0` published with the APK, SHA-256 sidecar, and output metadata assets.
- Firestore rules emulator: **22 passed, 0 failed, 0 skipped**.
- Developer-reported two-phone Stage 5 feature test: working.

## Upgrade safety

- Package name remains `com.maenalyn.couples`.
- Install as an update over the existing app using the same release signing key.
- Do not uninstall the existing app or clear its data during an upgrade.
- Verify the release APK version code/name and signing-certificate SHA-256 before publishing.
