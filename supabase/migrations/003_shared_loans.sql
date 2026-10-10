-- Legacy name-based loans remain private; never manufacture authenticated counterparties.
create table public.legacy_movements (like public.movements including all);
alter table public.legacy_movements add foreign key(owner_id) references auth.users(id) on delete cascade;
alter table public.legacy_movements add check(kind in ('LEND','BORROW','RECEIVE_REPAYMENT','PAY_REPAYMENT'));
insert into public.legacy_movements select * from public.movements where kind in ('LEND','BORROW','RECEIVE_REPAYMENT','PAY_REPAYMENT');
alter table public.legacy_movements enable row level security;
revoke all on public.legacy_movements from anon,authenticated;
grant select on public.legacy_movements to authenticated;
create policy owner_read on public.legacy_movements for select to authenticated using ((select auth.uid())=owner_id);

create table public.friendships (
 id uuid primary key, user_id uuid not null references auth.users(id), friend_id uuid not null references auth.users(id),
 status text not null check(status in ('PENDING','ACCEPTED','BLOCKED','REJECTED')),created_at bigint not null,
 check(user_id<>friend_id)
);
create unique index friendships_pair on public.friendships(least(user_id,friend_id),greatest(user_id,friend_id));
create index friendships_user on public.friendships(user_id);
create index friendships_friend on public.friendships(friend_id);
create table public.loans (
 id uuid primary key, lender_id uuid not null references auth.users(id), borrower_id uuid not null references auth.users(id),
 principal_minor bigint not null check(principal_minor between 1 and 100000000000),currency text not null default 'BDT' check(currency='BDT'),
 description text not null check(length(description)<=300), status text not null check(status in ('UNDER_REVIEW','ACTIVE','PARTIALLY_REPAID','REPAID','REJECTED','CANCELLED','SUPERSEDED')),
 proposed_by uuid not null references auth.users(id),
 lender_account text not null check(lender_account in ('CASH','BKASH','BANK')),borrower_account text not null check(borrower_account in ('CASH','BKASH','BANK')),
 repaid_minor bigint not null default 0 check(repaid_minor>=0 and repaid_minor<=principal_minor),
 created_at bigint not null, accepted_at bigint,fully_repaid_at bigint,
 blockchain_hash text,blockchain_tx_hash text,blockchain_status text not null default 'NOT_REQUIRED',
 sync_status text not null default 'SYNCED', version integer not null default 1, supersedes uuid references public.loans(id),
 check(lender_id<>borrower_id and proposed_by in(lender_id,borrower_id))
);
create index loans_lender on public.loans(lender_id);
create index loans_borrower on public.loans(borrower_id);
create index loans_proposer on public.loans(proposed_by);
create index loans_supersedes on public.loans(supersedes);
create table public.loan_events (
 id uuid primary key,loan_id uuid not null references public.loans(id),
 type text not null check(type in ('REPAYMENT','CORRECTION','APPROVAL','REJECTION')),
 amount_minor bigint check(amount_minor between 1 and 100000000000), proposed_by uuid not null references auth.users(id),
 approved_by uuid references auth.users(id),status text not null check(status in ('PENDING','CONFIRMED','REJECTED')),
 created_at bigint not null,sync_status text not null default 'SYNCED'
);
create index loan_events_loan on public.loan_events(loan_id);
create index loan_events_proposer on public.loan_events(proposed_by);
create index loan_events_approver on public.loan_events(approved_by);
alter table public.friendships enable row level security;
alter table public.loans enable row level security;
alter table public.loan_events enable row level security;
revoke all on public.friendships,public.loans,public.loan_events from anon,authenticated;
grant select on public.friendships,public.loans,public.loan_events to authenticated;
create policy participants on public.friendships for select to authenticated using ((select auth.uid()) in(user_id,friend_id));
create policy participants on public.loans for select to authenticated using ((select auth.uid()) in(lender_id,borrower_id));
create policy participants on public.loan_events for select to authenticated using (exists(select 1 from public.loans l where l.id=loan_id and (select auth.uid()) in(l.lender_id,l.borrower_id)));
-- Authenticated username discovery exposes public profile fields, never auth.users/email.
create policy profile_discovery on public.profiles for select to authenticated using(true);

create function private.account_balance(who uuid, account_name text) returns bigint language sql stable set search_path='' as $$
 select coalesce((select sum(income_minor-expense_minor) from public.monthly_summaries where user_id=who and account=account_name),0)
 +coalesce((select sum(case when type='INCOME' then amount_minor else -amount_minor end) from public.transactions where user_id=who and account=account_name and summary_status='ACTIVE'),0)
 +coalesce((select sum(amount_minor) from public.transactions where user_id=who and destination=account_name and type='TRANSFER'),0)
 +coalesce((select sum((principal_minor-repaid_minor)*case when lender_id=who then -1 else 1 end) from public.loans where status in('ACTIVE','PARTIALLY_REPAID','REPAID') and ((lender_id=who and lender_account=account_name) or (borrower_id=who and borrower_account=account_name))),0)
 +coalesce((select sum(case when kind in('BORROW','RECEIVE_REPAYMENT') then amount else -amount end) from public.legacy_movements where owner_id=who and account=account_name and kind in('LEND','BORROW','RECEIVE_REPAYMENT','PAY_REPAYMENT')),0);
$$;
revoke all on function private.account_balance(uuid,text) from public,anon,authenticated;

create function private.ledger_command(command_id uuid,entity_type text,entity_id text,action text,payload jsonb)
returns void language plpgsql security definer set search_path='' as $$
declare who uuid:=auth.uid(); stamp bigint:=(extract(epoch from now())*1000)::bigint;
 t public.transactions; c public.categories; f public.friendships; l public.loans; e public.loan_events; other uuid; new_id uuid; eid uuid; r jsonb; legacy public.legacy_movements; debt bigint;
begin
 if who is null then raise exception 'Authentication required'; end if;
 if entity_type<>'CATEGORY' then eid:=entity_id::uuid; end if;
 -- Global transaction lock prevents cross-party account races and lock-order deadlocks.
 perform pg_advisory_xact_lock(68192734);
 if exists(select 1 from private.ledger_commands where id=command_id and user_id=who) then return; end if;
 if entity_type='LEGACY_IMPORT' then
  if jsonb_typeof(payload)<>'array' then raise exception 'Invalid migration records'; end if;
  for r in select value from jsonb_array_elements(payload) with ordinality order by (value->>'occurred_at')::timestamptz,ordinality loop
   if r->>'kind' in ('INCOME','EXPENSE','TRANSFER') then
    perform private.ledger_command(gen_random_uuid(),'TRANSACTION',r->>'id','INSERT',jsonb_build_object(
     'id',r->>'id','user_id',who,'type',r->>'kind','amount_minor',r->'amount','account',r->>'account','destination',r->'destination',
     'category_id',case when r->>'kind'='TRANSFER' then null else who::text||':'||case r->>'category' when 'EAT' then 'eating' when 'PAYMENT' then 'payment' else 'other' end end,
     'description',r->>'note','occurred_at',(extract(epoch from (r->>'occurred_at')::timestamptz)*1000)::bigint));
   else
    legacy:=jsonb_populate_record(null::public.legacy_movements,r);
    if exists(select 1 from public.legacy_movements where id=legacy.id) then
     if not exists(select 1 from public.legacy_movements m where m.id=legacy.id and m.owner_id=who and m.kind=legacy.kind and m.amount=legacy.amount and m.account=legacy.account and m.person=legacy.person and m.note=legacy.note and m.occurred_at=legacy.occurred_at) then raise exception 'Legacy record collision'; end if;
    else
     if legacy.kind in('LEND','PAY_REPAYMENT') and private.account_balance(who,legacy.account)<legacy.amount then raise exception 'Insufficient legacy account balance'; end if;
     select coalesce(sum(case when kind in('LEND','PAY_REPAYMENT') then amount else -amount end),0) into debt from public.legacy_movements where owner_id=who and person=legacy.person;
     if (legacy.kind='RECEIVE_REPAYMENT' and (debt<=0 or legacy.amount>debt)) or (legacy.kind='PAY_REPAYMENT' and (debt>=0 or legacy.amount> -debt)) then raise exception 'Legacy repayment exceeds balance'; end if;
     insert into public.legacy_movements(id,owner_id,kind,amount,account,destination,category,person,note,occurred_at) values(legacy.id,who,legacy.kind,legacy.amount,legacy.account,legacy.destination,legacy.category,legacy.person,legacy.note,legacy.occurred_at);
    end if;
   end if;
  end loop;
 elsif entity_type='CATEGORY' then
  c:=jsonb_populate_record(null::public.categories,payload);
  if c.user_id<>who then raise exception 'Invalid owner'; end if;
  insert into public.categories values(c.id,who,c.name,c.icon,c.is_default,c.sort_order) on conflict(id) do nothing;
  if not exists(select 1 from public.categories where id=c.id and user_id=who) then raise exception 'Category collision'; end if;
 elsif entity_type='TRANSACTION' then
  t:=jsonb_populate_record(null::public.transactions,payload);
  if t.user_id<>who or t.id<>eid then raise exception 'Invalid owner'; end if;
  if exists(select 1 from public.deletions where id=eid and user_id=who) then
   null; -- Expired originals must never reappear, even after a long offline period.
  elsif exists(select 1 from public.transactions where id=eid) then
   if not exists(select 1 from public.transactions r where r.id=eid and r.user_id=who and r.type=t.type and r.amount_minor=t.amount_minor and r.account=t.account and r.destination is not distinct from t.destination and r.category_id is not distinct from t.category_id and r.description=t.description and r.occurred_at=t.occurred_at) then raise exception 'Transaction collision'; end if;
  else
   if t.type<>'INCOME' and private.account_balance(who,t.account)<t.amount_minor then raise exception 'Insufficient account balance'; end if;
   insert into public.transactions values(t.id,who,t.type,t.amount_minor,t.account,t.destination,t.category_id,t.description,t.occurred_at,'SYNCED','ACTIVE',null,null,null,1,stamp,stamp);
  end if;
 elsif entity_type='FRIENDSHIP' then
  if action='PROPOSE' then
   f:=jsonb_populate_record(null::public.friendships,payload);
   if f.user_id<>who or f.id<>eid then raise exception 'Invalid sender'; end if;
   insert into public.friendships values(eid,who,f.friend_id,'PENDING',stamp);
  else
   select * into strict f from public.friendships where id=eid for update;
   if f.friend_id<>who or f.status<>'PENDING' or action not in('ACCEPT','REJECT') then raise exception 'Invalid friendship decision'; end if;
   update public.friendships set status=case when action='ACCEPT' then 'ACCEPTED' else 'REJECTED' end where id=eid;
  end if;
 elsif entity_type='LOAN' then
  if action='PROPOSE' then
   l:=jsonb_populate_record(null::public.loans,payload);
   if l.id<>eid or l.proposed_by<>who or who not in(l.lender_id,l.borrower_id) then raise exception 'Invalid proposer'; end if;
   if not exists(select 1 from public.friendships where status='ACCEPTED' and least(user_id,friend_id)=least(l.lender_id,l.borrower_id) and greatest(user_id,friend_id)=greatest(l.lender_id,l.borrower_id)) then raise exception 'Accepted friendship required'; end if;
   insert into public.loans values(eid,l.lender_id,l.borrower_id,l.principal_minor,'BDT',l.description,'UNDER_REVIEW',who,l.lender_account,l.borrower_account,0,stamp,null,null,null,null,'NOT_REQUIRED','SYNCED',1,null);
  else
   select * into strict l from public.loans where id=eid for update;
   if who not in(l.lender_id,l.borrower_id) or who=l.proposed_by or l.status<>'UNDER_REVIEW' or action not in('ACCEPT','REJECT') then raise exception 'Only counterparty can decide pending loan'; end if;
   if action='ACCEPT' then
    if who=l.lender_id then l.lender_account:=payload->>'account'; else l.borrower_account:=payload->>'account'; end if;
    if private.account_balance(l.lender_id,l.lender_account)<l.principal_minor then raise exception 'Insufficient lender balance'; end if;
   end if;
   update public.loans set status=case when action='ACCEPT' then 'ACTIVE' else 'REJECTED' end,
    accepted_at=case when action='ACCEPT' then stamp end,lender_account=l.lender_account,borrower_account=l.borrower_account,version=version+1 where id=eid;
  end if;
 elsif entity_type='LOAN_EVENT' then
  if action='PROPOSE' then
   e:=jsonb_populate_record(null::public.loan_events,payload);
   select * into strict l from public.loans where id=e.loan_id for update;
   if e.id<>eid or e.proposed_by<>who or who not in(l.lender_id,l.borrower_id) or l.status not in('ACTIVE','PARTIALLY_REPAID') or e.type not in('REPAYMENT','CORRECTION') then raise exception 'Invalid loan event'; end if;
   if e.amount_minor is null or (e.type='REPAYMENT' and e.amount_minor>l.principal_minor-l.repaid_minor) or (e.type='CORRECTION' and e.amount_minor<l.repaid_minor) then raise exception 'Invalid amount'; end if;
   insert into public.loan_events values(e.id,e.loan_id,e.type,e.amount_minor,who,null,'PENDING',stamp,'SYNCED');
  else
   select * into strict e from public.loan_events where id=eid for update;
   select * into strict l from public.loans where id=e.loan_id for update;
   if who not in(l.lender_id,l.borrower_id) or who=e.proposed_by or e.status<>'PENDING' or l.status not in('ACTIVE','PARTIALLY_REPAID') or action not in('ACCEPT','REJECT') then raise exception 'Invalid event decision'; end if;
   if action='ACCEPT' and e.type='REPAYMENT' then
    if e.amount_minor>l.principal_minor-l.repaid_minor then raise exception 'Repayment exceeds balance'; end if;
    if private.account_balance(l.borrower_id,l.borrower_account)<e.amount_minor then raise exception 'Insufficient borrower balance'; end if;
    update public.loans set repaid_minor=repaid_minor+e.amount_minor,status=case when repaid_minor+e.amount_minor=principal_minor then 'REPAID' else 'PARTIALLY_REPAID' end,
     fully_repaid_at=case when repaid_minor+e.amount_minor=principal_minor then stamp end,version=version+1 where id=l.id;
   elsif action='ACCEPT' and e.type='CORRECTION' then
    if e.amount_minor<l.repaid_minor then raise exception 'Correction below repayments'; end if;
    if e.amount_minor>l.principal_minor and private.account_balance(l.lender_id,l.lender_account)<e.amount_minor-l.principal_minor then raise exception 'Insufficient lender balance'; end if;
    if e.amount_minor<l.principal_minor and private.account_balance(l.borrower_id,l.borrower_account)<l.principal_minor-e.amount_minor then raise exception 'Insufficient borrower balance'; end if;
    new_id:=eid; -- Stable replacement ID makes retries deterministic.
    update public.loans set status='SUPERSEDED',version=version+1 where id=l.id;
    insert into public.loans values(new_id,l.lender_id,l.borrower_id,e.amount_minor,'BDT',l.description,
     case when e.amount_minor=l.repaid_minor then 'REPAID' when l.repaid_minor>0 then 'PARTIALLY_REPAID' else 'ACTIVE' end,
     e.proposed_by,l.lender_account,l.borrower_account,l.repaid_minor,stamp,stamp,case when e.amount_minor=l.repaid_minor then stamp end,null,null,'NOT_REQUIRED','SYNCED',l.version+1,l.id);
    update public.loan_events set status='REJECTED' where loan_id=l.id and status='PENDING' and id<>eid;
   end if;
   update public.loan_events set status=case when action='ACCEPT' then 'CONFIRMED' else 'REJECTED' end,approved_by=who where id=eid;
  end if;
 else raise exception 'Unknown entity';
 end if;
 insert into private.ledger_commands(id,user_id) values(command_id,who);
end $$;
revoke all on function private.ledger_command(uuid,text,text,text,jsonb) from public,anon;
grant usage on schema private to authenticated;
grant execute on function private.ledger_command(uuid,text,text,text,jsonb) to authenticated;
create function public.ledger_command(command_id uuid,entity_type text,entity_id text,action text,payload jsonb)
returns void language sql security invoker set search_path='' as $$ select private.ledger_command(command_id,entity_type,entity_id,action,payload) $$;
revoke all on function public.ledger_command(uuid,text,text,text,jsonb) from public,anon;
grant execute on function public.ledger_command(uuid,text,text,text,jsonb) to authenticated;

create function private.ledger_snapshot() returns jsonb language plpgsql security definer set search_path='' as $$
declare who uuid:=auth.uid(); result jsonb;
begin
 if who is null then raise exception 'Authentication required'; end if;
 perform pg_advisory_xact_lock(68192734);
 perform private.ledger_compact(who);
 select jsonb_build_object(
 'legacy_movements',coalesce((select jsonb_agg(t) from public.legacy_movements t where owner_id=who),'[]'),
 'transactions',coalesce((select jsonb_agg(t) from public.transactions t where user_id=who),'[]'),
 'summaries',coalesce((select jsonb_agg(t) from public.monthly_summaries t where user_id=who),'[]'),
 'categories',coalesce((select jsonb_agg(t) from public.categories t where user_id=who),'[]'),
 'deletions',coalesce((select jsonb_agg(t) from public.deletions t where user_id=who),'[]'),
 'loans',coalesce((select jsonb_agg(t) from public.loans t where who in(lender_id,borrower_id)),'[]'),
 'events',coalesce((select jsonb_agg(t) from public.loan_events t where loan_id in(select id from public.loans where who in(lender_id,borrower_id))),'[]'),
 'friendships',coalesce((select jsonb_agg(t) from public.friendships t where who in(user_id,friend_id)),'[]'),
 'profiles',coalesce((select jsonb_agg(jsonb_build_object('id',id,'username',username,'display_name',display_name,'updated_at',(extract(epoch from updated_at)*1000)::bigint)) from public.profiles where id=who or id in(select friend_id from public.friendships where user_id=who) or id in(select user_id from public.friendships where friend_id=who)),'[]')
 ) into result;
 return result;
end $$;
revoke all on function private.ledger_snapshot() from public,anon;
grant execute on function private.ledger_snapshot() to authenticated;
create function public.ledger_snapshot() returns jsonb language sql security invoker set search_path='' as $$ select private.ledger_snapshot() $$;
revoke all on function public.ledger_snapshot() from public,anon;
grant execute on function public.ledger_snapshot() to authenticated;

-- Administrator-only scheduled sweep; works even when no device is online.
create function private.ledger_sweep() returns void language plpgsql set search_path='' as $$
declare who uuid; begin
 perform pg_advisory_xact_lock(68192734);
 for who in select distinct user_id from public.transactions loop perform private.ledger_compact(who); end loop;
end $$;
revoke all on function private.ledger_sweep() from public,anon,authenticated;
do $$ begin
 if exists(select 1 from pg_extension where extname='pg_cron') then
  perform cron.schedule('ledger-trash-cleanup','17 * * * *','select private.ledger_sweep()');
 end if;
 if exists(select 1 from pg_publication where pubname='supabase_realtime') then
  alter publication supabase_realtime add table public.friendships,public.loans,public.loan_events;
 end if;
end $$;
-- Disable the old write path so expired records cannot return through v0.2 clients.
revoke insert on public.movements from authenticated;
revoke execute on function public.sync_ledger(jsonb) from authenticated;
