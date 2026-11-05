# Us Chat — Stage 0 setup checklist

This stage prepares the Supabase project and verifies that the two existing UsApp members can use Firebase Auth tokens with it. No Android application code or chat tables are added in this stage.

## A. Create the Supabase project

1. Open https://supabase.com/dashboard and sign in.
2. Create a project under the **Free** plan. Do not start a paid trial or add a paid add-on.
3. Use a recognizable project name such as `us-chat`.
4. Select **Southeast Asia (Singapore)** / `ap-southeast-1` for the Philippines-based devices.
5. Save the database password in your own password manager. Do not commit it or send it in chat.
6. Wait until the project reports that it is ready.

Supabase Free currently allows two active free projects. Free projects with too little database activity can pause after seven days; their data and settings remain available for restoration. See https://supabase.com/docs/guides/platform/free-project-pausing and https://supabase.com/docs/guides/platform/regions.

## B. Register the existing Firebase project with Supabase

The Firebase project ID in this repository's `.firebaserc` is:

```text
us-project-f6fc5
```

In the Supabase project:

1. Open **Authentication → Third-Party Auth**.
2. Add **Firebase Auth**.
3. Enter the Firebase project ID above and save the integration.
4. Do not create a second Supabase Auth user system for this feature. Us Chat will use the existing Firebase identities.

Official guide: https://supabase.com/docs/guides/auth/third-party/firebase-auth

## C. Prepare the current two Firebase users

Follow the local-only helper guide in [tools/supabase-claims/README.md](../tools/supabase-claims/README.md).

The helper:
- Verifies the service-account file belongs to `us-project-f6fc5`.
- Reads exactly the two current UIDs from the selected completed Firestore couple document.
- Supports both legacy 8-character and current 12-character Us pairing codes.
- Runs as a dry run by default.
- Preserves all existing custom claims when adding `role: "authenticated"`.
- Never writes credentials into the repository.

Run the dry run first and check that it identifies your two expected members. Only run `npm run apply` after reviewing the dry-run output locally.

Do not send a pairing code, service-account key, private key, or database password in chat.

## D. Values needed for the next development step

Keep these values locally:

- Supabase project URL (format: `https://<project-ref>.supabase.co`).
- Supabase publishable key (client key; not a service-role key).
- Firebase project ID: `us-project-f6fc5`.

The project URL and publishable key will be added to local Android build configuration in a later milestone. Do not place a Supabase `service_role` key in Android or commit any project secrets.

## Stage 0 completion checklist

- [ ] Supabase project is active on the Free plan.
- [ ] Project region is Singapore (`ap-southeast-1`).
- [ ] Supabase Firebase Auth third-party integration points to `us-project-f6fc5`.
- [ ] Local Admin SDK dry run identifies exactly the two expected couple members.
- [ ] Local Admin SDK claim update succeeds for both users.
- [ ] Both phones refresh their Firebase ID tokens in a later Android integration test.
- [ ] An unrelated Firebase account is denied by the RLS tests in Stage 1.

**Do not proceed to Stage 1 database migrations until the Firebase token claims and identity integration are verified.**
