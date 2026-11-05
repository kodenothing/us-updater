-- Us Chat Stage 4.2 idempotent-send support.
-- Existing messages keep a NULL client_message_id; new Android sends provide a UUID.
-- PostgreSQL's default NULLS DISTINCT behavior allows those legacy rows to coexist.
begin;

alter table public.chat_messages
    add column if not exists client_message_id uuid;

create unique index if not exists chat_messages_client_message_id_unique
    on public.chat_messages (room_id, sender_uid, client_message_id);

grant insert (client_message_id)
    on public.chat_messages to authenticated;

commit;
