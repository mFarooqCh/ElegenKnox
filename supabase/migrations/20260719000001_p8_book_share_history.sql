-- P8: make book share/revoke show up in the book's edit history (visible to every current viewer
-- of the book, any role). Before this, share_book/revoke_book_grant wrote only an audit_log row
-- with entity_type='BOOK_GRANT' (entity_id = the target user) — which RemotePull's history pull
-- deliberately ignores (it only surfaces BOOK/TRANSACTION rows as history). So sharing/revoking a
-- book left no trace in the book's own history that a shared user could see.
--
-- Fix: alongside the existing BOOK_GRANT audit row (kept as the RBAC audit trail), also insert a
-- BOOK-type history row (entity_id = book_id, book_id set, business_id left null like the client's
-- own history rows) so it flows through the same history pull + sel_audit BOOK_VIEW gate as every
-- other book/entry history event. New actions: 'SHARED', 'ACCESS_REVOKED' (mirrored in the client
-- HistoryAction enum — must stay in lockstep or HistoryEntity.toDomain()'s valueOf() throws).
--
-- share_book body reproduced from 20260714000003 (latest) so the membership reactivate + business
-- co-visibility fixes are preserved; only the trailing BOOK history insert is new.

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

  if v_book.business_id is not null then
    insert into business_members (business_id, user_uid, role, status, invited_by_uid, book_scoped)
    values (v_book.business_id, v_target_uid, 'VIEWER', 'ACTIVE', auth.uid(), true)
    on conflict (business_id, user_uid) do update set status = 'ACTIVE';
  end if;

  insert into book_grants (book_id, user_uid, access, perms_override, granted_by_uid, deleted_at)
  values (p_book_id, v_target_uid, 'ALLOW',
          case when p_perms is not null then to_jsonb(p_perms) else null end,
          auth.uid(), null)
  on conflict (book_id, user_uid)
  do update set access = 'ALLOW', perms_override = excluded.perms_override, deleted_at = null;

  insert into audit_log (business_id, book_id, actor_uid, action, entity_type, entity_id)
  values (v_book.business_id, p_book_id, auth.uid(), 'SHARED', 'BOOK_GRANT', v_target_uid);

  -- Book-level history row: shows the share in the book's edit history for anyone who can view it.
  insert into audit_log (book_id, actor_uid, action, entity_type, entity_id)
  values (p_book_id, auth.uid(), 'SHARED', 'BOOK', p_book_id);

  return v_target_uid;
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

  -- Book-level history row: shows the revoke in the book's history for the remaining viewers.
  insert into audit_log (book_id, actor_uid, action, entity_type, entity_id)
  values (p_book_id, auth.uid(), 'ACCESS_REVOKED', 'BOOK', p_book_id);
end;
$$;

-- invite_to_business also grants books (its p_book_scope loop) — the Members edit sheet's book
-- checkboxes route through here, not share_book. Log a BOOK 'SHARED' history row per granted book,
-- same as share_book. Body reproduced from 20260714000002 (latest) so the FK existence check is
-- preserved; only the per-book history insert is new.
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
      if not exists (select 1 from books where id = v_book_id and business_id = p_business_id) then
        raise exception 'BOOK_NOT_FOUND';
      end if;
      insert into book_grants (book_id, user_uid, access, perms_override, granted_by_uid, deleted_at)
      values (v_book_id, v_target_uid, 'ALLOW', p_per_book_perms, auth.uid(), null)
      on conflict (book_id, user_uid)
      do update set access = 'ALLOW', perms_override = excluded.perms_override, deleted_at = null;
      -- Book-level history row per granted book (Members edit sheet share path).
      insert into audit_log (book_id, actor_uid, action, entity_type, entity_id)
      values (v_book_id, auth.uid(), 'SHARED', 'BOOK', v_book_id);
    end loop;
  end if;

  insert into audit_log (business_id, actor_uid, action, entity_type, entity_id)
  values (p_business_id, auth.uid(), 'INVITED:' || p_role, 'BUSINESS_MEMBER', v_target_uid);

  return v_target_uid;
end;
$$;
