# Our Instant — Stage 5: Independent Member Widgets

## Widget choices

Stage 5 adds two independent home-screen widget types:

- **My Instant** displays the signed-in member's most recent ready capture.
- **Partner Instant** displays the other member's most recent ready capture. Its empty-state label uses the partner's saved gender when available ("Her Instant" or "His Instant"); otherwise it says "Partner Instant".

The existing **Our Instant** shared widget remains registered for installations that already use it. It continues to show the most recent capture from either member. New installations can add My Instant and Partner Instant separately and place them side by side.

Each widget keeps its own frame-style selection.

## Data flow

- A capture is stored once in `livePhotoWidgetPhotos/{photoId}`.
- Publishing the capture atomically updates the legacy couple-wide pointer and `livePhotoWidgetMembers/{ownerUid}`.
- Each member pointer is writable only by the matching signed-in owner and can reference only that owner's ready photo.
- The sync worker resolves the two couple members, downloads each member's current photo into a separate app-private cache, and updates the two new widget providers.
- The legacy shared widget keeps its original cache and state pointer. Personal caches never overwrite one another.

The periodic worker is shared by all three widget types. It retains the existing WorkManager network constraint and 15-minute minimum periodic interval; actual execution can be deferred by Android.

## Deletion and privacy boundary

- A photo referenced by the legacy shared pointer or its owner's personal widget pointer cannot be deleted.
- Clearing the legacy shared widget pointer no longer deletes the photo. The photo remains in the owner's gallery and personal widget.
- Personal pointer documents can be cleared by their owner; deleting the pointer documents themselves remains reserved for permanent couple deletion approval.
- Gallery history remains read-only in the UI. This stage does not add an individual history-delete action.

## Firebase rules

The new `livePhotoWidgetMembers/{ownerUid}` rules allow a couple member to read either pointer, but only the matching UID can create or update their pointer. A pointer can refer only to a ready photo created by that UID.

The photo and content delete rules now also protect a photo while its personal pointer is active. The rules tests cover owner-only pointer writes, cross-member denial, outsider denial, and deletion protection.

After the rules tests pass, deploy with:

```powershell
firebase deploy --only firestore:rules
```

No composite index, Firestore schema migration, or Firebase Storage migration is required.

## Validation

- [ ] Run `.\gradlew.bat clean assembleDebug` with JDK 17.
- [ ] Run `.\gradlew.bat assembleRelease` with the existing release signing key.
- [ ] Run `firebase emulators:exec --only firestore "npm test --prefix rules-tests"` with the Firebase CLI's supported JDK.
- [ ] Deploy the updated Firestore rules after emulator tests pass.
- [ ] Install the same compatible-signed APK on both phones without uninstalling or clearing app data.
- [ ] Add **My Instant** and **Partner Instant** from the Android widget picker on both phones.
- [ ] On James's phone, verify My Instant shows James's capture and Partner Instant shows Mae's capture.
- [ ] On Mae's phone, verify My Instant shows Mae's capture and Partner Instant shows James's capture.
- [ ] Capture from James and verify only James's My Instant and Mae's Partner Instant change.
- [ ] Capture from Mae and verify only Mae's My Instant and James's Partner Instant change.
- [ ] Verify the legacy Our Instant shared widget continues to show the latest capture from either member.
- [ ] Verify the widgets refresh after app closure through the existing background schedule.
- [ ] Verify clearing the legacy shared pointer leaves personal widget photos and gallery history intact.
- [ ] Verify each widget retains its own selected frame design.

## Rollout notes

- Keep PR #15 as the Stage 4 dependency until it is merged.
- This PR is stacked on `feature/usapp-2.7.0-stage4-instant-gallery-ui`.
- Keep version metadata unchanged until final v2.7.0 release preparation.
