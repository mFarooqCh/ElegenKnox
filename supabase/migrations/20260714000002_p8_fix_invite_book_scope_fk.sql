-- P8 fix: invite_to_business's book_scope loop inserted into book_grants with no existence check
-- on the book id first -- a book_scope entry that doesn't exist server-side yet (client picked a
-- just-created book before its own CREATE outbox row pushed) crashed with a raw Postgres FK
-- violation (23503) instead of a friendly, client-mapped error. share_book/set_book_grant/
-- revoke_book_grant all already guard this with a BOOK_NOT_FOUND check; invite_to_business's
-- book_scope path never got the same treatment. Real bug found on-device: inviting to a business
-- with one of two books selected threw "Action failed - please try again" (unmapped error).

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
    end loop;
  end if;

  insert into audit_log (business_id, actor_uid, action, entity_type, entity_id)
  values (p_business_id, auth.uid(), 'INVITED:' || p_role, 'BUSINESS_MEMBER', v_target_uid);

  return v_target_uid;
end;
$$;
