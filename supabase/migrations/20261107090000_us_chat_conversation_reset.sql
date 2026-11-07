-- Us Chat reset:
-- Rotate the active conversation generation in the existing couple room.
-- Old messages are blanked and retained as hidden tombstones so message/read
-- references remain valid. Clients and offline workers must present the
-- generation they originally queued against; stale sends are rejected.

begin;

alter table public.chat_rooms
    add column if not exists active_conversation_id uuid;

update public.chat_rooms
   set active_conversation_id = pg_catalog.gen_random_uuid()
 where active_conversation_id is null;

alter table public.chat_rooms
    alter column active_conversation_id set default pg_catalog.gen_random_uuid(),
    alter column active_conversation_id set not null;

alter table public.chat_messages
    add column if not exists conversation_id uuid;

update public.chat_messages as m
   set conversation_id = r.active_conversation_id
  from public.chat_rooms as r
 where r.id = m.room_id
   and m.conversation_id is null;

alter table public.chat_messages
    alter column conversation_id set not null;

create index if not exists chat_messages_room_conversation_created_idx
    on public.chat_messages (room_id, conversation_id, created_at desc, id desc);

-- Read the active generation through a narrow SECURITY DEFINER helper so
-- message RLS can check room metadata without recursive policy evaluation.
create or replace function public.is_active_chat_conversation(
    p_room_id uuid,
    p_conversation_id uuid
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
    select exists (
        select 1
          from public.chat_rooms as r
         where r.id = p_room_id
           and r.active_conversation_id = p_conversation_id
    );
$function$;

revoke all on function public.is_active_chat_conversation(uuid, uuid)
    from public, anon;
grant execute on function public.is_active_chat_conversation(uuid, uuid)
    to authenticated;

-- Every insert takes a shared row lock. The reset RPC takes an exclusive row
-- lock, so a racing send either commits before the reset and is tombstoned,
-- or observes the new generation and is rejected.
create or replace function public.enforce_active_chat_conversation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_active_conversation_id uuid;
begin
    select r.active_conversation_id
      into v_active_conversation_id
      from public.chat_rooms as r
     where r.id = new.room_id
     for share;

    if not found
       or new.conversation_id is null
       or new.conversation_id is distinct from v_active_conversation_id then
        raise exception using
            errcode = '40001',
            message = 'This conversation was reset. Reopen Us Chat before sending.';
    end if;

    return new;
end;
$function$;

revoke all on function public.enforce_active_chat_conversation()
    from public, anon, authenticated;

drop trigger if exists chat_messages_enforce_active_conversation
    on public.chat_messages;

create trigger chat_messages_enforce_active_conversation
before insert on public.chat_messages
for each row
execute function public.enforce_active_chat_conversation();

-- The app's authenticated INSERT is still constrained by RLS and the trigger.
grant insert (conversation_id)
    on public.chat_messages to authenticated;

-- Historical generations stay in the table as tombstones, but are no longer
-- visible to either partner through the app's active-conversation queries.
drop policy if exists chat_messages_select_room_members
    on public.chat_messages;

create policy chat_messages_select_room_members
    on public.chat_messages
    for select
    to authenticated
    using (
        (select public.is_chat_room_member(room_id))
        and (select public.is_active_chat_conversation(room_id, conversation_id))
    );

drop policy if exists chat_messages_insert_as_self
    on public.chat_messages;

create policy chat_messages_insert_as_self
    on public.chat_messages
    for insert
    to authenticated
    with check (
        sender_uid = (select public.current_firebase_uid())
        and message_type = 'text'
        and (select public.is_chat_room_member(room_id))
        and (select public.is_active_chat_conversation(room_id, conversation_id))
    );

-- Atomically blank the previous generation, then rotate the room to a new
-- UUID. The stable room/membership rows preserve the existing one-couple model.
create or replace function public.chat_reset_conversation(p_room_id uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid text;
    v_old_conversation_id uuid;
    v_new_conversation_id uuid;
begin
    v_uid := public.current_firebase_uid();

    if v_uid is null then
        raise exception using
            errcode = '42501',
            message = 'Your paired account could not be verified.';
    end if;

    select r.active_conversation_id
      into v_old_conversation_id
      from public.chat_rooms as r
     where r.id = p_room_id
     for update;

    if not found or not public.is_chat_room_member(p_room_id) then
        raise exception using
            errcode = '42501',
            message = 'You are not allowed to reset this conversation.';
    end if;

    update public.chat_messages
       set body = '',
           deleted_at = pg_catalog.clock_timestamp(),
           deleted_by_uid = v_uid
     where room_id = p_room_id
       and conversation_id = v_old_conversation_id
       and deleted_at is null;

    v_new_conversation_id := pg_catalog.gen_random_uuid();

    update public.chat_rooms
       set active_conversation_id = v_new_conversation_id
     where id = p_room_id;

    return v_new_conversation_id;
end;
$function$;

revoke all on function public.chat_reset_conversation(uuid)
    from public, anon;
grant execute on function public.chat_reset_conversation(uuid)
    to authenticated;

-- Realtime notifies a partner whose chat is already open. On a missed event,
-- the client also reconciles the active generation on refresh/reconnect.
do $$
begin
    if not exists (
        select 1
        from pg_catalog.pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'chat_rooms'
    ) then
        alter publication supabase_realtime add table public.chat_rooms;
    end if;
end
$$;

alter table public.chat_rooms replica identity full;

comment on column public.chat_rooms.active_conversation_id is
    'Current Us Chat generation. Rotated only by chat_reset_conversation.';
comment on column public.chat_messages.conversation_id is
    'Generation in which a message was sent. Old generations are hidden and stale inserts are rejected.';
comment on function public.chat_reset_conversation(uuid) is
    'Clears active message bodies to tombstones and atomically starts a fresh Us Chat generation for both room members.';

commit;
