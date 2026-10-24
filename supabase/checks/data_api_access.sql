-- Read-only audit for LedgerX after migrations 001, 002 and 003.
-- Run in the hosted project's SQL Editor as an administrator.
-- Every row should report OK. This checks grants and RLS enablement, not
-- policy correctness, Data API settings, or authenticated HTTP behavior.
with expected_tables(name) as (
 values ('profiles'), ('movements'), ('categories'), ('monthly_summaries'),
 ('transactions'), ('deletions'), ('legacy_movements'), ('friendships'),
 ('loans'), ('loan_events')
), table_checks as (
 select 'table public.' || e.name as object,
  case
   when c.oid is null then 'MISSING: apply the required schema migration'
   when not c.relrowsecurity then 'REVIEW: row level security is disabled'
   when not has_schema_privilege('authenticated','public','USAGE') then 'MISSING: authenticated schema USAGE'
   when not has_table_privilege('authenticated',c.oid,'SELECT') then 'MISSING: authenticated SELECT'
   when e.name='profiles' and not has_table_privilege('authenticated',c.oid,'INSERT') then 'MISSING: authenticated INSERT'
   when has_table_privilege('anon',c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') then 'REVIEW: unexpected anonymous access'
   when has_table_privilege('authenticated',c.oid,'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') then 'REVIEW: unexpected direct write privileges'
   when e.name<>'profiles' and has_table_privilege('authenticated',c.oid,'INSERT') then 'REVIEW: financial writes must use ledger_command'
   else 'OK'
  end as result
 from expected_tables e
 left join pg_namespace n on n.nspname='public'
 left join pg_class c on c.relnamespace=n.oid and c.relname=e.name and c.relkind in ('r','p')
), expected_functions(signature,allowed) as (
 values ('public.ledger_command(uuid,text,text,text,jsonb)',true),
 ('public.ledger_snapshot()',true),
 ('private.ledger_command(uuid,text,text,text,jsonb)',true),
 ('private.ledger_snapshot()',true),
 ('public.sync_ledger(jsonb)',false),
 ('private.ledger_compact(uuid)',false),
 ('private.ledger_sweep()',false),
 ('private.account_balance(uuid,text)',false)
), function_checks as (
 select 'function ' || signature as object,
  case
   when to_regprocedure(signature) is null then 'MISSING: apply the required schema migration'
   when allowed and not has_schema_privilege('authenticated',split_part(signature,'.',1),'USAGE') then 'MISSING: authenticated schema USAGE'
   when has_function_privilege('anon',to_regprocedure(signature),'EXECUTE') then 'REVIEW: unexpected anonymous execution'
   when has_function_privilege('authenticated',to_regprocedure(signature),'EXECUTE')<>allowed then 'REVIEW: authenticated EXECUTE does not match the command API'
   else 'OK'
  end as result
 from expected_functions
)
select * from table_checks
union all
select * from function_checks
order by object;
