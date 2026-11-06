-- Us Chat message controls:
-- 1) "Remove for me" stores a private per-member hide marker.
-- 2) "Remove for everyone" irreversibly clears the active message body while
--    retaining a tombstone row so ordering, read cursors, and references remain valid.

begin;

alter table public.chat_messages
    add column if not exists deleted_by_uid text;

-- The original body check required non-empty text. A deleted message must
-- have an empty body, and its UI renders the tombstone from deleted_at.
alter table public.chat_messages
    drop constraint if exists chat_messages_body_check;

alter table public.chat_messages
    add constraint chat_messages_body_deleted_state_check
    check (
        (
            deleted_at is null
            and char_length(body) between 1 and 4000
            and char_length(btrim(body)) > 0
        )
        or
        (
            deleted_at is not null
            and body = ''
        )
    );

create table if not exists public.chat_message_user_hides (
    room_id uuid not null,
    message_id uuid not null,
    hidden_by_uid text not null,
    hidden_at timestamptz not null default now(),
    constraint chat_message_user_hides_pkey
        primary key (room_id, message_id, hidden_by_uid),
    constraint chat_message_user_hides_message_fk
        foreign key (room_id, message_id)
        references public.chat_messages(room_id, id)
        on delete cascade,
    constraint chat_message_user_hides_member_fk
        foreign key (room_id, hidden_by_uid)
        references public.chat_room_members(room_id, firebase_uid)
        on delete cascade
);

create index if not exists chat_message_user_hides_reader_idx
    on public.chat_message_user_hides(room_id, hidden_by_uid, hidden_at desc);

alter table public.chat_message_user_hides enable row level security;

revoke all on table public.chat_message_user_hides from anon, authenticated;
grant select on table public.chat_message_user_hides to authenticated;

create policy chat_message_user_hides_trusted_firebase_only
    on public.chat_message_user_hides
    as restrictive
    for all
    to authenticated
    using ((select public.is_usapp_firebase_jwt()))
    with check ((select public.is_usapp_firebase_jwt()));

create policy chat_message_user_hides_select_own
    on public.chat_message_user_hides
    for select
    to authenticated
    using (
        hidden_by_uid = (select public.current_firebase_uid())
        and (select public.is_chat_room_member(room_id))
    );

-- Each procedure derives the actor from the verified Firebase JWT. The Android
-- client cannot supply a different hidden_by_uid or deleted_by_uid.

create or replace function public.chat_hide_message_for_me(
    p_room_id uuid,
    p_message_id uuid
)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid text;
begin
    v_uid := public.current_firebase_uid();

    if v_uid is null or not public.is_chat_room_member(p_room_id) then
        raise exception using
            errcode = '42501',
            message = 'You are not allowed to hide messages in this conversation.';
    end if;

    if not exists (
        select 1
        from public.chat_messages as m
        where m.room_id = p_room_id
          and m.id = p_message_id
    ) then
        raise exception using
            errcode = 'P0002',
            message = 'That message no longer exists.';
    end if;

    insert into public.chat_message_user_hides (
        room_id,
        message_id,
        hidden_by_uid
    )
    values (
        p_room_id,
        p_message_id,
        v_uid
    )
    on conflict (room_id, message_id, hidden_by_uid) do nothing;
end;
$function$;

create or replace function public.chat_remove_message_for_everyone(
    p_room_id uuid,
    p_message_id uuid
)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid text;
    v_sender_uid text;
    v_deleted_at timestamptz;
begin
    v_uid := public.current_firebase_uid();

    if v_uid is null or not public.is_chat_room_member(p_room_id) then
        raise exception using
            errcode = '42501',
            message = 'You are not allowed to remove messages in this conversation.';
    end if;

    select m.sender_uid, m.deleted_at
      into v_sender_uid, v_deleted_at
      from public.chat_messages as m
     where m.room_id = p_room_id
       and m.id = p_message_id
     for update;

    if not found then
        raise exception using
            errcode = 'P0002',
            message = 'That message no longer exists.';
    end if;

    if v_sender_uid <> v_uid then
        raise exception using
            errcode = '42501',
            message = 'Only the sender can remove this message for everyone.';
    end if;

    -- Idempotent retry: preserve an existing tombstone without rewriting it.
    if v_deleted_at is not null then
        return;
    end if;

    update public.chat_messages
       set body = '',
           deleted_at = pg_catalog.clock_timestamp(),
           deleted_by_uid = v_uid
     where room_id = p_room_id
       and id = p_message_id
       and sender_uid = v_uid
       and deleted_at is null;
end;
$function$;

revoke all on function public.chat_hide_message_for_me(uuid, uuid)
    from public, anon;
revoke all on function public.chat_remove_message_for_everyone(uuid, uuid)
    from public, anon;

grant execute on function public.chat_hide_message_for_me(uuid, uuid)
    to authenticated;
grant execute on function public.chat_remove_message_for_everyone(uuid, uuid)
    to authenticated;

-- Realtime is a signal only; the Android client reloads authoritative rows.
do $$
begin
    if not exists (
        select 1
        from pg_catalog.pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'chat_message_user_hides'
    ) then
        alter publication supabase_realtime add table public.chat_message_user_hides;
    end if;
end
$$;

comment on column public.chat_messages.deleted_at is
    'When set, the message body is cleared and clients render the shared deleted-message tombstone.';
comment on column public.chat_messages.deleted_by_uid is
    'Firebase UID that removed the message for everyone.';
comment on table public.chat_message_user_hides is
    'Per-member soft-hide markers used by Remove for me; they do not delete the shared message.';

commit;
