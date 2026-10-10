-- v1 personal ledger. Apply after 001. All amounts are integer poisha and times epoch milliseconds.
alter table public.profiles add column if not exists avatar_url text;
alter table public.profiles add column if not exists updated_at timestamptz not null default now();
create table public.categories (
 id text primary key, user_id uuid not null references auth.users(id) on delete cascade,
 name text not null check(length(name) between 1 and 40), icon text not null check(length(icon) between 1 and 16),
 is_default boolean not null default false, sort_order integer not null default 0,
 unique(id,user_id)
);
create index categories_user on public.categories(user_id);
create table public.monthly_summaries (
 id text primary key, user_id uuid not null references auth.users(id) on delete cascade,
 year integer not null, month integer not null check(month between 1 and 12), category_id text not null,
 account text not null check(account in ('CASH','BKASH','BANK')),
 income_minor bigint not null default 0, expense_minor bigint not null default 0,
 transaction_count integer not null default 0, generated_at bigint not null, version integer not null default 1,
 unique(user_id,year,month,category_id,account),
 foreign key(category_id,user_id) references public.categories(id,user_id)
);
create index monthly_summaries_category on public.monthly_summaries(category_id,user_id);
create table public.transactions (
 id uuid primary key, user_id uuid not null references auth.users(id) on delete cascade,
 type text not null check(type in ('INCOME','EXPENSE','TRANSFER')),
 amount_minor bigint not null check(amount_minor between 1 and 100000000000),
 account text not null check(account in ('CASH','BKASH','BANK')),
 destination text check(destination in ('CASH','BKASH','BANK')), category_id text,
 description text not null default '' check(length(description)<=300), occurred_at bigint not null,
 sync_status text not null default 'SYNCED' check(sync_status='SYNCED'),
 summary_status text not null default 'ACTIVE' check(summary_status in ('ACTIVE','TRASHED')),
 summary_id text references public.monthly_summaries(id), trashed_at bigint, delete_after bigint,
 version integer not null default 1, created_at bigint not null, updated_at bigint not null,
 foreign key(category_id,user_id) references public.categories(id,user_id),
 check((type='TRANSFER' and destination is not null and destination<>account and category_id is null)
    or (type<>'TRANSFER' and destination is null and category_id is not null)),
 check((summary_status='ACTIVE' and summary_id is null and trashed_at is null and delete_after is null)
    or (summary_status='TRASHED' and summary_id is not null and trashed_at is not null and delete_after=trashed_at+2592000000))
);
create index transactions_owner_date on public.transactions(user_id,occurred_at);
create index transactions_category on public.transactions(category_id,user_id);
create index transactions_summary on public.transactions(summary_id);
create index transactions_expiration on public.transactions(delete_after) where summary_status='TRASHED';
create table public.deletions (
 id uuid primary key, user_id uuid not null references auth.users(id) on delete cascade, deleted_at bigint not null
);
create index deletions_owner on public.deletions(user_id);
create table private.ledger_commands (
 id uuid primary key, user_id uuid not null references auth.users(id) on delete cascade,
 created_at timestamptz not null default now()
);
create index ledger_commands_owner on private.ledger_commands(user_id);
alter table private.ledger_commands enable row level security;
-- Only the command engine writes these tables. Clients cannot forge summaries or Trash timestamps.
do $$ declare t text; begin
 foreach t in array array['categories','transactions','monthly_summaries','deletions'] loop
  execute format('alter table public.%I enable row level security',t);
  execute format('revoke all on public.%I from anon, authenticated',t);
  execute format('grant select on public.%I to authenticated',t);
  execute format('create policy owner_read on public.%I for select to authenticated using ((select auth.uid())=user_id)',t);
 end loop;
end $$;

create function private.ledger_compact(who uuid) returns void language plpgsql set search_path='' as $$
declare stamp bigint := (extract(epoch from now())*1000)::bigint;
 cutoff bigint := (extract(epoch from (date_trunc('month',now() at time zone 'Asia/Dhaka') at time zone 'Asia/Dhaka'))*1000)::bigint;
 r record; sid text;
begin
 perform pg_advisory_xact_lock(hashtextextended(who::text,42));
 for r in select extract(year from to_timestamp(occurred_at/1000.0) at time zone 'Asia/Dhaka')::integer y,
 extract(month from to_timestamp(occurred_at/1000.0) at time zone 'Asia/Dhaka')::integer m,
 category_id,account,sum(case when type='INCOME' then amount_minor else 0 end)::bigint inc,
 sum(case when type='EXPENSE' then amount_minor else 0 end)::bigint exp,count(*)::integer n
 from public.transactions where user_id=who and summary_status='ACTIVE' and type<>'TRANSFER' and occurred_at<cutoff
 group by 1,2,3,4 loop
  sid:=who::text||':'||r.y||':'||r.m||':'||r.category_id||':'||r.account;
  insert into public.monthly_summaries values(sid,who,r.y,r.m,r.category_id,r.account,r.inc,r.exp,r.n,stamp,1)
   on conflict(user_id,year,month,category_id,account) do update set
    income_minor=public.monthly_summaries.income_minor+excluded.income_minor,
    expense_minor=public.monthly_summaries.expense_minor+excluded.expense_minor,
    transaction_count=public.monthly_summaries.transaction_count+excluded.transaction_count,
    version=public.monthly_summaries.version+1,generated_at=stamp returning id into sid;
  update public.transactions set summary_status='TRASHED',summary_id=sid,trashed_at=stamp,delete_after=stamp+2592000000,updated_at=stamp
   where user_id=who and summary_status='ACTIVE' and type<>'TRANSFER' and category_id=r.category_id and account=r.account
   and extract(year from to_timestamp(occurred_at/1000.0) at time zone 'Asia/Dhaka')=r.y
   and extract(month from to_timestamp(occurred_at/1000.0) at time zone 'Asia/Dhaka')=r.m and occurred_at<cutoff;
 end loop;
 insert into public.deletions select id,user_id,stamp from public.transactions where user_id=who and delete_after<=stamp on conflict do nothing;
 -- Also remove the legacy backup of expired details. It must not act as an undeletable archive.
 delete from public.movements where owner_id=who and id in(select id from public.deletions where user_id=who);
 delete from public.transactions where user_id=who and delete_after<=stamp;
end $$;
revoke all on function private.ledger_compact(uuid) from public,anon,authenticated;

-- Migrate all existing personal records, including records saved only in the cloud.
insert into public.categories
select u.id::text||':'||d.slug,u.id,d.name,d.icon,true,d.position
from auth.users u cross join (values ('eating','Eating','🍔',0),('payment','Payment','💡',1),('transport','Transport','🚌',2),('shopping','Shopping','🛍️',3),('education','Education','📚',4),('entertainment','Entertainment','🎮',5),('health','Health','💊',6),('other','Others','📦',7)) d(slug,name,icon,position);
insert into public.transactions(id,user_id,type,amount_minor,account,destination,category_id,description,occurred_at,created_at,updated_at)
select id,owner_id,kind,amount,account,destination,
 case when kind='TRANSFER' then null else owner_id::text||':'||case category when 'EAT' then 'eating' when 'PAYMENT' then 'payment' else 'other' end end,
 note,(extract(epoch from occurred_at)*1000)::bigint,(extract(epoch from created_at)*1000)::bigint,(extract(epoch from created_at)*1000)::bigint
from public.movements where kind in ('INCOME','EXPENSE','TRANSFER');
