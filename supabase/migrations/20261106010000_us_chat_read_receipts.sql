-- Us Chat Stage 4.3.3: read-cursor safety and live updates.
-- Existing chat_room_reads RLS already restricts each user to their own cursor
-- and permits both room members to read each other's cursor.

begin;

-- Prevent stale/concurrent clients from moving a read cursor backwards.
create or replace function public.prevent_chat_room_read_cursor_regression()
returns trigger
language plpgsql
set search_path = ''
as $function$
declare
    v_old_created_at timestamptz;
    v_new_created_at timestamptz;
begin
    if old.last_read_message_id is null
       or new.last_read_message_id is null
       or old.last_read_message_id = new.last_read_message_id then
        return new;
    end if;

    select m.created_at
      into v_old_created_at
      from public.chat_messages as m
     where m.room_id = old.room_id
       and m.id = old.last_read_message_id;

    select m.created_at
      into v_new_created_at
      from public.chat_messages as m
     where m.room_id = new.room_id
       and m.id = new.last_read_message_id;

    if v_old_created_at is not null
       and v_new_created_at is not null
       and (v_new_created_at, new.last_read_message_id)
           < (v_old_created_at, old.last_read_message_id) then
        new.last_read_message_id := old.last_read_message_id;
    end if;

    return new;
end;
$function$;

revoke all on function public.prevent_chat_room_read_cursor_regression()
    from public, anon, authenticated;

drop trigger if exists chat_room_reads_prevent_cursor_regression
    on public.chat_room_reads;

create trigger chat_room_reads_prevent_cursor_regression
before update of last_read_message_id
on public.chat_room_reads
for each row
execute function public.prevent_chat_room_read_cursor_regression();

-- Realtime is used as a signal only; PostgREST remains authoritative.
do $$
begin
    if not exists (
        select 1
        from pg_catalog.pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'chat_room_reads'
    ) then
        alter publication supabase_realtime add table public.chat_room_reads;
    end if;
end
$$;

commit;
