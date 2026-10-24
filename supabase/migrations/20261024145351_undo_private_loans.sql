-- Extend the command API without granting clients direct writes to shared ledgers.
alter table public.loan_events drop constraint loan_events_status_check;
alter table public.loan_events add constraint loan_events_status_check
 check(status in ('PENDING','CONFIRMED','REJECTED','UNDONE'));

alter function private.ledger_command(uuid,text,text,text,jsonb) rename to ledger_command_v3;
revoke all on function private.ledger_command_v3(uuid,text,text,text,jsonb) from public,anon,authenticated;

create function private.ledger_command(command_id uuid,entity_type text,entity_id text,action text,payload jsonb)
returns void language plpgsql security definer set search_path='' as $$
declare
 who uuid:=auth.uid(); stamp bigint:=(extract(epoch from now())*1000)::bigint;
 eid uuid; t public.transactions; m public.legacy_movements; l public.loans; e public.loan_events;
 filtered jsonb; person_name text; debt bigint:=0; r record;
begin
 if who is null then raise exception 'Authentication required'; end if;
 perform pg_advisory_xact_lock(68192734);
 if exists(select 1 from private.ledger_commands where id=command_id and user_id=who) then return; end if;
 if entity_type<>'CATEGORY' then eid:=entity_id::uuid; end if;

 if action='UNDO' and entity_type in ('TRANSACTION','PRIVATE_MOVEMENT') then
  if exists(select 1 from public.deletions where id=eid and user_id=who) then
   null; -- Already removed; duplicate taps must not subtract totals twice.
  else
   if entity_type='TRANSACTION' then
    select * into strict t from public.transactions where id=eid and user_id=who for update;
    if t.summary_status='TRASHED' then
     update public.monthly_summaries set
      income_minor=income_minor-case when t.type='INCOME' then t.amount_minor else 0 end,
      expense_minor=expense_minor-case when t.type='EXPENSE' then t.amount_minor else 0 end,
      transaction_count=transaction_count-1,version=version+1
      where id=t.summary_id and user_id=who;
    end if;
    delete from public.transactions where id=eid and user_id=who;
    delete from public.monthly_summaries where id=t.summary_id and user_id=who and transaction_count=0;
   else
    select * into strict m from public.legacy_movements where id=eid and owner_id=who for update;
    delete from public.legacy_movements where id=eid and owner_id=who;
    -- Replay debt only: removing a loan must not turn a later repayment into a loan.
    for r in select * from public.legacy_movements where owner_id=who and person=m.person order by occurred_at,created_at,id loop
     if (r.kind='RECEIVE_REPAYMENT' and (debt<=0 or r.amount>debt)) or
        (r.kind='PAY_REPAYMENT' and (debt>=0 or r.amount> -debt)) then
      raise exception 'Undo later repayments for this person first';
     end if;
     debt:=debt+case when r.kind in('LEND','PAY_REPAYMENT') then r.amount else -r.amount end;
    end loop;
   end if;
   if exists(select 1 from unnest(array['CASH','BKASH','BANK']) a where private.account_balance(who,a)<0) then
    raise exception 'Undo would leave insufficient account balance';
   end if;
   delete from public.movements where id=eid and owner_id=who;
   insert into public.deletions values(eid,who,stamp);
  end if;
 elsif entity_type='LOAN' and action='UNDO' then
  select * into strict l from public.loans where id=eid for update;
  if l.lender_id<>who then raise exception 'Only lender can undo a loan'; end if;
  if l.status<>'CANCELLED' then
   if l.status not in('ACTIVE','PARTIALLY_REPAID') or l.repaid_minor<>0 then raise exception 'Undo repayments before undoing this loan'; end if;
   update public.loans set status='CANCELLED',version=version+1 where id=eid;
   if private.account_balance(l.borrower_id,l.borrower_account)<0 then raise exception 'Undo would leave insufficient borrower balance'; end if;
   update public.loan_events set status='REJECTED' where loan_id=eid and status='PENDING';
  end if;
 elsif entity_type='LOAN_EVENT' and action='UNDO' then
  select * into strict e from public.loan_events where id=eid for update;
  select * into strict l from public.loans where id=e.loan_id for update;
  if l.lender_id<>who or e.type<>'REPAYMENT' then raise exception 'Only lender can undo a repayment'; end if;
  if e.status<>'UNDONE' then
   if e.status<>'CONFIRMED' or l.status not in('ACTIVE','PARTIALLY_REPAID','REPAID') then raise exception 'Repayment cannot be undone on this loan'; end if;
   update public.loans set repaid_minor=repaid_minor-e.amount_minor,
    status=case when repaid_minor=e.amount_minor then 'ACTIVE' else 'PARTIALLY_REPAID' end,
    fully_repaid_at=null,version=version+1 where id=l.id;
   if private.account_balance(who,l.lender_account)<0 then raise exception 'Undo would leave insufficient lender balance'; end if;
   update public.loan_events set status='UNDONE' where id=eid;
  end if;
 elsif entity_type='LOAN_EVENT' and action='RECORD' then
  e:=jsonb_populate_record(null::public.loan_events,payload);
  select * into strict l from public.loans where id=e.loan_id for update;
  if e.id is distinct from eid or e.proposed_by is distinct from who or l.lender_id<>who or e.type is distinct from 'REPAYMENT' then
   raise exception 'Only lender can directly record a repayment';
  end if;
  if exists(select 1 from public.loan_events where id=eid) then
   if not exists(select 1 from public.loan_events where id=eid and loan_id=l.id and type='REPAYMENT' and proposed_by=who and amount_minor=e.amount_minor and status in('CONFIRMED','UNDONE')) then
    raise exception 'Repayment collision';
   end if;
  else
   if l.status not in('ACTIVE','PARTIALLY_REPAID') or e.amount_minor is null or e.amount_minor not between 1 and l.principal_minor-l.repaid_minor then raise exception 'Repayment exceeds balance'; end if;
   if private.account_balance(l.borrower_id,l.borrower_account)<e.amount_minor then raise exception 'Insufficient borrower balance'; end if;
   insert into public.loan_events values(eid,l.id,'REPAYMENT',e.amount_minor,who,who,'CONFIRMED',stamp,'SYNCED');
   update public.loans set repaid_minor=repaid_minor+e.amount_minor,
    status=case when repaid_minor+e.amount_minor=principal_minor then 'REPAID' else 'PARTIALLY_REPAID' end,
    fully_repaid_at=case when repaid_minor+e.amount_minor=principal_minor then stamp end,version=version+1 where id=l.id;
   update public.loan_events set status='REJECTED' where loan_id=l.id and status='PENDING' and
    (l.repaid_minor+e.amount_minor=l.principal_minor or (type='REPAYMENT' and amount_minor>l.principal_minor-l.repaid_minor-e.amount_minor));
  end if;
 elsif entity_type='PRIVATE_MOVEMENT' and action='INSERT' then
  if payload->>'id' is distinct from entity_id or payload->>'kind' is null or payload->>'kind' not in('LEND','BORROW','RECEIVE_REPAYMENT','PAY_REPAYMENT') then raise exception 'Invalid private record'; end if;
  if payload->>'owner_id' is not null and payload->>'owner_id'<>who::text then raise exception 'Invalid owner'; end if;
  if not exists(select 1 from public.deletions where id=eid and user_id=who) then
   person_name:=btrim(regexp_replace(payload->>'person','\s+',' ','g'));
   if person_name is null or length(person_name) not between 1 and 80 then raise exception 'Enter a person name up to 80 characters'; end if;
   select person into person_name from public.legacy_movements where owner_id=who and lower(person)=lower(person_name) limit 1;
   person_name:=coalesce(person_name,btrim(regexp_replace(payload->>'person','\s+',' ','g')));
   perform private.ledger_command_v3(command_id,'LEGACY_IMPORT',entity_id,'INSERT',jsonb_build_array(payload||jsonb_build_object('person',person_name)));
   return;
  end if;
 elsif entity_type='LEGACY_IMPORT' then
  -- Old offline backups cannot resurrect records that have been undone.
  select coalesce(jsonb_agg(value order by ordinal),'[]'::jsonb) into filtered
   from jsonb_array_elements(payload) with ordinality a(value,ordinal)
   where not exists(select 1 from public.deletions where id=(value->>'id')::uuid and user_id=who);
  perform private.ledger_command_v3(command_id,entity_type,entity_id,action,filtered);
  return;
 else
  perform private.ledger_command_v3(command_id,entity_type,entity_id,action,payload);
  return;
 end if;
 insert into private.ledger_commands(id,user_id) values(command_id,who);
end $$;
revoke all on function private.ledger_command(uuid,text,text,text,jsonb) from public,anon;
grant execute on function private.ledger_command(uuid,text,text,text,jsonb) to authenticated;
-- Rebind the public wrapper to the new implementation after renaming the old function.
create or replace function public.ledger_command(command_id uuid,entity_type text,entity_id text,action text,payload jsonb)
returns void language sql security invoker set search_path='' as $$
 select private.ledger_command(command_id,entity_type,entity_id,action,payload)
$$;
