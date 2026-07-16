-- P6 pgTAP suite (spec §13). Exercises the real RLS policies/triggers/RPCs against fixture data,
-- impersonating different users via `request.jwt.claims` + `set local role authenticated` (the
-- same mechanism Supabase's own auth.uid() reads). Everything runs inside one transaction that
-- rolls back at the end, so no fixture data survives in the project.
--
-- Run with: apply via execute_sql against the target project. Not a schema migration — doesn't
-- belong in supabase/migrations/, kept here for reproducibility of the P6 proof gate.
--
-- Every assertion is INSERTed into a log table instead of just SELECTed directly — a plain
-- multi-statement batch only surfaces its last statement's result set through most SQL clients
-- (this one included), so without the log table every assertion but the final one is silently
-- dropped from view even though pgTAP ran it.

create extension if not exists pgtap;

begin;

select plan(34);

create temporary table pgtap_log (id serial primary key, line text);
grant insert on pgtap_log to authenticated;
grant usage on sequence pgtap_log_id_seq to authenticated;

create temporary table fx (key text primary key, value uuid);
insert into fx (key, value) values
  ('owner', gen_random_uuid()),
  ('admin_unscoped', gen_random_uuid()),
  ('admin_scoped', gen_random_uuid()),
  ('viewer', gen_random_uuid()),
  ('stranger', gen_random_uuid()),
  ('personal_owner', gen_random_uuid()),
  ('personal_friend', gen_random_uuid()),
  ('biz', gen_random_uuid()),
  ('book_a', gen_random_uuid()),
  ('book_b', gen_random_uuid()),
  ('tx1', gen_random_uuid()),
  ('personal_book', gen_random_uuid());

-- SECURITY DEFINER: once a test block does `set local role authenticated`, plain `authenticated`
-- has no privileges on this session-owned temp table, so the lookup would fail with
-- "permission denied for table fx" — running as the definer (the setup role) sidesteps that.
create or replace function fx(k text) returns uuid language sql security definer as $$ select value from fx where key = k $$;

insert into auth.users (id, email)
select value, key || '@test.local' from fx where key not in ('biz', 'book_a', 'book_b', 'tx1', 'personal_book');

insert into public.businesses (id, name, owner_uid, currency, created_at)
values (fx('biz'), 'Test Biz', fx('owner'), 'PKR', 0);
-- businesses_owner_membership trigger already made owner an ACTIVE OWNER member.

insert into public.business_members (business_id, user_uid, role, status, book_scoped)
values
  (fx('biz'), fx('admin_unscoped'), 'ADMIN', 'ACTIVE', false),
  (fx('biz'), fx('admin_scoped'), 'ADMIN', 'ACTIVE', true),
  (fx('biz'), fx('viewer'), 'VIEWER', 'ACTIVE', false);

insert into public.books (id, business_id, owner_uid, name, currency, created_at)
values
  (fx('book_a'), fx('biz'), fx('owner'), 'Book A', 'PKR', 0),
  (fx('book_b'), fx('biz'), fx('owner'), 'Book B', 'PKR', 0);

-- scoped admin is allow-listed for book_a only
insert into public.book_grants (book_id, user_uid, access)
values (fx('book_a'), fx('admin_scoped'), 'ALLOW');

-- viewer is DENYed book_b specifically
insert into public.book_grants (book_id, user_uid, access)
values (fx('book_b'), fx('viewer'), 'DENY');

insert into public.transactions (id, book_id, type, amount_paisa, description, created_at, created_by_uid)
values (fx('tx1'), fx('book_a'), 'CASH_IN', 10000, 'seed', 0, fx('owner'));

insert into public.books (id, business_id, owner_uid, name, currency, created_at)
values (fx('personal_book'), null, fx('personal_owner'), 'Personal', 'PKR', 0);

-- ── Helper to impersonate a user for the rest of the current statement batch ───────────────────
create or replace function fx_login(uid uuid) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', json_build_object('sub', uid, 'role', 'authenticated')::text, true);
  set local role authenticated;
end;
$$;

-- ══════════════════════════════ 0. Fresh-row upsert bootstrap (regression) ═════════════════════
-- Real bug found via on-device testing: PostgREST's upsert() always compiles to
-- INSERT ... ON CONFLICT DO UPDATE, which requires the SELECT policy to pass to check for a
-- conflicting row — even on a pure first-time insert with no actual conflict. The owner's own
-- business_members row doesn't exist until AFTER this insert's trigger runs, so a naive
-- business_role()-only policy deadlocks a brand-new business forever. Fixed by OR-ing in the
-- row's own ownership column (spec migration 20260712000004).
select fx_login(fx('owner'));
insert into pgtap_log(line) select lives_ok(
  format($$insert into public.businesses (id, name, owner_uid, currency, created_at, version, device_id, deleted_at)
           values (gen_random_uuid(), 'Bootstrap Biz', %L, 'PKR', 0, 1, 'dev1', null)
           on conflict (id) do update set name = excluded.name$$, fx('owner')),
  'owner can upsert (INSERT ... ON CONFLICT DO UPDATE) a brand-new business'
);

reset role;

-- ══════════════════════════════ 1. Non-member denied ══════════════════════════════════════════
select fx_login(fx('stranger'));
insert into pgtap_log(line) select is(
  (select count(*) from public.books where id in (fx('book_a'), fx('book_b')))::int, 0,
  'stranger (non-member) sees zero books in the business'
);

reset role;

-- ══════════════════════════════ 2. OWNER sees everything ══════════════════════════════════════
select fx_login(fx('owner'));
insert into pgtap_log(line) select is((select count(*) from public.books where business_id = fx('biz'))::int, 2, 'owner sees both books');

reset role;

-- ══════════════════════════════ 3. Unscoped ADMIN needs no grant row ═══════════════════════════
select fx_login(fx('admin_unscoped'));
insert into pgtap_log(line) select is((select count(*) from public.books where business_id = fx('biz'))::int, 2, 'unscoped admin sees both books with zero grants');

reset role;

-- ══════════════════════════════ 4. VIEWER: read ok, write denied ═══════════════════════════════
select fx_login(fx('viewer'));
insert into pgtap_log(line) select is((select count(*) from public.books where id = fx('book_a'))::int, 1, 'viewer can view book_a');
insert into pgtap_log(line) select throws_ok(
  format($$insert into public.transactions (id, book_id, type, amount_paisa, description, created_at, created_by_uid)
           values (gen_random_uuid(), %L, 'CASH_IN', 500, 'x', 0, %L)$$, fx('book_a'), fx('viewer')),
  '42501', null, 'VIEWER cannot insert a transaction (TX_ADD denied)'
);

reset role;

-- ══════════════════════════════ 5. DENY grant hides the book ═══════════════════════════════════
select fx_login(fx('viewer'));
insert into pgtap_log(line) select is((select count(*) from public.books where id = fx('book_b'))::int, 0, 'viewer with DENY grant cannot see book_b');

reset role;

-- ══════════════════════════════ 6. Scoped ADMIN allow-list ═════════════════════════════════════
select fx_login(fx('admin_scoped'));
insert into pgtap_log(line) select is((select count(*) from public.books where id = fx('book_a'))::int, 1, 'scoped admin sees the allow-listed book_a');
insert into pgtap_log(line) select is((select count(*) from public.books where id = fx('book_b'))::int, 0, 'scoped admin cannot see book_b (not allow-listed)');

reset role;

-- ══════════════════════════════ 7. Membership/grant/audit not client-writable ══════════════════
select fx_login(fx('owner'));
insert into pgtap_log(line) select throws_ok(
  format($$insert into public.business_members (business_id, user_uid, role) values (%L, %L, 'ADMIN')$$, fx('biz'), fx('stranger')),
  '42501', null, 'business_members has no client insert grant (RPC-only)'
);
insert into pgtap_log(line) select throws_ok(
  format($$insert into public.book_grants (book_id, user_uid, access) values (%L, %L, 'ALLOW')$$, fx('book_a'), fx('stranger')),
  '42501', null, 'book_grants has no client insert grant (RPC-only)'
);
insert into pgtap_log(line) select throws_ok(
  format($$insert into public.audit_log (actor_uid, action, entity_type) values (%L, 'X', 'Y')$$, fx('owner')),
  '42501', null, 'audit_log has no client insert grant (RPC-only)'
);

reset role;

-- ══════════════════════════════ 8. Last-owner protection ═══════════════════════════════════════
select fx_login(fx('owner'));
insert into pgtap_log(line) select throws_ok(
  format($$select public.revoke_member(%L, %L)$$, fx('biz'), fx('owner')),
  'P0001', 'LAST_OWNER: business must keep at least one active owner',
  'revoking the sole active owner is rejected'
);

reset role;

-- ══════════════════════════════ 9. Non-owner cannot change roles ═══════════════════════════════
select fx_login(fx('admin_unscoped'));
insert into pgtap_log(line) select throws_ok(
  format($$select public.update_member_role(%L, %L, 'VIEWER')$$, fx('biz'), fx('viewer')),
  '42501', null, 'non-owner ADMIN cannot call update_member_role (OWNER required)'
);

reset role;

-- ══════════════════════════════ 10. TX_EDIT vs TX_DELETE fine split ════════════════════════════
-- give admin_unscoped a per-book override with TX_EDIT but not TX_DELETE on book_a — fixture
-- setup, runs unimpersonated (book_grants is RPC-only for real clients, not for this seed step).
insert into public.book_grants (book_id, user_uid, access, perms_override)
values (fx('book_a'), fx('admin_unscoped'), 'ALLOW', '["BOOK_VIEW","TX_ADD","TX_EDIT"]'::jsonb);

select fx_login(fx('admin_unscoped'));
insert into pgtap_log(line) select lives_ok(
  format($$update public.transactions set description = 'edited' where id = %L$$, fx('tx1')),
  'admin with TX_EDIT (no TX_DELETE) override can edit the transaction'
);
insert into pgtap_log(line) select throws_ok(
  format($$update public.transactions set deleted_at = 1 where id = %L$$, fx('tx1')),
  '42501', null, 'same admin cannot soft-delete it (TX_DELETE not in override)'
);

reset role;

-- ══════════════════════════════ 11. Personal book access ═══════════════════════════════════════
select fx_login(fx('personal_owner'));
insert into pgtap_log(line) select is((select count(*) from public.books where id = fx('personal_book'))::int, 1, 'personal book owner can see it');

reset role;

select fx_login(fx('personal_friend'));
insert into pgtap_log(line) select is((select count(*) from public.books where id = fx('personal_book'))::int, 0, 'stranger cannot see a personal book with no grant');

reset role;

select fx_login(fx('personal_owner'));
select public.share_book(fx('personal_book'), 'personal_friend@test.local', array['BOOK_VIEW', 'TX_ADD']);

reset role;

select fx_login(fx('personal_friend'));
insert into pgtap_log(line) select is((select count(*) from public.books where id = fx('personal_book'))::int, 1, 'after share_book, friend can see the personal book');

reset role;

-- ══════════════════════════════ 12. lookup_user exact-match / no enumeration ═══════════════════
select fx_login(fx('owner'));
insert into pgtap_log(line) select is(public.lookup_user('OWNER@test.local'), fx('owner'), 'lookup_user matches case-insensitively');
insert into pgtap_log(line) select is(public.lookup_user('nobody@nowhere.invalid'), null, 'lookup_user returns null for unregistered address');

reset role;

-- ══════════════════════════════ 13. invite_to_business guardrails ══════════════════════════════
select fx_login(fx('viewer'));
insert into pgtap_log(line) select throws_ok(
  format($$select public.invite_to_business(%L, 'stranger@test.local', 'VIEWER')$$, fx('biz')),
  '42501', null, 'VIEWER cannot invite (MEMBER_MANAGE required)'
);

reset role;

select fx_login(fx('owner'));
insert into pgtap_log(line) select throws_ok(
  format($$select public.invite_to_business(%L, 'nobody@nowhere.invalid', 'VIEWER')$$, fx('biz')),
  'P0001', 'USER_NOT_REGISTERED', 'inviting an unregistered address is rejected'
);

reset role;

-- ══════ 14. share_book on a business book grants real visibility, not just an inert row (regression) ══
-- Real bug found via on-device testing: share_book() inserted only a book_grants row. But
-- effective_perms() for a business-owned book hard-requires an ACTIVE business_members row before
-- it even looks at book_grants -- so sharing a business book with someone who wasn't already a
-- member was a complete no-op: they could see neither the business (sel_biz gates on
-- business_role()) nor the book. Fixed (migration 20260712000005) by having share_book also insert
-- a minimal VIEWER, book_scoped=true membership row when the target isn't already a member.
select fx_login(fx('owner'));
insert into pgtap_log(line) select is((select count(*) from public.business_members where business_id = fx('biz') and user_uid = fx('stranger'))::int, 0, 'stranger has no membership before share_book');
select public.share_book(fx('book_a'), 'stranger@test.local', array['BOOK_VIEW']);

reset role;

select fx_login(fx('stranger'));
insert into pgtap_log(line) select is((select count(*) from public.businesses where id = fx('biz'))::int, 1, 'after share_book, stranger can see the business');
insert into pgtap_log(line) select is((select count(*) from public.books where id = fx('book_a'))::int, 1, 'after share_book, stranger can see the shared book');
-- Real bug found via on-device testing (2nd part): effective_perms()'s book_scoped allow-list
-- check only fired for role = 'ADMIN'. share_book creates a VIEWER, book_scoped=true row, which
-- fell through that check untouched and got BOOK_VIEW on every book in the business via the
-- VIEWER role-default branch -- not just book_a. Fixed (migration 20260712000006) by generalizing
-- the scoping check to any role.
insert into pgtap_log(line) select is((select count(*) from public.books where id = fx('book_b'))::int, 0, 'scoped VIEWER from share_book cannot see the business''s OTHER book (not allow-listed)');

reset role;

-- ══════ 15. audit_log reused as history_log's remote store (P8) — gated the same as BOOK_VIEW ══
-- Edit-history rows never left the device that made them (no server table, no push/pull) — a
-- viewer on a different device saw nothing. Fixed by reusing audit_log (same RLS shape already
-- needed: book_id -> BOOK_VIEW) instead of a new table.
select fx_login(fx('viewer'));
insert into pgtap_log(line) select lives_ok(
  format($$insert into public.audit_log (book_id, actor_uid, action, entity_type, entity_id, changes, device_id) values (%L, %L, 'RENAMED', 'BOOK', %L, 'name=Old->New', 'dev1')$$, fx('book_a'), fx('viewer'), fx('book_a')),
  'viewer with BOOK_VIEW on book_a can insert a history row for it'
);

reset role;

select fx_login(fx('stranger'));
insert into pgtap_log(line) select throws_ok(
  format($$insert into public.audit_log (book_id, actor_uid, action, entity_type, entity_id) values (%L, %L, 'RENAMED', 'BOOK', %L)$$, fx('book_b'), fx('stranger'), fx('book_b')),
  '42501', null, 'stranger without BOOK_VIEW on book_b cannot insert a history row for it'
);

reset role;

select fx_login(fx('admin_unscoped'));
insert into pgtap_log(line) select is((select count(*) from public.audit_log where book_id = fx('book_a') and action = 'RENAMED')::int, 1, 'co-member with BOOK_VIEW on book_a can read the history row viewer inserted');

reset role;

-- ══════ 16. invite_to_business book_scope validates the book id, no raw FK crash (regression) ══
-- Real bug found on-device: inviting with a book_scope entry that doesn't exist server-side yet
-- (client picked a just-created book before its own CREATE outbox row pushed) crashed with a raw
-- Postgres FK violation (23503) instead of a clean, client-mapped error. Fixed (migration
-- 20260714000002) by validating each book id exists (and belongs to this business) before the
-- book_grants insert, raising BOOK_NOT_FOUND (P0001, same as share_book) instead.
select fx_login(fx('owner'));
insert into pgtap_log(line) select throws_ok(
  format(
    $$select public.invite_to_business(%L, 'stranger@test.local', 'VIEWER', array[%L]::uuid[])$$,
    fx('biz'), '00000000-0000-0000-0000-000000000000'
  ),
  'P0001', 'BOOK_NOT_FOUND', 'invite_to_business with a nonexistent book_scope id raises BOOK_NOT_FOUND, not a raw FK crash'
);
insert into pgtap_log(line) select lives_ok(
  format(
    $$select public.invite_to_business(%L, 'stranger@test.local', 'VIEWER', array[%L]::uuid[])$$,
    fx('biz'), fx('book_a')
  ),
  'invite_to_business with a real book_scope id still succeeds'
);

reset role;

-- ══════ 17. share_book reactivates a REVOKED member instead of leaving them stuck (regression) ══
-- Real bug found on-device: re-sharing a book with a previously-revoked business member reported
-- success but the recipient still saw nothing, because share_book's membership upsert was
-- `on conflict do nothing` -- a REVOKED row was never flipped back to ACTIVE, and effective_perms()
-- requires ACTIVE before it even looks at book_grants. Fixed (migration
-- 20260714000003) to `do update set status = 'ACTIVE'` — role/book_scoped untouched, so a revoked
-- unscoped ADMIN comes back as an unscoped ADMIN, not downgraded.
select fx_login(fx('owner'));
select public.revoke_member(fx('biz'), fx('stranger'));
insert into pgtap_log(line) select is(
  (select status from public.business_members where business_id = fx('biz') and user_uid = fx('stranger')),
  'REVOKED', 'stranger is revoked before re-share'
);
select public.share_book(fx('book_a'), 'stranger@test.local', array['BOOK_VIEW']);
insert into pgtap_log(line) select is(
  (select status from public.business_members where business_id = fx('biz') and user_uid = fx('stranger')),
  'ACTIVE', 'share_book reactivates the revoked member'
);

reset role;

insert into pgtap_log(line) select * from finish();

select string_agg(line, E'\n' order by id) as report from pgtap_log;

rollback;
