# Our Instant Gallery UI — Stage 4

## Experience

- Gallery icon is placed immediately to the left of the camera shutter.
- The gallery replaces the camera preview while open and returns to the camera with a back action.
- Personal tabs are perspective-aware: `I` for the signed-in Firebase UID and `She` / `He` based on the partner's saved gender. If the partner gender is unset, the label is `Partner`.
- Each tab displays only ready captures owned by that UID.
- Photos are grouped by local capture date, newest first, in a three-column square thumbnail grid.
- A selected photo opens a full-screen horizontal pager with capture timestamp and owner label.

## Loading and pagination

- Gallery metadata is loaded in pages of 30 using the Stage 3 repository API.
- Each page is owner-filtered while retaining the underlying couple-level cursor. If a page contains only partner photos, the client scans additional pages (up to ten per request) before displaying the empty state.
- Thumbnails are loaded on demand; original blobs are loaded only for the full-screen viewer.
- Switching tabs resets the page cursor and requests the selected owner's view.

## Deletion

- The viewer exposes delete only in the signed-in member's own tab and hides it for the current widget photo.
- Firestore rules enforce that a member can delete only their own non-current history photo.
- Either partner can still remove the active shared widget photo through the existing camera-screen action. Both-partner-approved permanent shared-space cleanup remains allowed.

## Rollout

- This stage changes Firestore rules, so rerun the complete emulator suite and deploy `firestore.rules` before testing delete in the app.
- Build with JDK 17. Install a release-signed build with the same signing certificate over both existing installations; do not uninstall or clear data.
- Test gallery tabs, dates, full-screen swipe, owner-only delete, active-photo preservation, and Stage 3 historical retention.
