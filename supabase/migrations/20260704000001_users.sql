-- P3: user directory (spec §8.1).
-- Identity invariant: email != null OR phone != null. Both unique across users.
-- Clients never write this table directly (default-deny RLS); rows are created by
-- the auth signup trigger and changed only via SECURITY DEFINER RPCs (P8: update_contact).

create table public.users (
  id uuid primary key references auth.users (id) on delete cascade,
  display_name text,
  email text unique,
  phone text unique, -- E.164, unverified identifier (spec §8.1: SMS costs money)
  photo_url text,
  -- sync envelope (spec §7)
  version bigint not null default 1,
  updated_at timestamptz not null default now(),
  device_id text,
  deleted_at timestamptz,
  constraint users_contact_check check (email is not null or phone is not null)
);

alter table public.users enable row level security;

-- SQL privilege layer (RLS filters on top of it): logged-in clients may SELECT (policy narrows
-- to own row); no INSERT/UPDATE/DELETE privilege → writes impossible even if a policy slipped in.
grant select on public.users to authenticated;

-- Read own row only. Directory lookup for sharing arrives as lookup_user RPC (P6/P7),
-- which returns a minimal match instead of exposing the table.
create policy users_select_own on public.users
  for select using ((select auth.uid()) = id);

-- No insert/update/delete policies: default deny for clients.

-- Mirror auth signups into the directory. display_name/phone come from signup metadata.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.users (id, display_name, email, phone)
  values (
    new.id,
    nullif(new.raw_user_meta_data ->> 'display_name', ''),
    new.email,
    nullif(new.raw_user_meta_data ->> 'phone', '')
  );
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute procedure public.handle_new_user();

-- Server authoritative updated_at + version (LWW source of truth, spec §6.6).
create or replace function public.touch_sync_envelope()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  new.version = old.version + 1;
  return new;
end;
$$;

create trigger users_touch_envelope
  before update on public.users
  for each row execute procedure public.touch_sync_envelope();
