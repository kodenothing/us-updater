# Us Chat — Stage 4 Execution Roadmap

This document is the detailed execution checklist for Stage 4 of Us Chat. The master product roadmap is [US_CHAT_ROADMAP.md](./US_CHAT_ROADMAP.md).

## Stage 4 goal

Make the text conversation dependable before adding reactions, replies, editing, attachments, or notifications. Stage 4 focuses on message ordering, missed-event recovery, safe retries, offline sending, and read state.

**Execution rule:** complete and verify one substage at a time. Stage 4.2's build and basic two-phone send/receive test passed, and the developer explicitly authorized moving forward. The extended 4.2 stress checklist is deferred to release hardening. The developer reports Stage 4 finished. Stage 4.3.3 read-receipt implementation is now added; build, migration, and focused device/security verification remain.

## Current status and exact stopping point (2026-11-05)

| Substage | Scope | Implementation status | Verification status |
|---|---|---|---|
| 4.1 | Supabase Realtime subscription and cleanup | Implemented in the branch. | Developer confirmed the Realtime publication flag is enabled and both phones exchange messages live. |
| 4.2 | Synchronization recovery, deduplication, ordering, and pagination | Implemented in the feature branch. | Developer confirmed `clean assembleDebug` succeeded and basic two-phone live send/receive works. Extended pagination, reconnect, lifecycle, and RLS stress checks are deferred. |
| 4.3 | Durable offline outbox, idempotent retries, and read receipts | 4.3.1 SQLite/WorkManager outbox and UI are pushed. Existing idempotent server-write path is reused. Read-receipt code has now been added. | Developer reports Stage 4.3 complete; read-receipt verification remains pending. |

### Current checkpoint — resume here

- **Current task:** Stage 4.3.3 — compile and validate read receipts.
- **Repository:** `kodenothing/us-updater`
- **Branch:** `feature/us-chat-2.5.0`
- **Pull request:** [PR #7](https://github.com/kodenothing/us-updater/pull/7), keep draft/unmerged.
- **Latest code checkpoint before the current documentation update:** `2dabae19a0d2a2df94d6c292fef6ef2492d0284f`. Pull the latest branch tip; do not check out an older hash.
- **Verified by developer:** the current 4.2 branch built successfully with `clean assembleDebug`; both phones exchanged live messages; `has_client_message_id` and `chat_messages_realtime_enabled` checks returned `true`.
- **Deferred 4.2 checks:** pagination beyond 50 rows, reconnect/missed-event recovery, repeated screen lifecycle, and unrelated-user RLS regression. These are not marked passed and must return to the release-hardening checklist.
- **Pushed for 4.3.1:** app-private SQLite outbox, network-constrained WorkManager delivery, a per-room serial work chain, five-attempt exponential retry, same client UUID on retries, sender UID guard, pending/sending/sent/failed display, manual retry, and server-confirmed local-row cleanup. The database also caches the room ID against the couple code and Firebase UID, so a previously opened conversation can reopen and queue messages offline after process death. The first room resolution still needs an online lookup.
- **Read-receipt implementation pushed:** repository cursor read/write, current-user cursor advance to latest server message, partner Seen state, Realtime INSERT/UPDATE listeners, and migration `20261106010000_us_chat_read_receipts.sql`.\n- **Verification pending:** fresh build, migration application, and read-receipt two-phone/security checks.

**Next action:** pull the latest feature branch and run `clean assembleDebug`. Review compiler output before testing offline behavior.

No new Supabase migration is required for 4.3.1; it reuses the existing `client_message_id` column and uniqueness support.

A successful build is not a substitute for offline, process-death, account-switch, and two-phone acceptance tests.

---

## Stage 4.1 — Realtime foundation

### Implementation scope

- Subscribe to INSERT events on `public.chat_messages`, filtered to the current room.
- Register the flow before joining the channel.
- Keep the channel and collectors scoped to the visible Chat screen.
- Remove the channel when the screen leaves.
- Keep a conservative PostgREST refresh as a safety net.
- Keep chat usable when WebSocket setup fails.

### Existing migration

Apply once through Supabase SQL Editor:

`supabase/migrations/20261105120000_us_chat_realtime.sql`

It adds `public.chat_messages` to the existing `supabase_realtime` publication. It does not replace or bypass RLS.

### Acceptance checks

- [ ] Migration is applied successfully.
- [ ] Phone A sends; Phone B receives without manually refreshing.
- [ ] Phone B replies; Phone A receives without manually refreshing.
- [ ] Leaving and reopening Chat does not create duplicate listeners.
- [ ] A third, unrelated Firebase account cannot read the room or its Realtime message events.

---

## Stage 4.2 — Reliable synchronization (accepted to proceed; stress checks deferred)

### 4.2.1 Authoritative message synchronization

- Treat Postgres Changes as a signal that the server may have changed; use PostgREST to load authoritative rows.
- Merge incoming server rows with the currently displayed timeline instead of replacing the entire conversation.
- Deduplicate by server message ID.
- Use the client-generated message ID to reconcile a locally queued send with its server-confirmed row.
- Preserve already loaded older pages when refreshing the latest page.
- Do not display a second copy when a Realtime event and a polling refresh observe the same insert.

### 4.2.2 Stable ordering

- Sort by `created_at`, then by message UUID as a deterministic tie-breaker.
- Use the same ordering in every page query and in the merged UI timeline.
- Confirm messages created in the same timestamp interval remain in a consistent order on both devices.

### 4.2.3 Keyset pagination

- Load the newest 50 server messages initially.
- Fetch older messages using a cursor composed of `created_at` and `id`; avoid offset pagination, which can shift as new messages arrive.
- Show an explicit **Load older messages** control while older rows exist.
- Merge older rows without removing newer messages or local pending messages.
- Preserve the reader's visible position after older rows are inserted.
- Stop requesting older data when the server returns no additional page.

### 4.2.4 Reconnect and missed-event recovery

- Observe Realtime channel status.
- On reconnection, immediately refresh the latest authoritative page.
- Keep a 30-second safety refresh while subscribed.
- Use a faster 5-second refresh while subscription is unavailable.
- Ensure only one room-scoped channel and one set of collectors exist for the visible screen.
- Cancel collectors and remove the channel when leaving the screen or changing rooms.
- Treat cancellation as cancellation; do not convert it into a normal network failure.

### 4.2.5 Idempotency foundation

The Stage 4.3 offline sender depends on stable client-generated IDs:

- Each outgoing message receives one UUID before it is queued.
- A retry must reuse that UUID; it must never create a new ID for the same logical message.
- The database unique key is scoped by room and sender.
- A client must not be able to impersonate another sender; existing Firebase UID checks and RLS remain mandatory.

Migration:

`supabase/migrations/20261105180000_us_chat_reliability.sql`

This migration adds nullable `client_message_id`, the unique index used by idempotent sends, and the narrow insert grant for that field. It does **not** add `chat_room_reads` to the Realtime publication; read-receipt Realtime setup is a separate 4.3.3 task. The developer's `has_client_message_id` diagnostic returned `true`; verify the unique index and grant before release. Do not rerun a migration that has already succeeded.

### Stage 4.2 build gate

- [ ] Pull the latest feature branch.
- [ ] `.\gradlew.bat clean assembleDebug` succeeds.
- [ ] Resolve every compiler error before device testing.
- [ ] Confirm the app remains installable using the existing matching signing certificate; do not uninstall the production app to work around a signing mismatch.

### Stage 4.2 two-phone acceptance

- [ ] Send at least 20 alternating messages between both phones.
- [ ] Confirm each message appears once on both phones.
- [ ] Send messages rapidly from both phones and confirm matching order.
- [ ] Create enough messages to exceed 50; load older messages and confirm no gaps or duplicates.
- [ ] While older messages are loaded, receive new messages and confirm the existing timeline is retained.
- [ ] Turn off one phone's network, send messages from the other phone, then reconnect; confirm missed messages appear.
- [ ] Background and foreground the app; confirm the conversation catches up.
- [ ] Switch away from Chat and back repeatedly; check for duplicate listeners, duplicate rows, or crashes.
- [ ] Confirm existing pairing, Memories, Our Instant, Maps, location sharing, and settings still work.
- [ ] Confirm RLS still blocks unrelated users.

**Stage 4.2 progression decision (2026-11-05):** the developer confirmed the new branch build succeeded and the basic two-phone send/receive path works, then explicitly authorized moving to Stage 4.3. The longer pagination, reconnect, lifecycle, and RLS checks above remain deferred; do not claim they passed. Revisit them during release hardening.

---

## Stage 4.3 — Offline sending and read receipts (active)

The developer has authorized Stage 4.3 after the 4.2 build and basic live two-phone check. Implement and accept 4.3.1 first, then validate idempotent delivery, and only then add read receipts. The developer reports the offline outbox and idempotency substages complete; read receipts are newly implemented and still need verification.

### 4.3.1 Durable local outbox

**Implementation pushed; fresh build and device tests pending.** The branch now contains `UsChatOutbox.kt` and the Chat screen integration. It persists the body and client-generated UUID in app-private SQLite before scheduling network delivery.

- Persist the message body and its client-generated UUID in app-private SQLite before scheduling network delivery.
- Store only the opaque message UUID in WorkManager input data; do not place message text, Firebase tokens, or Supabase credentials in Work Data.
- Use a connected-network constraint and bounded exponential retry.
- Serialize pending sends per room so offline messages are delivered in their original local order.
- Restore eligible pending work after process death.
- Keep pending, sent-awaiting-server-sync, and failed states distinct.
- Keep a failed message visible with an explicit retry action.
- Do not silently delete a local outbox row until an authoritative server refresh confirms the matching client message ID.
- Scope local queued messages to the Firebase UID that created them; never display one account's queued text to a different account after account switching.

### 4.3.2 Idempotent delivery

**Implementation groundwork exists; acceptance tests pending.** `UsChatRepository.sendMessage` uses the room/sender/client-ID conflict key and reads the matching server row back. The new worker reuses the stored UUID for every retry.

- Reuse the same client message UUID across WorkManager retries and manual retries.
- Verify the server-side unique key prevents duplicate rows if the server accepted a message but the client lost the HTTP response.
- Confirm successful sends against the authoritative database row.
- Ensure a permanent failure for one queued item does not block later queued messages in that room.
- Verify the send path rejects a stale or switched Firebase account.

### 4.3.3 Read receipts

**Implemented in branch; build/migration/device verification pending.** Android read-cursor behavior and Realtime publication have been added.

- Use the existing `chat_room_reads` table: one cursor per room member, not one update per old message.
- Add a new idempotent SQL migration to publish `public.chat_room_reads` to `supabase_realtime`, after checking the publication's current tables. Do not edit or rerun the already-applied message Realtime migration.
- A user may write only their own cursor; existing RLS and narrow column grants remain the security boundary.
- Advance the cursor only to a server-confirmed message.
- Load the partner's cursor and show **Seen** only for the latest applicable message sent by the current user.
- Refresh partner read state from Realtime and a fallback refresh.
- Do not expose message bodies or other private data through the read cursor.

### 4.3.4 Stage 4.3 acceptance

- [ ] Send a message offline; it remains visible as pending.
- [ ] Restore connectivity; the message is delivered without tapping Send again.
- [ ] Kill and reopen the app while a message is queued; it is not lost.
- [ ] Simulate a lost server response and confirm retry creates only one server row.
- [ ] Queue multiple messages offline; confirm they arrive in local send order.
- [ ] Force a permanent failure; confirm only that item becomes failed and can be retried manually.
- [ ] Switch Firebase accounts in a controlled test; confirm queued messages are never sent as the other user.
- [ ] Open the conversation on the partner phone; confirm the sender sees Seen after the partner's read cursor advances.
- [ ] Confirm both members can read the room cursor but cannot update the other member's cursor.
- [ ] Recheck RLS with an unrelated Firebase account.

**Stage 4.3 exit gate:** offline, retry, idempotency, account-switch, read-cursor, Realtime, RLS, and two-phone tests pass. The developer explicitly accepts the stage. Before that, keep Stage 5 paused.

---

## Stage 4 completion gate

Stage 4 is complete only when 4.1, 4.2, and 4.3 each pass their own acceptance checks. Keep the feature branch unmerged until the developer approves it. Record any test failure and fix it before moving to Stage 5.

## Developer workflow

From the repository root in PowerShell:

```powershell
git fetch origin
git pull --ff-only origin feature/us-chat-2.5.0
.\gradlew.bat clean assembleDebug
```

Do not install an APK over the existing app unless its signing certificate matches the installed app. Do not uninstall the existing app to work around a mismatch.

The assistant must not claim a Gradle build, Supabase migration, or phone test passed unless the developer explicitly reports that result or a real build/test environment confirms it.
