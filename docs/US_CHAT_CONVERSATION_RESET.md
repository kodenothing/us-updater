# Us Chat — Clear conversation and start new

## User-facing behavior

The action is available in **Settings → Us Chat → Clear conversation & start new**.

- Either verified room member can initiate the reset.
- A confirmation explains that the reset affects both partners and cannot be undone in the app.
- The server blanks message bodies in the current generation and marks them as shared-deletion tombstones.
- The room's active conversation ID is rotated in one database transaction. Both phones then display a new, empty conversation.
- A partner with Us Chat already open switches when the room update arrives through Supabase Realtime. A partner who is offline or has the app closed switches on the next successful room refresh.
- Offline messages keep the generation they were queued against. Workers validate that generation before delivery, and the database insert trigger serializes sends against resets. Stale queued messages are discarded rather than delivered into the new conversation.

## Supabase migration

Apply this migration in the Supabase SQL Editor after the existing chat schema, realtime, read-receipt, reliability, and message-controls migrations:

`supabase/migrations/20261107090000_us_chat_conversation_reset.sql`

**Do not install the updated app before applying this migration.** The new app expects the active conversation columns and reset RPC to exist.

The migration:

- adds an active generation ID to each room and a generation ID to each message;
- backfills current messages into their room's first generation;
- restricts message reads and inserts to the active generation;
- adds a Firebase-UID-validated `chat_reset_conversation` RPC;
- serializes message inserts and reset operations with room-row locks;
- preserves old message rows as hidden tombstones to protect foreign-key and read-cursor references; and
- publishes room metadata updates for a partner's open chat.

The migration depends on the message-controls migration, which adds `deleted_by_uid` and the deleted-body tombstone constraint.

## Data retention

The previous generation's message bodies are cleared in the active `chat_messages` rows. Tombstone rows and related metadata remain to preserve foreign-key and ordering references. Managed database backups and PostgreSQL historical storage remain subject to Supabase retention; the app cannot promise forensic erasure from backups.

## Manual test checklist

- [ ] Apply all chat migrations in timestamp order.
- [ ] Install the updated build on both partner phones.
- [ ] Send messages and confirm both phones see them.
- [ ] Queue a message while offline, reconnect, and confirm normal delivery.
- [ ] Open Us Chat on both phones.
- [ ] From one phone, open **Settings → Us Chat** and select **Clear conversation & start new**.
- [ ] Confirm the warning and verify the initiating phone reports success.
- [ ] Verify the partner's already-open chat switches to an empty conversation without restarting the app.
- [ ] Send a new message from each phone and confirm only new-generation messages appear.
- [ ] Queue a message offline on one phone, reset from the other phone, reconnect the first phone, and verify the old queued message is discarded.
- [ ] Race a send against reset and verify no old-generation message appears after the reset.
- [ ] Verify nicknames, read receipts, Remove for me, and Remove for everyone still work in the new conversation.
- [ ] Verify cancelling the confirmation leaves the current conversation unchanged.
- [ ] Verify existing Us Chat remains usable after reopening the app on both phones.
