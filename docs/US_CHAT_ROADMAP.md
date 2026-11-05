# Us Chat — v2.5.0 Development Roadmap

## Project goals

Build a private, real-time, one-to-one messaging feature for the two members of an existing UsApp couple.

- Keep Firebase as the source of truth for existing pairing, account identity, Memories, Our Instant, Maps, calendar, notes, and lists.
- Use Supabase PostgreSQL and Realtime for chat data and live message delivery.
- Stay on Supabase Free and Firebase Spark. Do not introduce paid services, Cloud Functions, Cloud Run, or a billing-plan upgrade.
- Preserve the current app architecture, navigation style, theme support, and current production features.
- Implement and validate one milestone at a time. Do not begin the next milestone until the current one builds and passes the agreed tests.

## Authentication and security decision

Preferred integration: Supabase's official Firebase Auth third-party integration. The Android client supplies the current Firebase ID token to the Supabase Kotlin client. For RLS, identify the Firebase user through the JWT `sub` claim as text (`auth.jwt()->>'sub'`). Do not use `auth.uid()` for these Firebase UIDs: Supabase casts `auth.uid()`'s `sub` to UUID, while Firebase UIDs are opaque strings.

Important prerequisite: Firebase ID tokens do not contain Supabase's required `role: authenticated` claim by default. Supabase documents that this claim must be added using a privileged Firebase Admin SDK environment. Do not attempt to set custom claims inside the Android app, and never put a service-account key in the repository or APK.

For the existing two Firebase accounts, the planned Spark-compatible setup is a one-time local Admin SDK script run by the project owner on their own computer. No deployed Firebase Cloud Function is planned. If either Firebase UID changes in the future, its claim must be provisioned again through the same trusted local process. Do not silently replace this identity model with client-supplied UIDs or unverified Supabase anonymous identities.

Before coding against the live project, verify the exact Firebase project ID, the two current Firebase UIDs, Supabase's third-party Firebase integration, token claims, and RLS behavior. If secure claim provisioning cannot be completed, stop and reassess the architecture before adding chat UI or data writes.

## Milestones

### Stage 0 — Account and project readiness (no app code)

1. Create the Supabase project on the Free plan.
2. Enable the official Firebase Auth third-party integration for the correct Firebase project.
3. Confirm the two existing Firebase UIDs and arrange local Admin SDK access without sharing or committing credentials.
4. Add the required `role: authenticated` claim to those two existing accounts using a local-only Admin SDK script.
5. Force-refresh each Firebase ID token and verify the token claims.
6. Test Supabase access from a temporary client for both users.
7. Verify an unrelated Firebase account is rejected by RLS.

**Exit gate:** both intended users authenticate through Firebase tokens; an unrelated account cannot access private chat data; no paid Firebase plan or deployed server is required.

### Stage 1 — Database schema and Row Level Security (Supabase only)

Implementation files on `feature/us-chat-2.5.0`:

- Migration: `supabase/migrations/20261105090000_us_chat_schema.sql`
- Setup and verification steps: `docs/US_CHAT_STAGE_1_DATABASE.md`


Create and review SQL migrations for:

- `chat_rooms`: one room per existing couple, with the Firebase couple identifier and creation metadata.
- `chat_room_members`: room membership keyed to Firebase UIDs.
- `chat_messages`: message ID, room ID, sender UID, message type, text, creation timestamp, optional reply reference, and edit/deletion metadata reserved for later use.
- `chat_message_reads` or an equivalent per-user read cursor for read state.

Security requirements:

- Enable RLS on every exposed table.
- Only the two Firebase UIDs stored as members of a room can select its room, membership, messages, and read state.
- A sender can only create messages as their own authenticated Firebase UID.
- Validate message length, room membership, message type, and allowed update fields in database policies or constrained RPCs.
- Do not expose service-role keys in Android.
- Keep the first release text-only; do not enable public media buckets.

**Exit gate:** test both partner accounts and a third unrelated account directly against Supabase. Confirm isolation with actual RLS tests before integrating Android.

### Stage 2 — Android Supabase foundation (no chat navigation yet)

1. Check the Supabase Kotlin SDK, Kotlin, serialization plugin, and Ktor versions for compatibility with the existing Kotlin 2.1.21 / AGP 8.5.2 / Java 17 project.
2. Add only the required Supabase modules: Auth-token integration, PostgREST, and Realtime.
3. Add Supabase URL and publishable key through local build configuration; never hardcode a service-role key.
4. Create a small Supabase client/provider that obtains a fresh Firebase ID token when needed.
5. Add connection and authentication state handling without changing existing login or pairing flows.
6. Add a small diagnostic path to verify the current UID and a protected database read during development.

**Exit gate:** debug and release builds compile; both paired devices authenticate; existing Firebase features and sign-in recovery remain unchanged.

### Stage 3 — First usable Us Chat (text-only)

1. Add a dedicated Chat destination in the existing navigation.
2. Build the conversation screen using the current UsApp Material 3 theme and dark/light palette.
3. Load the latest messages with descending database pagination, then display them chronologically.
4. Send plain text messages with a strict length limit and server-generated timestamp.
5. Subscribe to new messages through Supabase Realtime and clean up listeners when leaving the screen.
6. Include loading, empty, offline, retry, and send-failure states.
7. Prevent duplicate sends and keep scrolling stable as messages arrive.

**Exit gate:** send and receive between the two phones in real time; verify sender identity, ordering, pagination, listener cleanup, and RLS rejection from a third account.

### Stage 4 — Message reliability and conversation basics

1. Add local pending-send state and safe retry behavior.
2. Add message delivery state based on confirmed database writes.
3. Add read receipts using a per-user read cursor or read table; avoid updating every old message individually.
4. Add message pagination and efficient unread count calculation.
5. Handle app background/foreground transitions and reconnects without duplicate listeners or duplicate messages.

**Exit gate:** test poor connectivity, app process restarts, repeated taps, and simultaneous messages from both phones.

### Stage 5 — Conversation features

Implement separately, in this order:

1. Emoji reactions.
2. Reply to a specific message.
3. Edit and delete the sender's own messages, with clear edited/deleted presentation.
4. Save a message or shared text into Memories without duplicating existing media.
5. Chat appearance options such as wallpaper and accent color.

Each feature gets its own build and device test before the next one starts.

### Stage 6 — Notifications

1. Reuse the existing FCM token registration and notification channels.
2. Design a trusted server-side notification sender; never send FCM credentials from the Android client.
3. Prefer a Supabase-side secure function or webhook only after confirming the Free-plan quota and secret-handling requirements.
4. Support private notification previews and the existing quiet-hours settings.
5. Avoid notifying the sender's own device and avoid duplicate notifications.

**Exit gate:** partner receives a notification while the app is backgrounded or closed, tapping it opens the correct conversation, and no credential is present in the APK.

### Stage 7 — Media and later enhancements

Only after text messaging, RLS, synchronization, and notifications are stable:

- Private image attachments using Supabase Storage with RLS-protected buckets and short-lived access.
- Image compression, thumbnails, upload progress, retry, and deletion.
- Voice messages only after storage limits and UX are assessed.
- Typing/online state using short-lived Realtime presence, not permanent database writes.
- Chat-to-Memories and chat-to-calendar integrations.

## v2.5.0 release scope

The first v2.5.0 release should be limited to:

- Secure Firebase-to-Supabase authentication.
- Private one-to-one room tied to the existing couple.
- Text messages.
- Real-time delivery while connected.
- Pagination and basic sent/read state only if it passes the reliability milestone.
- A polished Chat screen integrated into existing navigation.

Do not include image attachments, voice messages, typing indicators, online status, or push notifications in the initial scope unless each is independently completed and tested.

## Versioning and test workflow

- Current production baseline: v2.4.9, versionCode 22.
- Target release: v2.5.0, versionCode 23.
- Work branch: `feature/us-chat-2.5.0`.
- Keep the branch unmerged while testing.
- For each milestone: inspect current code, implement only that milestone, review the diff, let the developer build with Gradle, test on both phones, fix reported errors, and proceed only after approval.
- Never claim a Gradle build or device test was performed unless it actually was.
- Preserve existing app features and Firestore data. Do not deploy Firestore rules or schema changes unless the corresponding milestone requires them and the diff has been reviewed.

## Official references

- Supabase Firebase Auth integration: https://supabase.com/docs/guides/auth/third-party/firebase-auth
- Supabase Kotlin installation: https://supabase.com/docs/reference/kotlin/installing
- Supabase Kotlin Android quickstart: https://supabase.com/docs/guides/getting-started/quickstarts/kotlin
- Firebase custom claims: https://firebase.google.com/docs/auth/admin/custom-claims
- Firebase pricing plans: https://firebase.google.com/docs/projects/billing/firebase-pricing-plans
