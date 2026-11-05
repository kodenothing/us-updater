# Local Firebase claim setup for Us Chat

This helper prepares the existing two members of one UsApp couple for Supabase's official Firebase Auth integration.

It uses Firebase Admin SDK **locally on your own computer**. It does not deploy a Cloud Function, call a paid Firebase service, or send credentials to the app.

## Security rules

- Keep the service-account JSON outside the Git repository.
- Never upload it to GitHub, share it in chat, put it in the Android assets, or include it in the APK.
- Never use a Supabase `service_role` key in Android.
- The script only targets the two UIDs in the selected, completed `couples/{coupleCode}` Firestore document.
- The default run is a dry run. It changes nothing.
- Only add `--apply` after checking the project, couple code, and both displayed UIDs.

## 1. Prepare a Firebase Admin service-account key

The Firebase project configured in this repository is:

```text
us-project-f6fc5
```

In Firebase Console, open **Project settings → Service accounts → Firebase Admin SDK → Generate new private key**.

Save the downloaded JSON somewhere outside the repository, for example:

```text
C:\Users\James\Secrets\us-project-f6fc5-admin.json
```

Treat this file like a password. Do not commit or share it. If it is ever exposed, revoke/delete that key in Google Cloud IAM and generate a replacement.

## 2. Install the local helper

From PowerShell, open the repository folder and switch to the feature branch:

```powershell
git switch feature/us-chat-2.5.0
git pull origin feature/us-chat-2.5.0
cd tools\supabase-claims
npm install
```

Node.js 20 or newer is required.

## 3. Run a dry run first

Replace the example path and code with your own local values:

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS = "C:\Users\James\Secrets\us-project-f6fc5-admin.json"
$env:US_COUPLE_CODE = "YOUR8OR12CHARCODE"
npm run dry-run
```

The helper verifies that the service-account key belongs to the expected Firebase project and that the selected Firestore couple document contains exactly two distinct members and is no longer an invite-only space. It checks both Firebase Auth users and preserves their existing custom claims.

Review both displayed UIDs carefully. A dry run does not change anything.

## 4. Apply only after reviewing the dry run

```powershell
npm run apply
```

The script adds or updates only the `role: "authenticated"` custom claim for those two existing Firebase UIDs, preserving their other custom claims. It reads the users back and verifies the result.

## 5. After the script succeeds

Supabase must be configured with the same Firebase project ID in its **Authentication → Third-Party Auth → Firebase** integration.

Each phone must refresh its Firebase ID token to receive the new custom claim. Do not sign out of the existing anonymous account just to do this; the Android integration milestone will add a safe token-refresh path.

## Important lifecycle note

This provisions the two current Firebase UIDs only. If a user later deletes/recreates their anonymous Firebase account or otherwise gets a new UID, provision the new paired members again with this helper before they use Supabase. Linking a recovery credential to an existing Firebase account normally retains its UID.

No Supabase database schema or Android code is changed by this helper.
