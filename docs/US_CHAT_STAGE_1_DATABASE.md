# Us Chat — Stage 1: Database and RLS

This stage creates the private, text-only chat database. It does not change Android code, Firebase data, or existing UsApp features.

## Important Firebase UID detail

UsApp uses Firebase Auth UIDs such as opaque alphanumeric strings. Supabase's \`auth.uid()\` helper casts the JWT \`sub\` claim to a UUID, so it is not suitable for these Firebase identities. The migration reads \`auth.jwt()->>'sub'\` as **text**, and checks the Firebase issuer, audience, and \`role: authenticated\` claim before granting access.

Official reference: https://supabase.com/docs/guides/auth/third-party/custom-oidc#using-rls-policies

## What the migration creates

| Table | Purpose |
|---|---|
| \`chat_rooms\` | One room linked to an existing Firebase couple code |
| \`chat_room_members\` | The two Firebase UIDs authorized for that room |
| \`chat_messages\` | Text messages, server timestamps, reply reference, and reserved edit/delete metadata |
| \`chat_room_reads\` | One read cursor per room member |

Security characteristics:

- RLS is enabled on all four tables.
- Every table has a restrictive check for the expected Firebase issuer, audience, and authenticated role.
- Only the two registered members can read a room, its roster, messages, or read cursors.
- Clients cannot create rooms or edit the member roster.
- A client can insert a message only as its own Firebase UID.
- The initial message type is text only, with a 4,000-character maximum.
- Clients cannot update or delete messages in this stage.
- Clients can update only their own read cursor; the database sets the read timestamp.
- No public Storage bucket or service-role key is used.

## 1. Apply the migration

In the Supabase Dashboard, open **SQL Editor → New query**.

Copy the complete migration from:

\`supabase/migrations/20261105090000_us_chat_schema.sql\`

Paste it into the SQL Editor and run it once.

Do not run it a second time after it succeeds. The migration is intentionally a versioned schema change, not a repeatable setup script.

## 2. Register the existing couple room

Do this in the Supabase SQL Editor, which runs with database-owner privileges. Do not run this from Android.

Use the existing UsApp Firestore \`couples/{coupleCode}\` document. Its \`members\` array must contain exactly the two Firebase UIDs that were verified by the local Admin SDK dry run.

Replace the three placeholders below with the current couple code and the two UIDs. Keep this query private; do not commit the real values to GitHub or send them in chat.

\`\`\`sql
with room as (
    insert into public.chat_rooms (firebase_couple_code)
    values ('REPLACE_WITH_EXISTING_COUPLE_CODE')
    on conflict (firebase_couple_code)
    do update set firebase_couple_code = excluded.firebase_couple_code
    returning id
),
members(firebase_uid) as (
    values
        ('REPLACE_WITH_FIRST_FIREBASE_UID'),
        ('REPLACE_WITH_SECOND_FIREBASE_UID')
)
insert into public.chat_room_members (room_id, firebase_uid)
select room.id, members.firebase_uid
from room
cross join members
on conflict (room_id, firebase_uid) do nothing;
\`\`\`

If this reports a uniqueness or member-limit error, stop. Do not work around it by deleting rows. Inspect the room and membership records first.

## 3. Verify the privileged roster

Run this in the SQL Editor, replacing the couple code with the same existing value:

\`\`\`sql
select
    r.id as room_id,
    r.firebase_couple_code,
    count(m.firebase_uid) as member_count,
    array_agg(m.firebase_uid order by m.firebase_uid) as firebase_members
from public.chat_rooms as r
left join public.chat_room_members as m on m.room_id = r.id
where r.firebase_couple_code = 'REPLACE_WITH_EXISTING_COUPLE_CODE'
group by r.id, r.firebase_couple_code;
\`\`\`

Expected: exactly one row with \`member_count = 2\`, and the two UIDs must match the existing Firestore \`members\` array.

## 4. Stop for the RLS verification

Do not add Android code yet. After the migration and roster are in place, report:
- whether the migration ran successfully;
- whether the roster query returned exactly two expected members;
- any SQL error text, with identifiers and credentials removed.

The next step is to test access as each partner and as an unrelated Firebase account before enabling Realtime or integrating the Android client.

## Rollback note

Do not drop these tables as a troubleshooting step. If the migration fails partway through, send the SQL error first. The migration is wrapped in a transaction, so a normal SQL error should roll back the change.
