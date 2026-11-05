# Us Chat — Stage 4 Execution Roadmap

This document is the detailed execution checklist for Stage 4 of Us Chat. The master product roadmap is [US_CHAT_ROADMAP.md](./US_CHAT_ROADMAP.md).

## Stage 4 goal

Make the text conversation dependable before adding reactions, replies, editing, attachments, or notifications. Stage 4 focuses on message ordering, missed-event recovery, safe retries, offline sending, and read state.

**Execution rule:** complete and verify one substage at a time. The current active scope is **Stage 4.2 only**. Do not begin more 4.3 work until 4.2 passes its build and two-phone acceptance tests.

## Current status (2026-11-05)

| Substage | Scope | Status |
|---|---|---|
| 4.1 | Supabase Realtime subscription and cleanup | Implementation is in the branch; the developer confirmed a successful debug build after the SDK API correction. Database migration and true two-phone live-event behavior still need explicit confirmation. |
| 4.2 | Synchronization recovery, deduplication, ordering, and pagination | Implementation has been pushed to the feature branch. **Not yet verified by a fresh Gradle build or two-phone tests. This is the active stage.** |
| 4.3 | Durable offline outbox, idempotent retries, and read receipts | Preliminary code and a database migration exist on the branch, but they are not accepted as complete. Work is paused until 4.2 is accepted. |

A successful build of 4.1 does not prove the newer 4.2/4.3 code builds. A successful build also does not prove Realtime, offline recovery, or read receipts work on both phones.

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

## Stage 4.2 — Reliable synchronization (active)

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

This migration adds nullable `client_message_id`, a uniqueness constraint for idempotent sends, the narrow insert grant for that field, and publication of the existing `chat_room_reads` table. Apply it through Supabase SQL Editor before testing code that depends on it. Do not rerun a migration that has already succeeded.

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

**Stage 4.2 exit gate:** all build and two-phone checks above pass. Do not start additional Stage 4.3 implementation until this gate is accepted.

---

## Stage 4.3 — Offline sending and read receipts (paused)

Some preliminary implementation is already present on the feature branch. It is intentionally not considered complete until reviewed, built, and tested after Stage 4.2.

### 4.3.1 Durable local outbox

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

- Reuse the same client message UUID across WorkManager retries and manual retries.
- Verify the server-side unique key prevents duplicate rows if the server accepted a message but the client lost the HTTP response.
- Confirm successful sends against the authoritative database row.
- Ensure a permanent failure for one queued item does not block later queued messages in that room.
- Verify the send path rejects a stale or switched Firebase account.

### 4.3.3 Read receipts

- Use the existing `chat_room_reads` table: one cursor per room member, not one update per old message.
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

**Stage 4.3 exit gate:** offline, retry, idempotency, read-cursor, and two-phone tests pass. The developer explicitly accepts the stage.

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
