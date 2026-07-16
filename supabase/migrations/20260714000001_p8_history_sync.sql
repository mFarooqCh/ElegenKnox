-- P8: edit-history cross-device sync. The book/transaction edit-history feature (history_log,
-- local-only) never left the device that made the change — a viewer on a different device saw
-- nothing, since there was no server table and no push/pull wiring for it at all (real bug found
-- via on-device testing: viewer role couldn't see book/entry history).
--
-- Reuses audit_log (P6) instead of a new table: it already has the exact RLS shape history needs
-- (book_id -> BOOK_VIEW gates visibility), just add the 2 columns the client's HistoryEntity
-- needs and an insert policy (RPCs were the only writer before; plain CRUD writes directly now).

alter table public.audit_log
  add column if not exists changes text,
  add column if not exists device_id text;

-- actor_uid = auth.uid() (no impersonation); requires BOOK_VIEW on the book the row is about, same
-- gate as reading books/transactions themselves. business_id stays null for these rows (client
-- never sends it) — the business-wide OR-branch below is unused by history the RPC-written rows
-- (BUSINESS_MEMBER/BOOK_GRANT audit entries) still rely on it, untouched.
create policy ins_audit on public.audit_log for insert with check (
  actor_uid = auth.uid()
  and book_id is not null
  and 'BOOK_VIEW' = any(effective_perms(book_id))
);

grant insert on public.audit_log to authenticated;
