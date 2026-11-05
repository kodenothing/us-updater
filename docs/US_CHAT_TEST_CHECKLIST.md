# Us Chat — Test Checklist

Use this checklist to record real results. Check an item only after performing the test on the stated device/account. A successful Gradle build is not a substitute for device acceptance.

## Current checkpoint

- [x] Developer confirmed the new partner Firebase UID was provisioned with role="authenticated" and the Supabase room roster was updated.
- [ ] Verify the partner UID migration on both phones using Section A.
- [ ] Pull the latest feature/us-chat-2.5.0 branch and run the fresh build in Section B.
- [ ] Complete Stage 4.3.1 offline outbox tests in Section C.
- [ ] Complete deferred Stage 4.2 reliability/security tests in Section D.
- [ ] Complete read receipts only after their implementation is pushed (Section E is a future checklist).

Repository: kodenothing/us-updater
Branch: feature/us-chat-2.5.0
PR: [#7 — keep draft and unmerged until explicitly approved](https://github.com/kodenothing/us-updater/pull/7)

---

## A. Partner Firebase UID migration

Run these using the newly paired accounts.

| # | Test | Expected result | Status |
|---|---|---|---|
| A1 | Open Us Chat on both phones. | Both current members can open the same conversation. | [ ] |
| A2 | Review messages sent before the UID change. | Previous conversation history remains visible; partner's old messages remain attributed to the partner. | [ ] |
| A3 | Send three alternating messages from both phones. | All messages arrive on both devices, once each, in the same order. | [ ] |
| A4 | Force-stop and reopen UsApp on both phones. | Both accounts remain authenticated and the same chat history loads. | [ ] |
| A5 | Run the roster SQL verification in Supabase. | Exactly one room, exactly two members, both current UIDs present, old partner UID absent. | [ ] |
| A6 | Test the old UID with a valid old Firebase token, if that account/token is still available. | Old UID cannot read or insert messages in this room. Do not treat an unavailable/deleted old account as a passed denial test. | [ ] |

### A5 roster verification SQL

Replace the couple code locally; do not share the returned UIDs.

~~~sql
select
    r.id as room_id,
    r.firebase_couple_code,
    count(m.firebase_uid) as member_count,
    array_agg(m.firebase_uid order by m.firebase_uid) as firebase_members
from public.chat_rooms as r
join public.chat_room_members as m on m.room_id = r.id
where r.firebase_couple_code = 'YOUR_COUPLE_CODE'
group by r.id, r.firebase_couple_code;
~~~

Expected: one row, member_count = 2, and both UIDs match the current Firestore couples/{coupleCode}.members array.

---

## B. Fresh build gate for the new offline-outbox code

From the repository root in PowerShell:

~~~powershell
git fetch origin
git pull --ff-only origin feature/us-chat-2.5.0
.\gradlew.bat clean assembleDebug
~~~

- [ ] Pull completes as a fast-forward.
- [ ] clean assembleDebug reports BUILD SUCCESSFUL.
- [ ] Review all warnings; distinguish warnings from compiler errors.
- [ ] Do not install over an existing app unless the APK is signed with the matching existing certificate.
- [ ] Do not uninstall the production app or clear its data to work around a signing mismatch.

Build result / notes:

~~~text
Paste the actual Gradle result here.
~~~

---

## C. Stage 4.3.1 — durable offline outbox

Run on two paired phones after installing a build that passes Section B. Start with an already-opened conversation while online so the local room mapping is cached. First-time room discovery still requires connectivity.

| # | Test | Steps | Expected result | Status |
|---|---|---|---|---|
| C1 | Online send regression | Send a normal message while connected. | Message is saved locally, delivered, and appears once on both phones. | [ ] |
| C2 | Offline queue | Turn off Wi-Fi and mobile data on Phone A; send one message. | Message remains visible on A as Waiting for connection; no HTTP request error banner appears; draft is cleared only after local SQLite save succeeds. | [ ] |
| C3 | Automatic delivery | Restore connectivity on A. Do not tap Send again. | WorkManager automatically sends the queued message; B receives it; one server row exists. | [ ] |
| C4 | Offline app restart | Queue a message offline, force-stop UsApp, reopen while still offline. | Cached conversation opens; queued message remains visible and is not lost. | [ ] |
| C5 | Process-death recovery | Queue a message, force-stop the app, restore network. | WorkManager resumes delivery after connectivity is available; one server row exists. | [ ] |
| C6 | Multiple queued messages | Queue at least three distinct messages offline in a known order, then reconnect. | Partner receives all messages in the original local send order, without duplicates. | [ ] |
| C7 | Ambiguous response / idempotency | Simulate a request accepted by the server where the client does not receive the success response, then allow retry. | Retry uses the same client UUID and produces only one chat_messages row. Use a controlled test; do not tamper with production data. | [ ] |
| C8 | Manual retry | Cause a controlled permanent failure, wait for Not sent, then restore valid conditions and tap Retry. | The same logical message is retried with its original client UUID; only one server row exists. | [ ] |
| C9 | Per-room queue continuation | Make one queued item permanently fail, followed by a valid queued item. | Failed item is visibly failed; subsequent item still gets a chance to send. | [ ] |
| C10 | Account switch protection | With a test setup, queue under account A, switch to account B before delivery. | A's queued message is never sent with B's Firebase identity and is never shown as B's message. | [ ] |
| C11 | Return to original account | Sign back into account A in the controlled test. | A's pending/failed item remains associated with A and can be retried safely. | [ ] |
| C12 | Server confirmation cleanup | Allow a queued message to be sent and then perform an authoritative chat refresh. | Local outbox row is removed only after the server page confirms the matching client message UUID. | [ ] |

### C-stage database spot checks

Use Supabase SQL Editor. Replace values locally; never paste actual UIDs or message bodies into a public issue.

Check for duplicate logical sends:

~~~sql
select
    room_id,
    sender_uid,
    client_message_id,
    count(*) as row_count
from public.chat_messages
where client_message_id is not null
group by room_id, sender_uid, client_message_id
having count(*) > 1;
~~~

Expected: zero rows.

Check a specific test message using its client UUID:

~~~sql
select id, room_id, sender_uid, client_message_id, created_at
from public.chat_messages
where client_message_id = 'YOUR_TEST_CLIENT_MESSAGE_UUID';
~~~

Expected: exactly one row for the test UUID.

---

## D. Deferred Stage 4.2 reliability and security checks

These were not completed as part of the basic live-message check. Record them before release.

| # | Test | Expected result | Status |
|---|---|---|---|
| D1 | Send at least 20 alternating messages. | Every message appears exactly once on both phones. | [ ] |
| D2 | Rapid simultaneous sending from both phones. | Both devices converge on the same deterministic message order. | [ ] |
| D3 | Create more than 50 messages and load older history. | No missing or duplicated messages; older-page loading eventually stops. | [ ] |
| D4 | Receive new messages while older history is loaded. | Previously loaded older rows remain in the timeline. | [ ] |
| D5 | Turn off one phone's network while the other continues sending, then reconnect. | Missed messages are recovered from the authoritative server query. | [ ] |
| D6 | Background and foreground the app. | Chat catches up without requiring a manual refresh. | [ ] |
| D7 | Leave and reopen Chat repeatedly. | No duplicate Realtime listeners, duplicate rows, or crashes. | [ ] |
| D8 | Use an unrelated Firebase account. | RLS denies room, roster, message, and read-cursor access. | [ ] |
| D9 | Attempt an insert with a mismatched sender UID. | RLS rejects the insert. | [ ] |
| D10 | Attempt cross-room message/read-cursor access. | RLS denies unauthorized cross-room access. | [ ] |
| D11 | Regression pass: pairing, Memories, Our Instant, Maps, location sharing, and settings. | Existing features continue working. | [ ] |

---

## E. Stage 4.3.3 — read receipts (not implemented yet)

Do not test or mark this section complete until the Android implementation and any required Realtime publication migration have been reviewed and pushed.

- [ ] Read cursor is created/advanced only for the current Firebase UID.
- [ ] Cursor advances only to a server-confirmed message.
- [ ] Partner cursor is visible to the other room member.
- [ ] Seen appears only for the latest applicable message sent by the current user.
- [ ] Read state updates live through Realtime and recovers after reconnect.
- [ ] A user cannot update the partner's cursor.
- [ ] An unrelated Firebase account cannot read or update room cursors.

---

## Result log

Record dates in local Philippine time. Do not mark a test passed based only on code review.

| Date/time (Asia/Manila) | Test IDs | Result | Bug / notes |
|---|---|---|---|
|  |  |  |  |
|  |  |  |  |
|  |  |  |  |

Acceptance rule: Stage 4.3 is not complete until the offline outbox, idempotency, account-switch, read-receipt, RLS, and two-phone checks pass. Keep PR #7 draft and unmerged until the developer explicitly approves it.
