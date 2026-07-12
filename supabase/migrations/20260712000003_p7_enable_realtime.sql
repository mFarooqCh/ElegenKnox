-- P7: foreground live updates. Realtime's postgres_changes respects each connected client's own
-- RLS (same effective_perms()-gated policies as PostgREST), so no extra server-side filtering is
-- needed beyond adding these tables to the replication publication.
alter publication supabase_realtime add table public.books, public.transactions;
