# Us Chat — Stage 3: first text-chat screen

This is the first Android chat UI and text-message write path on `feature/us-chat-2.5.0`.

## Included

- A dedicated **Us Chat** destination opened from the chat icon in the existing top app bar.
- Material 3 chat bubbles using the current app theme, with sent/received alignment and local message times.
- Room lookup using the existing Firebase couple code; Firebase remains the identity provider.
- Loading, empty conversation, retry/error, sending, and message-length states.
- Text-only send operation (maximum 4,000 characters), with Firebase UID validation before insert.
- Fetches the newest 100 messages and displays them chronologically.
- Automatic refresh every 5 seconds while the chat screen is visible, plus a manual refresh button.
- Supabase RLS remains the access control boundary; the Android client does not create rooms or roster entries.

## Files

- `app/src/main/java/com/maenalyn/couples/chat/UsChatRepository.kt`
- `app/src/main/java/com/maenalyn/couples/chat/UsChatScreen.kt`
- `app/src/main/java/com/maenalyn/couples/Screens.kt`

## Current limitation

This first implementation uses periodic PostgREST refresh rather than a Realtime subscription. The Realtime module is already installed, but table publication/subscription setup and lifecycle handling are deliberately left for a follow-up after this basic send/receive path builds and passes two-device tests. Messages sent by the partner should appear within about five seconds while the Chat screen is open.

This is an initial latest-100 view, not full older-message pagination. Pagination, unread/read state, offline outbox, and notifications are not included yet.

## Build and test

From the repository root:

```powershell
git fetch origin
git pull --ff-only origin feature/us-chat-2.5.0
.\gradlew.bat clean assembleDebug
```

Do not install over an existing app until the signing certificate matches. For the current installation, the local release keystore fingerprint was verified to match the installed app, so use the local signing configuration already present in `keystore.properties` to sign the Debug APK if needed.

## Two-device test checklist

1. Build and install the Debug APK on both existing paired phones.
2. Open Us Chat on both phones.
3. Send a short message from phone A; confirm it appears on A immediately and on B after the next refresh.
4. Reply from B; confirm it appears on both phones.
5. Test emoji, multi-line text, long text near the limit, empty text, and repeated taps on Send.
6. Switch to other app tabs and back; confirm there are no crashes or stale screens.
7. Test dark and light themes.
8. Confirm existing pairing, Memories, Our Instant, map, location sharing, and settings still work.
9. Confirm the Logcat `UsChatBackend` protected room diagnostic passes on both partner accounts.
10. Do not merge the branch until the developer confirms the tests pass.

Do not use a third-party account to send messages as either partner. The SQL RLS policy must reject unrelated users and mismatched sender UIDs.
