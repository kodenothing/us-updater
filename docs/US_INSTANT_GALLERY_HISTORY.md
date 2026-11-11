# Our Instant Gallery History — Stage 3

## Scope

Stage 3 adds the persistent photo-history data layer required by the future Instagram-style Our Instant gallery. It deliberately does not add the visible gallery icon or gallery screen; those belong to Stage 4.

## Existing data model reused

Photos remain in:

- `couples/{coupleId}/livePhotoWidgetPhotos/{photoId}`
- `couples/{coupleId}/livePhotoWidgetPhotos/{photoId}/content/image`
- `couples/{coupleId}/livePhotoWidgetPhotos/{photoId}/content/thumbnail`

Each photo document already records `createdBy`, `createdAt`, `status`, `sizeBytes`, and `thumbnailBytes`. These fields support owner-specific tabs and date-descending gallery pages without migrating existing records.

## Capture retention change

A new capture updates `livePhotoWidget/current.activePhotoId`, which remains the single shared photo used by the existing widget in this stage. It no longer deletes the previous ready photo automatically. Every successful capture from an app build containing Stage 3 remains available in history until a member explicitly removes that photo or both members approve deletion of the shared space.

The existing explicit **Remove shared photo** action still removes the currently active photo and its image documents. Replacing a photo by taking another photo does not.

Photos that were deleted by older app versions cannot be restored by this change.

## Gallery repository API

- `LivePhotoGalleryEntry`: photo ID, owner UID, capture timestamp, and stored byte sizes.
- `LivePhotoGalleryPage`: one page of entries plus a Firestore document cursor.
- `LivePhotoWidgetRepo.loadGalleryPage(code, ownerUid, pageSize, after)`: loads up to 30 entries by default, newest first, filtered to one owner's ready photos.
- `LivePhotoWidgetRepo.loadGalleryImageBytes(code, photoId, preferThumbnail)`: loads a ready photo rendition for the future grid or full-screen viewer.

The page query orders the couple's photo metadata by `createdAt` and filters the selected owner locally. This uses the automatic single-field timestamp index and avoids introducing a new composite-index deployment requirement. The future gallery can maintain a separate cursor for each tab.

## Security

The existing Firestore rules already restrict the photo metadata and content reads to members of the couple. Content documents permit direct reads of `image` and `thumbnail`, but do not permit listing the content subcollection. The added emulator test confirms that both paired members can browse historical photo metadata/content while an outsider cannot.

No Firestore rules change or database migration is required for Stage 3.

## Storage and privacy notes

Each capture retains a compressed original (up to 700 KiB) and thumbnail (up to 80 KiB) as Firestore Blob documents. Keeping history increases Firestore storage and read usage over time. The Stage 4 gallery should load metadata in pages and thumbnails on demand rather than downloading every original at once.

The history remains private to the two members of the shared couple. Gallery labels will be derived from each member's UID and saved gender setting; gender is presentation metadata and does not change photo ownership.

## Rollout

1. Build and test the Stage 3 app on both phones.
2. Install the same compatible-signed build on both phones without uninstalling or clearing app data.
3. New captures from the Stage 3 app are retained; do not test retention while either phone is still on an older uploader build.
4. Confirm that a later capture changes the current widget photo while the previous photo remains queryable for the gallery.

No Firebase deployment step is required for this stage.
