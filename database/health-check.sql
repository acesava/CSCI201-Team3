-- Run once in the Team 3 project (klxskdrnvslhqrwmuiuu) SQL Editor.
-- A repeated application fails instead of replacing an existing function.
begin;

create function public.check_database_health()
returns integer
language sql
stable
security invoker
set search_path = ''
as $$ select 1; $$;

revoke execute on function public.check_database_health()
    from public, anon, authenticated;
grant execute on function public.check_database_health() to anon;

commit;
