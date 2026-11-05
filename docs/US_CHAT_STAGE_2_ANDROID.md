# Us Chat — Stage 2: Android Supabase foundation

This stage adds the Supabase client foundation only. It does not add Chat navigation, message UI, message reads, message writes, or change Firebase sign-in/pairing.

## Compatibility choice

The app currently uses Kotlin 2.1.21, AGP 8.5.2, Java 17, and minSdk 26. The dependencies are deliberately pinned to:

- supabase-kt BOM 3.1.4
- Ktor 3.1.1 with the OkHttp engine
- Kotlin serialization plugin 2.1.21

supabase-kt 3.1.4 is on the Kotlin 2.1-compatible line. Version 3.2.0 moved the SDK to Kotlin 2.2.0, so do not upgrade to the newest SDK or Ktor in this milestone without first planning a coordinated toolchain upgrade.

The OkHttp engine is intentional because Supabase Realtime uses WebSockets; the Ktor Android engine does not support WebSockets.

## 1. Add local Supabase configuration

At the repository root, copy:

`supabase.properties.example`

to:

`supabase.properties`

Edit the new local file:

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_REPLACE_ME
```

Use the project URL and the **publishable** key from Supabase Dashboard → Project Settings → API Keys. Do not use a secret key or service-role key.

The local `supabase.properties` file is ignored by Git. Never commit it. The publishable key is intended for client apps, but it still must not be confused with a secret/service-role key.

## 2. Firebase token behavior

The app continues to use its existing Firebase Auth account. There is no Supabase sign-in, account migration, logout, or new account creation.

The access-token provider:

1. Reads the currently signed-in Firebase user.
2. Force-refreshes that user's Firebase ID token the first time this process needs a Supabase token, so the locally provisioned `role=authenticated` claim is present.
3. Verifies that claim before returning the token.
4. Uses Firebase's normal cached/automatic refresh behavior for later requests.
5. Detects an account switch while a token is being fetched and does not return the old account's token.

The Supabase Auth plugin is intentionally not installed. Third-party Firebase auth uses the Supabase client's `accessToken` provider instead.

## 3. Debug-only protected-read diagnostic

When a paired shared space loads in a **debug build** and local Supabase configuration is present, the app performs one read-only query against `chat_rooms` for the current couple code.

Open Android Studio → Logcat and filter by:

`UsChatBackend`

Expected on each partner's phone:

`PASS: current Firebase account can read its private Us Chat room through RLS.`

The query does not print the UID, couple code, JWT, or publishable key. No messages are created or changed.

Possible outcomes:

- `SKIP: local Supabase configuration is not present.` — check that the local file is named exactly `supabase.properties` and is in the project root, then rebuild.
- Missing role claim — verify that the device is signed into one of the two Firebase accounts that received the Admin SDK claim. The provider performs a forced ID-token refresh automatically.
- No matching room — check the Stage 1 room roster and RLS policies in Supabase.
- HTTP/auth error — verify the project URL, publishable key, Firebase third-party integration, and current network connection.

Diagnostics run only in debug builds. Release builds do not perform this automatic check.

## 4. Build checks

From the project root:

```powershell
.\gradlew.bat clean assembleDebug
.\gradlew.bat assembleRelease
```

Both tasks must pass. Do not update the app's versionCode or versionName in this foundation stage.

## 5. Two-device verification

Install the debug APK on both phones using the existing paired Firebase accounts.

For each phone:

1. Open UsApp and let the shared space finish loading.
2. Check Logcat for the `UsChatBackend` tag.
3. Confirm the protected room read passes.
4. Confirm Firebase sign-in, pairing, Memories, Our Instant, Maps, and the other existing features still work.

A third unrelated Firebase account must not be used for this diagnostic; Stage 1's separate RLS test already covers that account. Do not add any client-side room membership or bypass policy checks.

## Stage 2 exit gate

- Debug build succeeds.
- Release build succeeds.
- Both paired phones report the protected room read as passed.
- Existing Firebase sign-in/recovery and app features remain unchanged.
- No service-role key, Firebase service-account JSON, or other privileged credential is in the APK or repository.

Stop here. Do not start Chat UI or message sending until both devices pass these checks.
