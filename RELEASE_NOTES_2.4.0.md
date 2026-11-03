# Us 2.4.0 — Shared places and arrival/departure alerts

Includes the 2.3.2 temporary-sharing features and:
- Up to 25 shared saved places, added by long-pressing the map.
- Android geofence entry/exit alerts, independently enabled by each partner.
- Precise location and Allow all the time permission flow for saved-place alerts.
- Heart-shaped live partner markers and distinct saved-place markers.
- Shared places included in encrypted backup/restore.
- Firestore rules and rules tests for places and transition events.

Android geofence alerts are best-effort and can be delayed. On Firebase Spark, remote partner notifications are not reliable while the receiving app's Firestore listener is disconnected; server-side FCM requires Blaze.

Toolchain remains compileSdk/targetSdk 34, minSdk 26, Kotlin 2.0.20, AGP 8.5.2, Firebase BoM 33.4.0.
