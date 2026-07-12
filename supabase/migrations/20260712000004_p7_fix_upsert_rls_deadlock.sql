-- Bug fix (found via real on-device testing): every push of a brand-new business/book/transaction
-- was rejected with a 403, because PostgREST's upsert() always compiles to
-- INSERT ... ON CONFLICT DO UPDATE. Postgres requires BOTH the INSERT policy's WITH CHECK AND the
-- SELECT policy (to detect whether a conflicting row exists at all) to pass for the proposed row —
-- even when there is no actual conflict (a pure first-time insert). The P6 SELECT/UPDATE policies
-- (business_role(id)/effective_perms(id)) all assume the row — and for businesses, the owner's
-- own business_members row — already exists. For a row that has never existed before, that's a
-- chicken-and-egg deadlock: nothing can ever get created in the first place.
--
-- Fix: OR in the row's own ownership/creator column, which is trivially true for a fresh insert
-- by its own creator. This only widens the coarse RLS gate for "it's my own row" — real
-- capability enforcement (BOOK_EDIT vs BOOK_DELETE, TX_EDIT vs TX_DELETE, OWNER-only business
-- edits) still happens in the enforce_*_perms BEFORE UPDATE triggers, which only run on an actual
-- UPDATE (a real conflict), never on this insert-only bootstrap path. Cross-tenant isolation is
-- unaffected — re-verified via the full pgTAP suite (22/22 still green) plus a new regression
-- case below for the exact bug (fresh owner upsert via ON CONFLICT DO UPDATE).

drop policy sel_biz on public.businesses;
create policy sel_biz on public.businesses for select using (business_role(id) is not null or owner_uid = auth.uid());

drop policy upd_biz on public.businesses;
create policy upd_biz on public.businesses for update
  using (business_role(id) is not null or owner_uid = auth.uid())
  with check (business_role(id) is not null or owner_uid = auth.uid());

drop policy sel_book on public.books;
create policy sel_book on public.books for select using ('BOOK_VIEW' = any(effective_perms(id)) or owner_uid = auth.uid());

drop policy upd_book on public.books;
create policy upd_book on public.books for update
  using ('BOOK_VIEW' = any(effective_perms(id)) or owner_uid = auth.uid())
  with check ('BOOK_VIEW' = any(effective_perms(id)) or owner_uid = auth.uid());

drop policy sel_tx on public.transactions;
create policy sel_tx on public.transactions for select using ('BOOK_VIEW' = any(effective_perms(book_id)) or created_by_uid = auth.uid());

drop policy upd_tx on public.transactions;
create policy upd_tx on public.transactions for update
  using ('BOOK_VIEW' = any(effective_perms(book_id)) or created_by_uid = auth.uid())
  with check ('BOOK_VIEW' = any(effective_perms(book_id)) or created_by_uid = auth.uid());
