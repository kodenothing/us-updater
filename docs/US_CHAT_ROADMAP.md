# Us Chat — v2.5.0 and Beyond Roadmap

This is the **master roadmap** for the Us Chat feature. The detailed reliability implementation and acceptance criteria are in [US_CHAT_STAGE_4_ROADMAP.md](./US_CHAT_STAGE_4_ROADMAP.md). Stage-specific setup and implementation notes remain in the Stage 0–3 documents alongside this file.

## Product objective

Deliver a private, dependable one-to-one conversation for the two members of an existing UsApp couple, then expand it into a richer shared communication space without disrupting the existing app.

### Architecture decisions to preserve

- **Firebase remains the identity and couple source of truth.** Continue using the existing Firebase Auth accounts and existing couple pairing.
- **Supabase PostgreSQL is the chat data store.** Supabase Realtime is the live change signal; PostgREST reads remain authoritative.
- Keep all database access protected by RLS. Never trust a client-supplied sender UID, room ID, or read cursor without server-side policy checks.
- Keep service-role keys, Firebase Admin credentials, and FCM server credentials out of the APK and repository.
- Keep Firebase features—Memories, Our Instant, Maps, location sharing, and existing app navigation—working as they do now.
- Target Supabase Free and Firebase Spark-compatible approaches unless a feature is shown to be impossible within the available limits. Verify current quotas and pricing before choosing any new backend service.
- Implement in small stages. Each stage must pass a build and its acceptance tests before the next stage is treated as complete.

## Current development status — 2026-11-05

| Stage | Status |
|---|---|
| 0 — Project and identity readiness | Completed according to the developer's setup confirmation. |
| 1 — Database schema and RLS | Base schema and room roster setup completed. Security regression tests remain part of release hardening. |
| 2 — Android Supabase foundation | Integrated; earlier Android build and protected-read setup completed. |
| 3 — First text-chat UI and send/receive path | Implemented; a debug build was confirmed successful during development. Keep the full two-phone acceptance checklist as the release record. |
| 4.1 — Realtime foundation | SDK integration correction is in; developer confirmed the subsequent build succeeded. Database migration and two-device live delivery still require explicit acceptance. |
| 4.2 — Reliable synchronization | Code has been pushed to `feature/us-chat-2.5.0`. A fresh build and two-phone test are still pending. **Current active stage.** |
| 4.3 — Offline sending and read receipts | Initial code and migration groundwork exist in the branch, but this is paused and not accepted. Start/finish it only after 4.2 is approved. |
| 5 — Conversation features | Planned. |
| 6 — Push notifications | Planned; backend, secrets, quota, and free-plan feasibility must be reviewed first. |
| 7 — Media and live presence | Planned; do only after text, sync, and notifications are reliable. |
| 8 — Security, performance, and release hardening | Planned; required before production release. |

**Status language:** “implemented” means code exists; “build passed” means the developer actually ran the build successfully; “accepted” means the agreed device/security tests also passed. Do not treat these as interchangeable.

---

## Stage 0 — Account and project readiness

**Goal:** establish the trusted Firebase-to-Supabase identity path without adding app code.

- [x] Create the Supabase project on the Free plan and select the intended Singapore region (developer-confirmed).
- [x] Configure Supabase's Firebase third-party authentication integration (developer-confirmed).
- [x] Provision the required Firebase JWT `role: authenticated` custom claim for the two existing accounts through the local trusted Admin SDK workflow (developer-confirmed).
- [x] Confirm the Supabase project configuration and local-only credential handling.
- [x] Keep service-account credentials and database secrets out of GitHub and the Android client.

Reference: [US_CHAT_STAGE_0_SETUP.md](./US_CHAT_STAGE_0_SETUP.md).

## Stage 1 — Database schema and Row Level Security

**Goal:** create the private chat data model and enforce the two-member boundary in PostgreSQL.

- [x] Create `chat_rooms`, `chat_room_members`, `chat_messages`, and `chat_room_reads`.
- [x] Use Firebase UID values as text, not Supabase UUID identity assumptions.
- [x] Enforce the expected Firebase issuer, audience, authenticated role, and current Firebase UID.
- [x] Restrict room/member/message/read visibility to the two registered room members.
- [x] Allow a member to insert messages only as their own Firebase UID.
- [x] Keep room and roster creation privileged; Android cannot add arbitrary members.
- [ ] Retain unrelated-user, wrong-sender, and cross-room RLS regression tests as mandatory checks before release.

Reference: [US_CHAT_STAGE_1_DATABASE.md](./US_CHAT_STAGE_1_DATABASE.md).

## Stage 2 — Android Supabase foundation

**Goal:** use the existing Firebase identity in the Android app without changing sign-in or pairing.

- [x] Pin compatible Supabase Kotlin and Ktor versions for the existing Kotlin 2.1 / Java 17 toolchain.
- [x] Add the Supabase client/provider and Firebase ID token supplier.
- [x] Keep Supabase URL and publishable key in ignored local build configuration.
- [x] Add protected-read diagnostics for development.
- [x] Preserve Firebase as the only app sign-in identity provider.

Reference: [US_CHAT_STAGE_2_ANDROID.md](./US_CHAT_STAGE_2_ANDROID.md).

## Stage 3 — First usable text conversation

**Goal:** deliver a simple private chat before adding complex message types.

- [x] Add the Chat destination and Material 3 conversation UI.
- [x] Load and render text messages chronologically.
- [x] Add text send with a 4,000-character limit and Firebase sender validation.
- [x] Add loading, empty, retry, and send/error states.
- [x] Add Realtime INSERT subscription and screen-scoped cleanup.
- [ ] Keep two-phone send/receive, listener cleanup, and unrelated-user denial in the final acceptance record.

Reference: [US_CHAT_STAGE_3_ANDROID.md](./US_CHAT_STAGE_3_ANDROID.md).

## Stage 4 — Reliability and conversation basics

**Goal:** make the text conversation dependable under real mobile network conditions.

Stage 4 is divided into three controlled checkpoints:

1. **4.1 Realtime foundation:** subscribe to current-room message changes, clean up listeners, and retain a catch-up fallback.
2. **4.2 Synchronization reliability — active:** authoritative refresh after reconnect, duplicate prevention, stable chronological ordering, and keyset pagination for older history.
3. **4.3 Offline sending and read receipts — paused:** app-private durable outbox, WorkManager retry, stable client message IDs, idempotent server writes, and per-member read cursors.

Detailed tasks, migration order, build commands, and acceptance tests: [US_CHAT_STAGE_4_ROADMAP.md](./US_CHAT_STAGE_4_ROADMAP.md).

**Do not move to Stage 5 until all Stage 4 gates pass on both phones.**

## Stage 5 — Conversation features

Implement and test each item independently. Keep text-only behavior stable while adding these capabilities.

### 5.1 Emoji reactions
- Add a normalized reaction table keyed by message and Firebase UID.
- Limit a member to one reaction per emoji per message (or define a single-reaction-per-user rule before migration).
- Enforce same-room membership and own-user identity through RLS.
- Show reaction counts and the partner's reaction state in the UI.
- Handle removal and Realtime updates without duplicating reaction chips.

### 5.2 Replies
- Use the existing `reply_to_message_id` reference.
- Enforce that the parent message belongs to the same room.
- Display a compact quoted-message preview and navigate to the original message.
- Define behavior when the original message is deleted.

### 5.3 Edit and delete
- Permit a user to edit or delete only their own messages.
- Add strict database grants/RLS or a constrained database function; do not rely on Android-only checks.
- Prefer a clear edited marker and a tombstone for deleted messages so conversation history remains structurally consistent.
- Define edit/delete time limits and whether partner notifications are generated.
- Reconcile concurrent edits and deletes safely.

### 5.4 Save to Memories
- Allow a user to save a text message or shared link into the existing Memories feature.
- Reuse the existing Memories data model and UI where appropriate.
- Avoid copying or duplicating media objects.
- Preserve the original chat message reference so users can return to the conversation.
- Define what happens to a saved item if its source chat message is later deleted.

### 5.5 Chat appearance
- Optional per-user wallpaper, bubble accent, and text-size preferences.
- Keep customization local unless shared appearance is explicitly chosen.
- Respect dark mode, contrast, dynamic text sizing, and accessibility.

## Stage 6 — Push notifications

**Goal:** notify the partner while UsApp is backgrounded or closed without exposing credentials.

- [ ] Inspect the existing FCM token registration and `UsMessagingService` before adding parallel notification infrastructure.
- [ ] Choose a trusted sender (for example, a Supabase Edge Function, webhook-backed service, or another supported trusted environment) only after reviewing current pricing, free quotas, secret management, and operational limits.
- [ ] Trigger notifications from confirmed message inserts, not from an untrusted Android client request.
- [ ] Store server-only notification credentials in the selected provider's secret store.
- [ ] Notify only the other room member; do not notify the sender's own device.
- [ ] Prevent duplicate notifications during retry/replay.
- [ ] Support private previews, notification channels, deep links to the correct conversation, and existing quiet-hours behavior.
- [ ] Handle token refresh and invalid/expired FCM tokens.

**Exit gate:** partner receives one correct notification while the app is backgrounded or closed; tapping opens the right conversation; no server credential exists in the APK.

## Stage 7 — Media and live presence

Only start after text messaging, Stage 4 synchronization, read state, and Stage 6 notifications are stable.

### 7.1 Image attachments
- Private Supabase Storage bucket with explicit RLS policies tied to the same two room members.
- Upload/download authorization that never exposes service-role credentials.
- Image compression, thumbnail generation, progress, cancellation, retry, and size limits.
- Safe local caching and cleanup.
- Delete both message metadata and storage objects through a trusted, failure-safe flow.
- Test expired access, interrupted upload, account switching, and cross-room denial.

### 7.2 Voice messages
- Decide maximum duration, file format, bitrate, and storage budget before implementation.
- Add recording permissions, recording preview/cancel, playback, upload progress, and retry.
- Respect audio focus, headset routing, and accessibility.
- Verify actual Free-plan storage and egress limits before release.

### 7.3 Typing and online state
- Use short-lived Supabase Realtime Presence or Broadcast state.
- Do not write high-frequency typing/online changes to PostgreSQL.
- Add timeout and lifecycle cleanup so stale online indicators disappear.
- Treat presence as approximate, not as a security or delivery guarantee.

### 7.4 Later integrations
- Share a chat message into Memories or calendar.
- Optional shared location card only if it can reuse existing privacy and location-sharing controls.
- Do not duplicate the existing live location feature inside Chat.

## Stage 8 — Security, quality, and production release

**Goal:** validate the whole feature as one production system.

### Security and privacy
- [ ] Re-run RLS tests as both partners and an unrelated Firebase account.
- [ ] Test mismatched sender UID, room ID, read cursor, message ID, reaction owner, and cross-room references.
- [ ] Confirm no privileged credentials are in the repository, logs, crash reports, APK, or WorkManager data.
- [ ] Review Realtime event visibility and Storage policies.
- [ ] Verify local outbox data is scoped to the signed-in Firebase UID and safely handled on logout/account changes.
- [ ] Document data deletion and retention behavior, including attachment cleanup.

### Reliability and UX
- [ ] Test airplane mode, weak network, rapid reconnect, process death, device restart, and app update.
- [ ] Test simultaneous sends, duplicate events, same-timestamp messages, pagination boundaries, and long conversations.
- [ ] Test light/dark themes, keyboard behavior, small screens, font scaling, TalkBack labels, and tap targets.
- [ ] Check battery/network usage and avoid unbounded polling or high-frequency database writes.
- [ ] Verify no regression in pairing, Memories, Our Instant, Maps, location sharing, and other app features.

### Release process
- [ ] Complete all Stage 4–7 gates for features included in the release.
- [ ] Update release notes and version metadata only when the release is approved.
- [ ] Build debug and signed release variants using the existing matching signing setup.
- [ ] Test upgrade/install behavior without uninstalling the existing app or clearing user data.
- [ ] Review Supabase usage limits, backups/recovery expectations, and production monitoring.
- [ ] Merge the feature branch only after the developer accepts the final test results.

## Deferred ideas (not required for v2.5.0)

These ideas may be considered after the core roadmap, based on actual usage and free-tier capacity:

- Message search and conversation filters.
- Pinned messages and saved chat bookmarks.
- Link previews with safe URL handling.
- Shared photos/voice notes beyond the initial attachment implementation.
- Chat export and user-controlled conversation cleanup.
- Optional message reminders or scheduled messages.

Do not let deferred enhancements delay private, reliable text messaging.

## Release scope

The first Us Chat release should prioritize:

1. Secure Firebase-to-Supabase identity and RLS.
2. Private one-to-one room tied to the existing couple.
3. Reliable text send/receive, pagination, reconnect recovery, and safe retries.
4. Basic read receipts only after their acceptance tests pass.
5. A polished Chat screen that preserves all current UsApp features.

Image attachments, voice messages, typing indicators, online presence, and other advanced enhancements remain out of the first release unless separately completed, tested, and explicitly approved.

## Branch and collaboration rules

- Feature branch: `feature/us-chat-2.5.0`.
- Keep PR #7 in draft/unmerged state until the developer accepts the implementation and tests.
- Current production baseline: v2.4.9, versionCode 22. Do not change version metadata during feature development unless requested.
- For each stage: inspect current code and schema, implement one bounded change, review the full diff, let the developer run Gradle, test on both phones, fix failures, and record acceptance.
- Never claim a local build, migration, or device test succeeded unless it was actually run and confirmed.
- Preserve existing app architecture and features; do not modify unrelated Firebase rules or data.
