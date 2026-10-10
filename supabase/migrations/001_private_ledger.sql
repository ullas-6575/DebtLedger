-- Initial private ledger schema and posting rules for optional Android cloud sync.
-- Run with the Supabase SQL editor or CLI against your own development project.
create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null unique check (username ~ '^[a-z0-9_]{3,32}$'),
  display_name text not null check (char_length(display_name) between 1 and 80),
  created_at timestamptz not null default now()
);
create table public.movements (
  id uuid primary key,
  owner_id uuid not null references auth.users(id) on delete cascade default auth.uid(),
  kind text not null check (kind in ('INCOME','EXPENSE','TRANSFER','LEND','BORROW','RECEIVE_REPAYMENT','PAY_REPAYMENT')),
  amount bigint not null check (amount between 1 and 100000000000),
  account text not null check (account in ('CASH','BKASH','BANK')),
  destination text check (destination in ('CASH','BKASH','BANK')),
  category text check (category in ('EAT','PAYMENT','OTHER')),
  person text check (char_length(person) between 1 and 80),
  note text not null default '' check (char_length(note) <= 300),
  occurred_at timestamptz not null,
  created_at timestamptz not null default now(),
  constraint transfer_fields check (
    (kind = 'TRANSFER' and destination is not null and destination <> account)
    or (kind <> 'TRANSFER' and destination is null)
  ),
  constraint expense_fields check (
    (kind = 'EXPENSE' and category is not null)
    or (kind <> 'EXPENSE' and category is null)
  ),
  constraint loan_fields check (
    (kind in ('LEND','BORROW','RECEIVE_REPAYMENT','PAY_REPAYMENT') and person is not null and btrim(person) <> '')
    or (kind in ('INCOME','EXPENSE','TRANSFER') and person is null)
  )
);
create index movements_owner_date on public.movements(owner_id, occurred_at desc);
alter table public.profiles enable row level security;
alter table public.movements enable row level security;
revoke all on public.profiles, public.movements from anon;
revoke all on public.profiles, public.movements from authenticated;
grant select, insert on public.profiles, public.movements to authenticated;
create policy own_profile_read on public.profiles for select to authenticated using ((select auth.uid()) = id);
create policy own_profile_insert on public.profiles for insert to authenticated with check ((select auth.uid()) = id);
create policy own_movement_read on public.movements for select to authenticated using ((select auth.uid()) = owner_id);
create policy own_movement_insert on public.movements for insert to authenticated with check ((select auth.uid()) = owner_id);
-- Append-only: no update/delete permissions. Corrections should be separate records.
-- The trigger and RPC below enforce posting rules across concurrent devices.

-- Server posting rules for Android's manual sync. This migration has not yet
-- been applied to the configured project; run the complete file for first setup.
create schema if not exists private;
revoke all on schema private from public;

create index movements_owner_person on public.movements(owner_id, lower(person))
  where person is not null;

-- A trigger protects both the RPC and direct Data API inserts. It runs as the
-- caller, so the existing owner policies still apply. Every owner's postings
-- share a transaction-level lock, including concurrent requests from devices.
create or replace function private.validate_movement()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  previous public.movements%rowtype;
  account_balance numeric;
  person_balance numeric;
  canonical_name text;
begin
  if (select auth.uid()) is null or new.owner_id is distinct from (select auth.uid()) then
    raise exception 'Authentication required for the ledger owner' using errcode = '42501';
  end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(new.owner_id::text, 0));

  new.note := btrim(new.note);
  if new.person is not null then
    new.person := btrim(regexp_replace(new.person, '\s+', ' ', 'g'));
    select m.person into canonical_name from public.movements m
      where m.owner_id = new.owner_id and lower(m.person) = lower(new.person)
      order by m.created_at, m.id limit 1;
    new.person := coalesce(canonical_name, new.person);
  end if;

  select * into previous from public.movements m where m.id = new.id;
  if found then
    if (previous.owner_id, previous.kind, previous.amount, previous.account,
        previous.destination, previous.category, previous.person, previous.note, previous.occurred_at)
       is distinct from
       (new.owner_id, new.kind, new.amount, new.account,
        new.destination, new.category, new.person, new.note, new.occurred_at) then
      raise exception 'Record id already exists with different content' using errcode = '22023';
    end if;
    return null; -- An identical retry is a no-op, even if the money is now spent.
  end if;

  select coalesce(sum(
    case when m.account = new.account then
      case when m.kind in ('INCOME', 'BORROW', 'RECEIVE_REPAYMENT') then m.amount else -m.amount end
      else 0 end
    + case when m.kind = 'TRANSFER' and m.destination = new.account then m.amount else 0 end
  ), 0) into account_balance from public.movements m where m.owner_id = new.owner_id;

  if new.kind in ('EXPENSE', 'TRANSFER', 'LEND', 'PAY_REPAYMENT') and account_balance < new.amount then
    raise exception 'Insufficient account balance' using errcode = '22023';
  end if;

  select coalesce(sum(case when m.kind in ('LEND', 'PAY_REPAYMENT') then m.amount
    when m.kind in ('BORROW', 'RECEIVE_REPAYMENT') then -m.amount else 0 end), 0)
    into person_balance from public.movements m
    where m.owner_id = new.owner_id and m.person = new.person;

  if new.kind = 'RECEIVE_REPAYMENT' and (person_balance <= 0 or new.amount > person_balance) then
    raise exception 'Repayment exceeds what this person owes you' using errcode = '22023';
  end if;
  if new.kind = 'PAY_REPAYMENT' and (person_balance >= 0 or new.amount > -person_balance) then
    raise exception 'Repayment exceeds what you owe this person' using errcode = '22023';
  end if;
  return new;
end;
$$;
revoke all on function private.validate_movement() from public, anon, authenticated;
create trigger validate_movement_before_insert
  before insert on public.movements
  for each row execute function private.validate_movement();

-- One atomic upload and snapshot: validation errors roll back the entire batch.
-- The owner always comes from the JWT, never from the client's JSON.
-- Return a JSON object so PostgREST's row limit cannot truncate the snapshot.
create or replace function public.sync_ledger(records jsonb)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
  owner uuid := (select auth.uid());
  entry jsonb;
  snapshot jsonb;
begin
  if owner is null then
    raise exception 'Sign in before syncing' using errcode = '42501';
  end if;
  if records is null or jsonb_typeof(records) <> 'array' then
    raise exception 'Expected an array of records' using errcode = '22023';
  end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(owner::text, 0));
  for entry in select value from jsonb_array_elements(records) with ordinality as items(value, position) order by position loop
    if entry ->> 'owner_id' is not null and (entry ->> 'owner_id')::uuid <> owner then
      raise exception 'Cannot sync another owner' using errcode = '42501';
    end if;
    insert into public.movements(id, owner_id, kind, amount, account, destination, category, person, note, occurred_at)
    values (
      (entry ->> 'id')::uuid, owner, entry ->> 'kind', (entry ->> 'amount')::bigint,
      entry ->> 'account', entry ->> 'destination', entry ->> 'category', entry ->> 'person',
      coalesce(entry ->> 'note', ''), (entry ->> 'occurred_at')::timestamptz
    );
  end loop;
  select coalesce(jsonb_agg(to_jsonb(m) order by m.occurred_at, m.created_at, m.id), '[]'::jsonb)
    into snapshot from public.movements m where m.owner_id = owner;
  return jsonb_build_object('movements', snapshot);
end;
$$;
revoke all on function public.sync_ledger(jsonb) from public, anon;
grant execute on function public.sync_ledger(jsonb) to authenticated;
