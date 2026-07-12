-- P7: read-side RPCs for the members/sharing UI. business_members/book_grants rows carry only
-- user_uid — the users table isn't client-selectable for anyone but yourself (spec §8.1 no
-- enumeration), so the UI needs a SECURITY DEFINER function to resolve email/display_name
-- alongside each row, same pattern as lookup_user.

create or replace function public.list_business_members(p_business_id uuid)
returns table (
  user_uid uuid,
  email text,
  display_name text,
  role text,
  status text,
  book_scoped boolean,
  joined_at timestamptz
)
language plpgsql
security definer
set search_path = public
as $$
begin
  if business_role(p_business_id) is null then
    raise exception 'FORBIDDEN: not a member of this business' using errcode = '42501';
  end if;

  return query
  select m.user_uid, u.email, u.display_name, m.role, m.status, m.book_scoped, m.joined_at
  from business_members m
  join users u on u.id = m.user_uid
  where m.business_id = p_business_id
  order by m.joined_at asc;
end;
$$;

create or replace function public.list_book_grants(p_book_id uuid)
returns table (
  user_uid uuid,
  email text,
  display_name text,
  access text,
  perms_override jsonb
)
language plpgsql
security definer
set search_path = public
as $$
begin
  if not ('BOOK_VIEW' = any(effective_perms(p_book_id))) then
    raise exception 'FORBIDDEN: cannot view this book' using errcode = '42501';
  end if;

  return query
  select g.user_uid, u.email, u.display_name, g.access, g.perms_override
  from book_grants g
  join users u on u.id = g.user_uid
  where g.book_id = p_book_id and g.deleted_at is null
  order by u.email asc;
end;
$$;

revoke execute on function public.list_business_members(uuid) from public;
revoke execute on function public.list_book_grants(uuid) from public;
grant execute on function public.list_business_members(uuid) to authenticated;
grant execute on function public.list_book_grants(uuid) to authenticated;
