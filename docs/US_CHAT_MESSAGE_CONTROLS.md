# Us Chat — Message controls and deletion

## Behavior

- **Nickname labels:** each message shows the sender's existing shared nickname. The value comes from the current couple's Firestore `memberNames` map and the existing Settings → Your nickname control.
- **Remove for me:** creates a private hide marker for the signed-in Firebase UID. The other partner still sees the original message. The shared message body is not changed.
- **Remove for everyone:** available only to the original sender. It clears the message's `body` in the active Postgres row, sets `deleted_at` and `deleted_by_uid`, and both partners see `Message deleted 😔`. The row remains as a tombstone so chronological ordering, read cursors, and reply references do not break.
- **Offline feedback:** low-level DNS/socket/HTTP transport exceptions are translated to an app-authored message. The chat outbox continues to show its own pending/sending/sent/failed state.
- **Message actions:** long-press a server-confirmed message to open its actions. Pending or failed local outbox items cannot be removed by the server-side controls.

## Supabase migration

Apply this migration exactly once through Supabase SQL Editor:

`supabase/migrations/20261106210000_us_chat_message_controls.sql`

It creates the per-member hide table, RLS policies, secure RPC functions, the deleted-body constraint, and Realtime publication for personal hide markers. It reuses the existing `chat_messages` Realtime publication for shared message updates.

## Manual message removal in Supabase SQL Editor

First find the row. Replace the room UUID with your actual room ID:

```sql
select id, room_id, sender_uid, body, created_at, deleted_at
from public.chat_messages
where room_id = 'YOUR_ROOM_UUID'
order by created_at desc;
```

To remove a message for everyone, replace the room UUID, message UUID, and sender UID, then run:

```sql
update public.chat_messages
set body = '',
    deleted_at = clock_timestamp(),
    deleted_by_uid = 'YOUR_FIREBASE_UID'
where room_id = 'YOUR_ROOM_UUID'
  and id = 'YOUR_MESSAGE_UUID'
  and sender_uid = 'YOUR_FIREBASE_UID'
  and deleted_at is null;
```

The original body is cleared from the active message row. The message row itself remains, and the app renders `Message deleted 😔`.

To hide a message only for one member, run:

```sql
insert into public.chat_message_user_hides (room_id, message_id, hidden_by_uid)
values ('YOUR_ROOM_UUID', 'YOUR_MESSAGE_UUID', 'YOUR_FIREBASE_UID')
on conflict (room_id, message_id, hidden_by_uid) do nothing;
```

Use the Firebase UID of the member whose conversation should hide that message. This does not change the message body or the other member's view.

## Important data-retention distinction

Clearing `body` removes the original text from the active `chat_messages` row, not necessarily every historical physical copy immediately. PostgreSQL write-ahead logs and managed backups are governed by database retention and maintenance; the application cannot promise forensic erasure from those backups.

Avoid physically deleting the whole `chat_messages` row for routine message removal. Read cursors and reply references rely on stable message rows.