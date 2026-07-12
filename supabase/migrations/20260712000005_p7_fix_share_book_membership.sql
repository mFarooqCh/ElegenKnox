-- P7 fix: share_book() on a business-owned book inserted a book_grants row but never gave the
-- target a business_members row. effective_perms() for a business book hard-requires an ACTIVE
-- business_members row before it even looks at book_grants (see the p6_rbac.sql v_role null check)
-- -- so a book_grants-only invite was completely inert: the target could see neither the business
-- (sel_biz gates on business_role()) nor the book (effective_perms() returned empty regardless of
-- the grant). Mirrors what invite_to_business already does for its p_book_scope path: a minimal
-- VIEWER, book_scoped=true membership row, added only if the target isn't already a member (never
-- downgrade an existing ADMIN/OWNER, never flip book_scoped on an existing broader membership).

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
    on conflict (business_id, user_uid) do nothing;
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
