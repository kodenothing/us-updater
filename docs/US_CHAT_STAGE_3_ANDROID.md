# Us Chat — Stage 3: first text-chat screen

This is the first Android chat UI and text-message write path on `feature/us-chat-2.5.0`.

## Included

- A dedicated **Us Chat** destination opened from the chat icon in the existing top app bar.
- Material 3 chat bubbles using the current app theme, with sent/received alignment and local message times.
- Room lookup using the existing Firebase couple code; Firebase remains the identity provider.
- Loading, empty conversation, retry/error, sending, and message-length states.
- Text-only send operation (maximum 4,000 characters), with Firebase UID validation before insert.
- Fetches the newest 100 messages and displays them chronologically.
- Supabase Realtime INSERT subscription for the current room, plus periodic polling as a fallback and a manual refresh button.
- Supabase RLS remains the access control boundary; the Android client does not create rooms or roster entries.

## Files

- `app/src/main/java/com/maenalyn/couples/chat/UsChatRepository.kt`
- `app/src/main/java/com/maenalyn/couples/chat/UsChatScreen.kt`
- `app/src/main/java/com/maenalyn/couples/Screens.kt`

## Current limitation

Realtime is now the primary update path. The app subscribes to INSERT events on `public.chat_messages`, filtered to the current `room_id`, and reloads the authoritative latest-100 list when an event arrives. The subscription and collector are scoped to the Chat screen and removed when it leaves. Polling remains as a resilience fallback: every 30 seconds while Realtime is subscribed, or every 5 seconds if subscription setup fails. Realtime must be enabled for `chat_messages` in the `supabase_realtime` publication; apply `supabase/migrations/20261105120000_us_chat_realtime.sql` in the Supabase SQL Editor before testing. This uses database Realtime events—not a separate chat database or client-side broadcast.

This is an initial latest-100 view, not full older-message pagination. Pagination, unread/read state, offline outbox, and notifications are not included yet.

## Build and test

From the repository root:

```powershell
git fetch origin
git pull --ff-only origin feature/us-chat-2.5.0
.\gradlew.bat clean assembleDebug
```

Do not install over an existing app until the signing certificate matches. The installed app's certificate was confirmed to match the local release keystore, while the default Debug certificate does not match. Before installing, make sure the local Debug build is signed with that existing key; do not uninstall the existing app to work around a signature mismatch.

## Two-device test checklist

1. Build and install the Debug APK on both existing paired phones.
2. Open Us Chat on both phones.
3. Send a short message from phone A; confirm it appears on A immediately and on B after the next refresh.
4. Reply from B; confirm it appears on both phones in near real time (without waiting for the fallback poll).
5. Test emoji, multi-line text, long text near the limit, empty text, and repeated taps on Send.
6. Switch to other app tabs and back; confirm there are no crashes or stale screens.
7. Test dark and light themes.
8. Confirm existing pairing, Memories, Our Instant, map, location sharing, and settings still work.
9. Confirm the Logcat `UsChatBackend` protected room diagnostic passes on both partner accounts.
10. Do not merge the branch until the developer confirms the tests pass.

Do not use a third-party account to send messages as either partner. The SQL RLS policy must reject unrelated users and mismatched sender UIDs.


## Stage 4.1 — Realtime enablement

Apply the migration in Supabase SQL Editor:

- `supabase/migrations/20261105120000_us_chat_realtime.sql`

It adds only `public.chat_messages` to the existing `supabase_realtime` publication, idempotently. No room/member/read tables are published. Existing RLS policies continue to govern database access and Realtime visibility.

Runtime notes:
- The app listens only to INSERT events for the current room.
- The event is a signal to reload from PostgREST; the client does not trust event payloads as its message list.
- Realtime subscription failures do not block chat. The screen falls back to 5-second polling.
- While subscribed, 30-second polling is retained to recover from missed events/network reconnect edge cases.
- The channel is removed when the Chat screen leaves.
