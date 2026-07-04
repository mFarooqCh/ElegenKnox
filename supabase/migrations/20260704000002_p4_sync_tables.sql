-- P4: push-sync target tables (spec §6.3, §7, §8.5). Mirror the Room entities so the outbox
-- can PostgREST-upsert rows straight up. RLS is intentionally *owner-only* here — the full
-- two-level RBAC (business_members / book_grants / effective_perms) lands in P6. Until then a
-- row is reachable only by the user who owns it (owner_uid / created_by_uid = auth.uid()).
--
-- Envelope columns match what the client sends (see RemotePush): created_at / deleted_at are
-- client epoch-millis (bigint); updated_at is server-authoritative (timestamptz, trigger below,
-- spec §6.6 — device clocks are not trusted). syncState is client-only and never sent.
--
-- Apply via the dashboard SQL Editor (auto-mode blocks DDL against a live DB — by design).

create table public.businesses (
  id uuid primary key,
  name text not null,
  owner_uid uuid not null references auth.users (id) on delete cascade,
  currency text not null,
  created_at bigint not null,
  -- sync envelope
  version bigint not null default 1,
  updated_at timestamptz not null default now(),
  device_id text,
  deleted_at bigint
);

create table public.books (
  id uuid primary key,
  business_id uuid references public.businesses (id) on delete cascade,
  owner_uid uuid not null references auth.users (id) on delete cascade,
  name text not null,
  currency text not null,
  created_at bigint not null,
  version bigint not null default 1,
  updated_at timestamptz not null default now(),
  device_id text,
  deleted_at bigint
);
create index books_business_id_idx on public.books (business_id);

create table public.transactions (
  id uuid primary key,
  book_id uuid not null references public.books (id) on delete cascade,
  type text not null,
  amount_paisa bigint not null,
  category_id uuid,
  description text not null,
  created_at bigint not null,
  created_by_uid uuid not null references auth.users (id) on delete cascade,
  version bigint not null default 1,
  updated_at timestamptz not null default now(),
  device_id text,
  deleted_at bigint
);
create index transactions_book_id_created_at_idx on public.transactions (book_id, created_at);

-- Server-authoritative updated_at + version on every update (LWW anchor, spec §6.6).
-- Reuses public.touch_sync_envelope() defined in the P3 users migration.
create trigger businesses_touch_envelope
  before update on public.businesses
  for each row execute procedure public.touch_sync_envelope();
create trigger books_touch_envelope
  before update on public.books
  for each row execute procedure public.touch_sync_envelope();
create trigger transactions_touch_envelope
  before update on public.transactions
  for each row execute procedure public.touch_sync_envelope();

-- Owner-only RLS (P4). Upsert needs select+insert+update; deletes are soft (tombstone), never hard.
alter table public.businesses enable row level security;
alter table public.books enable row level security;
alter table public.transactions enable row level security;

grant select, insert, update on public.businesses to authenticated;
grant select, insert, update on public.books to authenticated;
grant select, insert, update on public.transactions to authenticated;

create policy businesses_owner_all on public.businesses
  for all using (owner_uid = (select auth.uid())) with check (owner_uid = (select auth.uid()));
create policy books_owner_all on public.books
  for all using (owner_uid = (select auth.uid())) with check (owner_uid = (select auth.uid()));
create policy transactions_owner_all on public.transactions
  for all using (created_by_uid = (select auth.uid())) with check (created_by_uid = (select auth.uid()));
