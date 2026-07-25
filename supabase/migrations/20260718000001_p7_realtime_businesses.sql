-- Bug fix (on-device): a shared business rename never reached the shared member's device until the
-- 15-min periodic pull worker ran. P7 added only books/transactions to the realtime publication
-- (20260712000003), so `businesses` changes had no live channel at all — pull-to-refresh was the
-- only manual recourse and felt broken. Add businesses so renames/deletes propagate instantly,
-- same as books/transactions. RLS (sel_biz) still scopes what each subscriber receives.
alter publication supabase_realtime add table public.businesses;
