# UsApp v2.6.0

**Android package:** `com.maenalyn.couples`  
**Version code:** `24`  
**Release status:** Prepared release candidate; not yet published.

## What's new

### Us Chat

- Remove a message for yourself or remove your own message for both partners.
- Deleted messages remain as tombstones so chat ordering and related references remain consistent.
- Start a fresh shared conversation from **Settings → Us Chat → Clear conversation & start new**.
- Both partners switch to the new conversation after a reset.
- Improved app-authored offline and connection feedback.

### Navigation and Our Instant

- Added a compact hamburger drawer with **Our Instant**, **Our Map**, and **Settings**.
- The drawer opens from the hamburger icon rather than an edge swipe; swiping remains available to close it after opening.
- Us Chat remains the right-side header action.
- Added four independently configurable Our Instant photo-widget styles: **Soft corners**, **Fine outline**, **Polaroid**, and **Minimal**.

### Camera and map polish

- Corrected front-camera photo mirroring while preserving back-camera orientation.
- Improved the Our Map content inset so its controls remain clear of the bottom navigation.
- Polished the bottom-navigation surface and layout.

## Database rollout

The Supabase migration for conversation reset was applied and confirmed by the developer:

`supabase/migrations/20261107090000_us_chat_conversation_reset.sql`

It depends on the earlier chat migrations, including:

`supabase/migrations/20261106210000_us_chat_message_controls.sql`

Do not run the conversation-reset migration a second time as part of ordinary app installation. Confirm the target Supabase project already has the migration applied before installing this release on another environment.

## Validation recorded

- [x] Debug `clean assembleDebug` completed successfully.
- [x] Conversation-reset Supabase migration applied successfully.
- [x] Updated app installed on both partner phones.
- [x] Normal chat messages delivered between the two phones.
- [x] Reset from one phone switched both phones to a new empty conversation.
- [x] New messages worked after the reset.

## Remaining regression checks

- [ ] Verify read receipts and individual message removal after reset.
- [ ] Queue a message offline, reset from the partner phone, reconnect, and verify the old queued message is discarded.
- [ ] Exercise simultaneous send/reset timing and verify no old-generation message appears after reset.
- [ ] Verify the reset action stays disabled until the shared space has two members.
- [ ] Test all four Our Instant widget styles, including multiple differently styled widget instances and resize/reconfigure behavior.
- [ ] Test the drawer on a physical device: tap-to-open, swipe-to-close, scrim/back dismissal, and no swipe-to-open.
- [ ] Build, install, and test the release-signed APK on both phones.
- [ ] Verify the release build uses Play Integrity App Check rather than the debug provider.

## Build

Use JDK 17 and the repository's existing release signing configuration:

```powershell
.\gradlew.bat clean assembleRelease
```

The release APK is expected at:

`app/build/outputs/apk/release/app-release.apk`

Do not publish the GitHub release until the APK is signed with the same certificate as the installed app, the artifact's package/version/signature are checked, and the remaining release tests are complete.

## Upgrade safety

Install as an update over the existing app. Do not uninstall the current installation or clear app data. Preserve the existing application ID and signing key so the update can retain local pairing and chat state.
