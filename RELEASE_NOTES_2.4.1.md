# Us 2.4.1 — Pairing persistence and recovery

This update preserves the full 2.4.0 feature set, including MapLibre, live location sharing, saved places, geofence alerts, memories, Daily Us, and Activity Center.

## Pairing fix

- Wait for Firebase Authentication's initial state restoration before observing the saved couple.
- A Firestore `PERMISSION_DENIED`, network outage, timeout, or other listener error no longer becomes an unpair event.
- These errors preserve the saved pairing code and local app-private memory caches.
- A retry screen explains the problem and lets the user retry verification.
- The Pair screen can recover a missing local code using the Firebase identity already saved on that phone; linked email/password recovery is also available.
- A pair is cleared only after Firestore confirms from the server that the couple document does not exist.

## Version

- `versionName`: `2.4.1`
- `versionCode`: `14`
