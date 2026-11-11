# Our Instant Gallery UI — Stage 4

## Experience

- Gallery icon is placed immediately to the left of the camera shutter.
- The gallery replaces the camera preview while open and returns to the camera with a back action or Android system Back.
- Personal tabs are perspective-aware: `I` for the signed-in Firebase UID and `She` / `He` based on the partner's saved gender. If the partner gender is unset, the label is `Partner`.
- Each tab displays only ready captures owned by that UID.
- Photos are grouped by local capture date, newest first, in a three-column square thumbnail grid.
- A selected photo opens a full-screen horizontal pager with capture timestamp and owner label.

## Loading and pagination

- Gallery metadata is loaded in pages of 30 using the Stage 3 repository API.
- Each page is owner-filtered while retaining the underlying couple-level cursor. If a page contains only partner photos, the client scans additional pages (up to ten per request) before displaying the empty state.
- Thumbnails are loaded on demand; original blobs are loaded only for the full-screen viewer.
- Switching tabs resets the page cursor and requests the selected owner's view.

## Deletion boundary

The historical gallery is read-only in this stage. The existing camera-screen **Remove shared photo** action is unchanged. Individual historical-photo deletion is deferred until a dedicated, server-enforced owner-only deletion flow can be added without weakening the current shared-photo cleanup or whole-couple deletion rules.

## Rollout

- Stage 4 does not change Firestore rules, require a database migration, or require Firebase deployment.
- Build with JDK 17. Install the same compatible-signed build on both phones without uninstalling or clearing data.
- Test gallery tabs, date grouping, pagination, full-screen swipe, and Stage 3 history retention.
