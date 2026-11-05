-- Enable database INSERT events for Us Chat Realtime.
-- Realtime is opt-in per table in Supabase's supabase_realtime publication.
do $$
begin
    if not exists (
        select 1
        from pg_catalog.pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'chat_messages'
    ) then
        alter publication supabase_realtime add table public.chat_messages;
    end if;
end
$$;
