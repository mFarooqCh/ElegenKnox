-- P7 fix: effective_perms()'s book_scoped allow-list check only applied to role = 'ADMIN'. A
-- scoped VIEWER (the exact shape share_book's fix in migration 20260712000005 now creates —
-- VIEWER, book_scoped=true) fell through untouched and got BOOK_VIEW on every book in the
-- business via the VIEWER role-default branch, not just the one book actually shared. Real bug
-- found via on-device testing: shared a single book as viewer, recipient could see the whole
-- business's other books too. Generalized the scoping check to any role, matching
-- core/permission/Permission.kt's PermissionResolver.effective (client mirror, fixed alongside).

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
