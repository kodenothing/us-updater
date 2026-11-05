begin;

-- Us Chat database foundation.
-- Firebase UIDs are opaque text values, not UUIDs. Do not use auth.uid()
-- for these users; read the Firebase JWT "sub" claim as text instead.

create table public.chat_rooms (
    id uuid primary key default gen_random_uuid(),
    firebase_couple_code text not null unique,
    created_at timestamptz not null default now(),
    constraint chat_rooms_couple_code_format
        check (firebase_couple_code ~ '^[A-HJKMNP-Z2-9]{8}([A-HJKMNP-Z2-9]{4})?$')
);

create table public.chat_room_members (
    room_id uuid not null
        references public.chat_rooms(id) on delete cascade,
    firebase_uid text not null
        check (char_length(firebase_uid) between 1 and 128),
    joined_at timestamptz not null default now(),
    constraint chat_room_members_pkey primary key (room_id, firebase_uid),
    constraint chat_room_members_one_room_per_user unique (firebase_uid)
);

create table public.chat_messages (
    id uuid primary key default gen_random_uuid(),
    room_id uuid not null
        references public.chat_rooms(id) on delete cascade,
    sender_uid text not null
        check (char_length(sender_uid) between 1 and 128),
    message_type text not null default 'text'
        check (message_type = 'text'),
    body text not null
        check (
            char_length(body) between 1 and 4000
            and char_length(btrim(body)) > 0
        ),
    created_at timestamptz not null default now(),
    reply_to_message_id uuid null,
    edited_at timestamptz null,
    deleted_at timestamptz null,
    constraint chat_messages_room_id_id_key unique (room_id, id),
    constraint chat_messages_reply_same_room_fk
        foreign key (room_id, reply_to_message_id)
        references public.chat_messages(room_id, id)
);

create index chat_messages_room_created_idx
    on public.chat_messages (room_id, created_at desc, id desc);

create table public.chat_room_reads (
    room_id uuid not null,
    reader_uid text not null,
    last_read_message_id uuid null,
    last_read_at timestamptz not null default now(),
    constraint chat_room_reads_pkey primary key (room_id, reader_uid),
    constraint chat_room_reads_member_fk
        foreign key (room_id, reader_uid)
        references public.chat_room_members(room_id, firebase_uid)
        on delete cascade,
    constraint chat_room_reads_message_same_room_fk
        foreign key (room_id, last_read_message_id)
        references public.chat_messages(room_id, id)
);

-- Only tokens issued for this exact Firebase project are recognized.
-- The "role" claim is the custom claim provisioned by the local Admin SDK.
create or replace function public.is_usapp_firebase_jwt()
returns boolean
language sql
stable
set search_path = ''
as $function$
    select coalesce(
        (auth.jwt() ->> 'iss') = 'https://securetoken.google.com/us-project-f6fc5'
        and (auth.jwt() ->> 'aud') = 'us-project-f6fc5'
        and (auth.jwt() ->> 'role') = 'authenticated'
        and nullif(auth.jwt() ->> 'sub', '') is not null,
        false
    );
$function$;

-- Returns the Firebase UID as text. auth.uid() is intentionally not used:
-- Supabase casts auth.uid()'s sub claim to UUID, but Firebase UIDs are not UUIDs.
create or replace function public.current_firebase_uid()
returns text
language sql
stable
set search_path = ''
as $function$
    select case
        when (select public.is_usapp_firebase_jwt())
        then (select auth.jwt() ->> 'sub')
        else null
    end;
$function$;

-- SECURITY DEFINER avoids recursive RLS evaluation when membership rows are
-- checked from policies on chat_room_members, chat_rooms, messages, and reads.
-- It returns only a boolean for the caller's own Firebase UID.
create or replace function public.is_chat_room_member(p_room_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
    select coalesce(
        (
            select count(*) = 2
                and coalesce(bool_or(m.firebase_uid = (select public.current_firebase_uid())), false)
            from public.chat_room_members as m
            where m.room_id = p_room_id
        ),
        false
    );
$function$;

-- Prevent accidental creation of a room with more than two Firebase members.
create or replace function public.enforce_chat_room_member_limit()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_member_count integer;
begin
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(new.room_id::text, 0)
    );

    select count(*)::integer
      into v_member_count
      from public.chat_room_members as m
     where m.room_id = new.room_id;

    if v_member_count >= 2 then
        raise exception using
            errcode = '23514',
            message = 'A Us Chat room can have only two members.';
    end if;

    return new;
end;
$function$;

create trigger chat_room_members_limit_two
before insert on public.chat_room_members
for each row execute function public.enforce_chat_room_member_limit();

-- Read timestamps are server-generated; clients can only move the message cursor.
create or replace function public.touch_chat_room_read_timestamp()
returns trigger
language plpgsql
set search_path = ''
as $function$
begin
    new.last_read_at := pg_catalog.clock_timestamp();
    return new;
end;
$function$;

create trigger chat_room_reads_touch_timestamp
before insert or update of last_read_message_id on public.chat_room_reads
for each row execute function public.touch_chat_room_read_timestamp();

-- Lock down table privileges first. RLS policies do not replace SQL grants.
alter table public.chat_rooms enable row level security;
alter table public.chat_room_members enable row level security;
alter table public.chat_messages enable row level security;
alter table public.chat_room_reads enable row level security;

revoke all on table
    public.chat_rooms,
    public.chat_room_members,
    public.chat_messages,
    public.chat_room_reads
from anon, authenticated;

grant select on public.chat_rooms to authenticated;
grant select on public.chat_room_members to authenticated;

grant select on public.chat_messages to authenticated;
grant insert (room_id, sender_uid, message_type, body, reply_to_message_id)
    on public.chat_messages to authenticated;

grant select on public.chat_room_reads to authenticated;
grant insert (room_id, reader_uid, last_read_message_id)
    on public.chat_room_reads to authenticated;
grant update (last_read_message_id)
    on public.chat_room_reads to authenticated;

-- Remove default PUBLIC execute rights from helper functions.
revoke all on function public.is_usapp_firebase_jwt() from public, anon;
revoke all on function public.current_firebase_uid() from public, anon;
revoke all on function public.is_chat_room_member(uuid) from public, anon;
revoke all on function public.enforce_chat_room_member_limit() from public, anon;
revoke all on function public.touch_chat_room_read_timestamp() from public, anon;

grant execute on function public.is_usapp_firebase_jwt() to authenticated;
grant execute on function public.current_firebase_uid() to authenticated;
grant execute on function public.is_chat_room_member(uuid) to authenticated;

-- Each table has a restrictive Firebase-project gate in addition to its
-- operation-specific membership policy.
create policy chat_rooms_trusted_firebase_only
    on public.chat_rooms
    as restrictive
    for all
    to authenticated
    using ((select public.is_usapp_firebase_jwt()))
    with check ((select public.is_usapp_firebase_jwt()));

create policy chat_room_members_trusted_firebase_only
    on public.chat_room_members
    as restrictive
    for all
    to authenticated
    using ((select public.is_usapp_firebase_jwt()))
    with check ((select public.is_usapp_firebase_jwt()));

create policy chat_messages_trusted_firebase_only
    on public.chat_messages
    as restrictive
    for all
    to authenticated
    using ((select public.is_usapp_firebase_jwt()))
    with check ((select public.is_usapp_firebase_jwt()));

create policy chat_room_reads_trusted_firebase_only
    on public.chat_room_reads
    as restrictive
    for all
    to authenticated
    using ((select public.is_usapp_firebase_jwt()))
    with check ((select public.is_usapp_firebase_jwt()));

-- Room and membership metadata are visible only to the two members of that room.
create policy chat_rooms_select_room_members
    on public.chat_rooms
    for select
    to authenticated
    using ((select public.is_chat_room_member(id)));

create policy chat_room_members_select_room_members
    on public.chat_room_members
    for select
    to authenticated
    using ((select public.is_chat_room_member(room_id)));

-- Text-only message access. A client cannot choose another sender identity.
create policy chat_messages_select_room_members
    on public.chat_messages
    for select
    to authenticated
    using ((select public.is_chat_room_member(room_id)));

create policy chat_messages_insert_as_self
    on public.chat_messages
    for insert
    to authenticated
    with check (
        sender_uid = (select public.current_firebase_uid())
        and message_type = 'text'
        and (select public.is_chat_room_member(room_id))
    );

-- Both partners can see each other's read cursor; each user can write only
-- their own cursor for a room they belong to.
create policy chat_room_reads_select_room_members
    on public.chat_room_reads
    for select
    to authenticated
    using ((select public.is_chat_room_member(room_id)));

create policy chat_room_reads_insert_own_cursor
    on public.chat_room_reads
    for insert
    to authenticated
    with check (
        reader_uid = (select public.current_firebase_uid())
        and (select public.is_chat_room_member(room_id))
    );

create policy chat_room_reads_update_own_cursor
    on public.chat_room_reads
    for update
    to authenticated
    using (
        reader_uid = (select public.current_firebase_uid())
        and (select public.is_chat_room_member(room_id))
    )
    with check (
        reader_uid = (select public.current_firebase_uid())
        and (select public.is_chat_room_member(room_id))
    );

comment on table public.chat_rooms is
    'One private text-chat room per existing UsApp Firebase couple.';
comment on table public.chat_room_members is
    'Privileged roster of the two Firebase UIDs belonging to a Us Chat room. Clients have read-only access.';
comment on table public.chat_messages is
    'Text-only Us Chat messages. Client updates and deletes are disabled in the initial schema.';
comment on table public.chat_room_reads is
    'Per-member read cursor for a Us Chat room. The server sets last_read_at.';

commit;
