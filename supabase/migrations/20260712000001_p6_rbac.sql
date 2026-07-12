-- P6: RBAC backend + permission core (spec §8.3-§8.7). Replaces P4's owner-only RLS with
-- full two-level capability model: business role (OWNER/ADMIN/VIEWER) is the coarse default
-- bundle, book_grants is the per-book override / allow-deny list. Enforcement is
-- server-authoritative: RLS policies gate coarsely (row visible / not), BEFORE UPDATE
-- triggers enforce the fine-grained capability split (e.g. TX_EDIT vs TX_DELETE) that plain
-- RLS USING/WITH CHECK can't express (no OLD-vs-NEW column diff in a policy predicate).
--
-- Apply via the dashboard SQL Editor for hosted prod (auto-mode blocks DDL against a live DB
-- by design) — this file is the source of truth either way.

-- ============================================================================
-- 1. New tables
-- ============================================================================

create table public.business_members (
  id uuid primary key default gen_random_uuid(),
  business_id uuid not null references public.businesses (id) on delete cascade,
  user_uid uuid not null references auth.users (id) on delete cascade,
  role text not null check (role in ('OWNER', 'ADMIN', 'VIEWER')),
  status text not null default 'ACTIVE' check (status in ('ACTIVE', 'REVOKED')),
  -- true when this admin was invited with a book_scope allow-list (spec §8.3 adminIsScoped(m));
  -- stored directly on the membership row rather than inferred from grant existence, since an
  -- unscoped admin may still later pick up an incidental DENY grant on one book.
  book_scoped boolean not null default false,
  invited_by_uid uuid references auth.users (id),
  joined_at timestamptz not null default now(),
  -- sync envelope
  version bigint not null default 1,
  updated_at timestamptz not null default now(),
  device_id text,
  deleted_at bigint,
  unique (business_id, user_uid)
);
create index business_members_user_uid_idx on public.business_members (user_uid);
create index business_members_business_id_idx on public.business_members (business_id);

create table public.book_grants (
  id uuid primary key default gen_random_uuid(),
  book_id uuid not null references public.books (id) on delete cascade,
  user_uid uuid not null references auth.users (id) on delete cascade,
  access text not null check (access in ('ALLOW', 'DENY')),
  perms_override jsonb,
  granted_by_uid uuid references auth.users (id),
  created_at timestamptz not null default now(),
  -- sync envelope
  version bigint not null default 1,
  updated_at timestamptz not null default now(),
  device_id text,
  deleted_at bigint,
  unique (book_id, user_uid)
);
create index book_grants_book_id_idx on public.book_grants (book_id);
create index book_grants_user_uid_idx on public.book_grants (user_uid);

create table public.audit_log (
  id uuid primary key default gen_random_uuid(),
  business_id uuid references public.businesses (id) on delete cascade,
  book_id uuid references public.books (id) on delete cascade,
  actor_uid uuid not null references auth.users (id),
  action text not null,
  entity_type text not null,
  entity_id uuid,
  at timestamptz not null default now()
);

alter table public.business_members enable row level security;
alter table public.book_grants enable row level security;
alter table public.audit_log enable row level security;

-- Select only — membership/grant/audit writes go through SECURITY DEFINER RPCs below, which
-- write as the function owner and so don't need (and must not have) a client grant here.
grant select on public.business_members to authenticated;
grant select on public.book_grants to authenticated;
grant select on public.audit_log to authenticated;

-- ============================================================================
-- 2. Helper functions (used inside RLS policies and triggers)
-- ============================================================================

create or replace function public.all_permissions()
returns text[]
language sql
immutable
set search_path = public
as $$
  select array['BOOK_VIEW', 'TX_ADD', 'TX_EDIT', 'TX_DELETE', 'BOOK_ADD', 'BOOK_EDIT',
               'BOOK_DELETE', 'MEMBER_MANAGE', 'BUSINESS_EDIT', 'BUSINESS_DELETE']
$$;

-- SECURITY DEFINER so it can read business_members from inside that table's own RLS policy
-- (and from books/transactions policies) without recursing through RLS itself.
create or replace function public.business_role(p_business_id uuid)
returns text
language sql
stable
security definer
set search_path = public
as $$
  select role from business_members
  where business_id = p_business_id and user_uid = auth.uid() and status = 'ACTIVE'
$$;

-- Mirrors spec §8.3 effective(user, book) pseudocode exactly.
create or replace function public.effective_perms(p_book_id uuid)
returns text[]
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_business_id uuid;
  v_owner_uid uuid;
  v_role text;
  v_scoped boolean;
  v_grant_access text;
  v_grant_perms jsonb;
  v_base text[];
begin
  select business_id, owner_uid into v_business_id, v_owner_uid
  from books where id = p_book_id;

  if v_business_id is null then
    -- personal / individual book: access comes solely from book_grants (owner has all)
    select access, perms_override into v_grant_access, v_grant_perms
    from book_grants
    where book_id = p_book_id and user_uid = auth.uid() and deleted_at is null;

    if v_grant_access = 'DENY' then
      return array[]::text[];
    end if;
    if v_grant_perms is not null then
      return coalesce((select array_agg(value) from jsonb_array_elements_text(v_grant_perms) as value), array[]::text[]);
    end if;
    if v_owner_uid = auth.uid() then
      return all_permissions();
    end if;
    return array[]::text[];
  end if;

  select role, book_scoped into v_role, v_scoped
  from business_members
  where business_id = v_business_id and user_uid = auth.uid() and status = 'ACTIVE';

  if v_role is null then
    return array[]::text[];
  end if;

  select access, perms_override into v_grant_access, v_grant_perms
  from book_grants
  where book_id = p_book_id and user_uid = auth.uid() and deleted_at is null;

  if v_grant_access = 'DENY' then
    return array[]::text[];
  end if;

  -- "only these books": any scoped member (not just ADMIN — a VIEWER invited to a single book
  -- via share_book is scoped too) needs an explicit ALLOW grant per book, or they'd see every
  -- other book in the business through their role defaults instead.
  if v_scoped and coalesce(v_grant_access, '') != 'ALLOW' then
    return array[]::text[];
  end if;

  v_base := case v_role
    when 'OWNER' then all_permissions()
    when 'ADMIN' then array['BOOK_VIEW', 'TX_ADD', 'TX_EDIT', 'TX_DELETE', 'BOOK_ADD', 'BOOK_EDIT', 'BOOK_DELETE', 'MEMBER_MANAGE']
    else array['BOOK_VIEW']
  end;

  if v_grant_perms is not null then
    return coalesce((select array_agg(value) from jsonb_array_elements_text(v_grant_perms) as value), array[]::text[]);
  end if;
  return v_base;
end;
$$;

grant execute on function public.all_permissions() to authenticated;
grant execute on function public.business_role(uuid) to authenticated;
grant execute on function public.effective_perms(uuid) to authenticated;

-- ============================================================================
-- 3. RLS policies — replace P4 owner-only with full capability model
-- ============================================================================

drop policy if exists businesses_owner_all on public.businesses;
drop policy if exists books_owner_all on public.books;
drop policy if exists transactions_owner_all on public.transactions;

-- businesses: readable by any active member; insert always allowed (creator becomes OWNER via
-- trigger below); update/delete(soft) fine-gated by the enforce trigger (OWNER only either way,
-- since ADMIN has neither BUSINESS_EDIT nor BUSINESS_DELETE — no OLD/NEW split needed here).
create policy sel_biz on public.businesses for select using (business_role(id) is not null);
create policy ins_biz on public.businesses for insert with check (owner_uid = auth.uid());
create policy upd_biz on public.businesses for update
  using (business_role(id) is not null)
  with check (business_role(id) is not null);

-- business_members: readable by co-members; no insert/update/delete policy for clients at all
-- (RPC-only, via SECURITY DEFINER functions that bypass RLS as the function owner).
create policy sel_members on public.business_members for select using (business_role(business_id) is not null);

-- book_grants: readable by anyone who can view the book.
create policy sel_grants on public.book_grants for select using ('BOOK_VIEW' = any(effective_perms(book_id)));

-- audit_log: readable by business co-members or book viewers; no client insert policy (RPCs write it).
create policy sel_audit on public.audit_log for select using (
  (business_id is not null and business_role(business_id) is not null)
  or (book_id is not null and 'BOOK_VIEW' = any(effective_perms(book_id)))
);

-- books: BOOK_VIEW gates select; insert requires business OWNER/ADMIN (or personal-book self);
-- update is coarse-gated by BOOK_VIEW here, fine-split into BOOK_EDIT/BOOK_DELETE by the
-- enforce trigger below (they can diverge via perms_override, unlike business edit/delete).
create policy sel_book on public.books for select using ('BOOK_VIEW' = any(effective_perms(id)));
create policy ins_book on public.books for insert with check (
  (business_id is null and owner_uid = auth.uid())
  or (business_id is not null and business_role(business_id) in ('OWNER', 'ADMIN'))
);
create policy upd_book on public.books for update
  using ('BOOK_VIEW' = any(effective_perms(id)))
  with check ('BOOK_VIEW' = any(effective_perms(id)));

-- transactions: TX_ADD gates insert; TX_EDIT/TX_DELETE fine split happens in the enforce trigger
-- (soft-delete is just an UPDATE setting deleted_at — constitution forbids hard DELETE, so no
-- DELETE privilege/policy is granted here, unlike the spec §8.6 draft sample).
create policy sel_tx on public.transactions for select using ('BOOK_VIEW' = any(effective_perms(book_id)));
create policy ins_tx on public.transactions for insert with check ('TX_ADD' = any(effective_perms(book_id)));
create policy upd_tx on public.transactions for update
  using ('BOOK_VIEW' = any(effective_perms(book_id)))
  with check ('BOOK_VIEW' = any(effective_perms(book_id)));

-- ============================================================================
-- 4. Triggers
-- ============================================================================

-- Creating a business makes its creator an ACTIVE OWNER member immediately (so business_role()
-- resolves for them on the very next query, including the row they just inserted).
create or replace function public.handle_new_business()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.business_members (business_id, user_uid, role, status, invited_by_uid)
  values (new.id, new.owner_uid, 'OWNER', 'ACTIVE', new.owner_uid);
  return new;
end;
$$;
create trigger businesses_owner_membership after insert on public.businesses
  for each row execute procedure public.handle_new_business();

-- A business always keeps >=1 ACTIVE OWNER (spec §8.7) — reject any update that would demote or
-- deactivate the last one. Covers both update_member_role (role change) and revoke_member
-- (status change) since both are plain UPDATEs on this table.
create or replace function public.protect_last_owner()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_remaining_owners int;
begin
  if old.role = 'OWNER' and old.status = 'ACTIVE'
     and (new.role != 'OWNER' or new.status != 'ACTIVE') then
    select count(*) into v_remaining_owners
    from business_members
    where business_id = old.business_id and role = 'OWNER' and status = 'ACTIVE' and id != old.id;
    if v_remaining_owners = 0 then
      raise exception 'LAST_OWNER: business must keep at least one active owner';
    end if;
  end if;
  return new;
end;
$$;
create trigger business_members_protect_last_owner before update on public.business_members
  for each row execute procedure public.protect_last_owner();

create trigger business_members_touch_envelope before update on public.business_members
  for each row execute procedure public.touch_sync_envelope();
create trigger book_grants_touch_envelope before update on public.book_grants
  for each row execute procedure public.touch_sync_envelope();

-- BUSINESS_EDIT and BUSINESS_DELETE are both OWNER-only with no per-book override (unlike book
-- and tx caps), so no OLD/NEW branching is needed — either kind of update needs OWNER.
create or replace function public.enforce_business_perms()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if business_role(new.id) != 'OWNER' then
    raise exception 'FORBIDDEN: OWNER required' using errcode = '42501';
  end if;
  return new;
end;
$$;
create trigger businesses_enforce_perms before update on public.businesses
  for each row execute procedure public.enforce_business_perms();

-- BOOK_EDIT (rename/move) vs BOOK_DELETE (soft-delete/restore) can diverge via perms_override,
-- so this does need to read which column changed.
create or replace function public.enforce_book_perms()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if old.deleted_at is distinct from new.deleted_at then
    if not ('BOOK_DELETE' = any(effective_perms(new.id))) then
      raise exception 'FORBIDDEN: BOOK_DELETE required' using errcode = '42501';
    end if;
  else
    if not ('BOOK_EDIT' = any(effective_perms(new.id))) then
      raise exception 'FORBIDDEN: BOOK_EDIT required' using errcode = '42501';
    end if;
  end if;
  return new;
end;
$$;
create trigger books_enforce_perms before update on public.books
  for each row execute procedure public.enforce_book_perms();

create or replace function public.enforce_tx_perms()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if old.deleted_at is distinct from new.deleted_at then
    if not ('TX_DELETE' = any(effective_perms(new.book_id))) then
      raise exception 'FORBIDDEN: TX_DELETE required' using errcode = '42501';
    end if;
  else
    if not ('TX_EDIT' = any(effective_perms(new.book_id))) then
      raise exception 'FORBIDDEN: TX_EDIT required' using errcode = '42501';
    end if;
  end if;
  return new;
end;
$$;
create trigger transactions_enforce_perms before update on public.transactions
  for each row execute procedure public.enforce_tx_perms();

-- ============================================================================
-- 5. RPCs (spec §8.4) — the only write path for business_members / book_grants
-- ============================================================================

-- Exact-match only, no enumeration (spec §8.1). Assumes the caller already normalized phone to
-- E.164 — this function only lowercases/trims email, it doesn't parse phone country codes.
create or replace function public.lookup_user(p_email_or_phone text)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid;
begin
  select id into v_uid from users
  where lower(email) = lower(trim(p_email_or_phone)) or phone = p_email_or_phone;
  return v_uid;
end;
$$;

create or replace function public.invite_to_business(
  p_business_id uuid,
  p_email_or_phone text,
  p_role text,
  p_book_scope uuid[] default null,
  p_per_book_perms jsonb default null
)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_caller_role text;
  v_target_uid uuid;
  v_book_id uuid;
begin
  v_caller_role := business_role(p_business_id);
  if v_caller_role is null or v_caller_role not in ('OWNER', 'ADMIN') then
    raise exception 'FORBIDDEN: MEMBER_MANAGE required' using errcode = '42501';
  end if;
  if p_role not in ('OWNER', 'ADMIN', 'VIEWER') then
    raise exception 'INVALID_ROLE';
  end if;

  v_target_uid := lookup_user(p_email_or_phone);
  if v_target_uid is null then
    raise exception 'USER_NOT_REGISTERED';
  end if;

  insert into business_members (business_id, user_uid, role, status, invited_by_uid, book_scoped)
  values (p_business_id, v_target_uid, p_role, 'ACTIVE', auth.uid(), p_book_scope is not null)
  on conflict (business_id, user_uid)
  do update set role = excluded.role, status = 'ACTIVE', book_scoped = excluded.book_scoped;

  if p_book_scope is not null then
    foreach v_book_id in array p_book_scope loop
      insert into book_grants (book_id, user_uid, access, perms_override, granted_by_uid, deleted_at)
      values (v_book_id, v_target_uid, 'ALLOW', p_per_book_perms, auth.uid(), null)
      on conflict (book_id, user_uid)
      do update set access = 'ALLOW', perms_override = excluded.perms_override, deleted_at = null;
    end loop;
  end if;

  insert into audit_log (business_id, actor_uid, action, entity_type, entity_id)
  values (p_business_id, auth.uid(), 'INVITED:' || p_role, 'BUSINESS_MEMBER', v_target_uid);

  return v_target_uid;
end;
$$;

create or replace function public.share_book(
  p_book_id uuid,
  p_email_or_phone text,
  p_perms text[] default null
)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_book record;
  v_target_uid uuid;
begin
  select * into v_book from books where id = p_book_id;
  if v_book is null then
    raise exception 'BOOK_NOT_FOUND';
  end if;

  if v_book.business_id is null then
    if v_book.owner_uid != auth.uid() then
      raise exception 'FORBIDDEN' using errcode = '42501';
    end if;
  else
    if not ('MEMBER_MANAGE' = any(effective_perms(p_book_id))) then
      raise exception 'FORBIDDEN: MEMBER_MANAGE required' using errcode = '42501';
    end if;
  end if;

  v_target_uid := lookup_user(p_email_or_phone);
  if v_target_uid is null then
    raise exception 'USER_NOT_REGISTERED';
  end if;

  insert into book_grants (book_id, user_uid, access, perms_override, granted_by_uid, deleted_at)
  values (p_book_id, v_target_uid, 'ALLOW',
          case when p_perms is not null then to_jsonb(p_perms) else null end,
          auth.uid(), null)
  on conflict (book_id, user_uid)
  do update set access = 'ALLOW', perms_override = excluded.perms_override, deleted_at = null;

  insert into audit_log (business_id, book_id, actor_uid, action, entity_type, entity_id)
  values (v_book.business_id, p_book_id, auth.uid(), 'SHARED', 'BOOK_GRANT', v_target_uid);

  return v_target_uid;
end;
$$;

create or replace function public.update_member_role(p_business_id uuid, p_target_uid uuid, p_role text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if business_role(p_business_id) != 'OWNER' then
    raise exception 'FORBIDDEN: OWNER required' using errcode = '42501';
  end if;
  if p_role not in ('OWNER', 'ADMIN', 'VIEWER') then
    raise exception 'INVALID_ROLE';
  end if;

  update business_members set role = p_role
  where business_id = p_business_id and user_uid = p_target_uid;

  insert into audit_log (business_id, actor_uid, action, entity_type, entity_id)
  values (p_business_id, auth.uid(), 'ROLE_CHANGED:' || p_role, 'BUSINESS_MEMBER', p_target_uid);
end;
$$;

create or replace function public.set_book_grant(
  p_book_id uuid,
  p_target_uid uuid,
  p_access text,
  p_perms_override text[] default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_book record;
begin
  select * into v_book from books where id = p_book_id;
  if v_book is null then
    raise exception 'BOOK_NOT_FOUND';
  end if;
  if p_access not in ('ALLOW', 'DENY') then
    raise exception 'INVALID_ACCESS';
  end if;

  if v_book.business_id is null then
    if v_book.owner_uid != auth.uid() then
      raise exception 'FORBIDDEN' using errcode = '42501';
    end if;
  else
    if not ('MEMBER_MANAGE' = any(effective_perms(p_book_id))) then
      raise exception 'FORBIDDEN: MEMBER_MANAGE required' using errcode = '42501';
    end if;
  end if;

  insert into book_grants (book_id, user_uid, access, perms_override, granted_by_uid, deleted_at)
  values (p_book_id, p_target_uid, p_access,
          case when p_perms_override is not null then to_jsonb(p_perms_override) else null end,
          auth.uid(), null)
  on conflict (book_id, user_uid)
  do update set access = excluded.access, perms_override = excluded.perms_override, deleted_at = null;

  insert into audit_log (business_id, book_id, actor_uid, action, entity_type, entity_id)
  values (v_book.business_id, p_book_id, auth.uid(), 'GRANT_SET:' || p_access, 'BOOK_GRANT', p_target_uid);
end;
$$;

create or replace function public.revoke_member(p_business_id uuid, p_target_uid uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if business_role(p_business_id) not in ('OWNER', 'ADMIN') then
    raise exception 'FORBIDDEN: MEMBER_MANAGE required' using errcode = '42501';
  end if;

  update business_members set status = 'REVOKED'
  where business_id = p_business_id and user_uid = p_target_uid;

  insert into audit_log (business_id, actor_uid, action, entity_type, entity_id)
  values (p_business_id, auth.uid(), 'REVOKED', 'BUSINESS_MEMBER', p_target_uid);
end;
$$;

create or replace function public.revoke_book_grant(p_book_id uuid, p_target_uid uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_book record;
begin
  select * into v_book from books where id = p_book_id;
  if v_book is null then
    raise exception 'BOOK_NOT_FOUND';
  end if;

  if v_book.business_id is null then
    if v_book.owner_uid != auth.uid() then
      raise exception 'FORBIDDEN' using errcode = '42501';
    end if;
  else
    if not ('MEMBER_MANAGE' = any(effective_perms(p_book_id))) then
      raise exception 'FORBIDDEN: MEMBER_MANAGE required' using errcode = '42501';
    end if;
  end if;

  -- tombstone, not hard delete — a hard DELETE would be invisible to delta pull (spec §6.4),
  -- so the revoke would never propagate to the granted device.
  update book_grants set deleted_at = (extract(epoch from now()) * 1000)::bigint
  where book_id = p_book_id and user_uid = p_target_uid;

  insert into audit_log (business_id, book_id, actor_uid, action, entity_type, entity_id)
  values (v_book.business_id, p_book_id, auth.uid(), 'GRANT_REVOKED', 'BOOK_GRANT', p_target_uid);
end;
$$;

revoke execute on function public.lookup_user(text) from public;
revoke execute on function public.invite_to_business(uuid, text, text, uuid[], jsonb) from public;
revoke execute on function public.share_book(uuid, text, text[]) from public;
revoke execute on function public.update_member_role(uuid, uuid, text) from public;
revoke execute on function public.set_book_grant(uuid, uuid, text, text[]) from public;
revoke execute on function public.revoke_member(uuid, uuid) from public;
revoke execute on function public.revoke_book_grant(uuid, uuid) from public;

grant execute on function public.lookup_user(text) to authenticated;
grant execute on function public.invite_to_business(uuid, text, text, uuid[], jsonb) to authenticated;
grant execute on function public.share_book(uuid, text, text[]) to authenticated;
grant execute on function public.update_member_role(uuid, uuid, text) to authenticated;
grant execute on function public.set_book_grant(uuid, uuid, text, text[]) to authenticated;
grant execute on function public.revoke_member(uuid, uuid) to authenticated;
grant execute on function public.revoke_book_grant(uuid, uuid) to authenticated;

-- ============================================================================
-- 6. Lock down internal-only functions — Postgres grants EXECUTE to PUBLIC by default on
-- creation, which makes trigger-only functions reachable via PostgREST RPC
-- (/rest/v1/rpc/<name>) unless explicitly revoked (Supabase advisor: anon/authenticated
-- security_definer_function_executable warnings).
-- ============================================================================

revoke execute on function public.touch_sync_envelope() from public, anon, authenticated;
revoke execute on function public.handle_new_user() from public, anon, authenticated;
revoke execute on function public.handle_new_business() from public, anon, authenticated;
revoke execute on function public.protect_last_owner() from public, anon, authenticated;
revoke execute on function public.enforce_business_perms() from public, anon, authenticated;
revoke execute on function public.enforce_book_perms() from public, anon, authenticated;
revoke execute on function public.enforce_tx_perms() from public, anon, authenticated;

-- business_role / effective_perms / all_permissions stay authenticated-only (not anon) — they're
-- read-only helpers meant for a logged-in client's own permission-cache queries, not public probing.
revoke execute on function public.business_role(uuid) from public, anon;
revoke execute on function public.effective_perms(uuid) from public, anon;
revoke execute on function public.all_permissions() from public, anon;
