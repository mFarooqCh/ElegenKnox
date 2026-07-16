-- P8 fix: share_book's membership-row insert used `on conflict do nothing`, so re-sharing a book
-- with a previously-REVOKED business member left them REVOKED forever -- the book_grants row got
-- created/updated fine (RPC returned success, no error), but effective_perms() requires an ACTIVE
-- business_members row before it even looks at book_grants, so the recipient still saw nothing.
-- invite_to_business already reactivates correctly (`do update set status = 'ACTIVE'`); share_book
-- never got the same treatment. Real bug found on-device: re-sharing books with a revoked member
-- "did nothing" from the recipient's side despite the owner's share action reporting success.
--
-- Only status flips to ACTIVE — role/book_scoped are left untouched, so a revoked unscoped ADMIN
-- comes back as an unscoped ADMIN, not downgraded to a scoped VIEWER just because one more book
-- was shared with them.

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

  return v_target_uid;
end;
$$;
