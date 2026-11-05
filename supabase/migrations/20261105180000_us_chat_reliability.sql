-- Us Chat Stage 4.2/4.3 reliability support.
-- client_message_id makes a retry safe when the server accepted a message but
-- the phone lost the response. The partial unique index preserves legacy rows.
-- Read receipts use the existing chat_room_reads table and its existing RLS.
begin;

alter table public.chat_messages
    add column if not exists client_message_id uuid;

create unique index if not exists chat_messages_client_message_id_unique
    on public.chat_messages (room_id, sender_uid, client_message_id);

grant insert (client_message_id)
    on public.chat_messages to authenticated;

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
