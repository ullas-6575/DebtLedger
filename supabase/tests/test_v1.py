"""Isolated v1 migration, authorization, approval and Trash regressions (local PostgreSQL only)."""
import json, os, subprocess, uuid
from pathlib import Path
DB='ledger_v1_'+uuid.uuid4().hex
ENV=dict(os.environ,PGDATABASE=DB)
ROOT=Path(__file__).resolve().parents[2]
A='11111111-1111-1111-1111-111111111111'
B='22222222-2222-2222-2222-222222222222'
C='33333333-3333-3333-3333-333333333333'
checks=0

def sql(s,who=None,fail=False,role=None):
    prefix=(f"set role {role or 'authenticated'};" if who or role else '')+(f"set request.jwt.claim.sub='{who}';" if who else '')
    r=subprocess.run(['psql','-X','-qAt','-v','ON_ERROR_STOP=1'],input=prefix+s,text=True,capture_output=True,env=ENV)
    if fail:
        assert r.returncode, 'Expected rejection: '+s
        return r.stderr
    assert not r.returncode,r.stderr
    return r.stdout.strip()
def check(value,label):
    global checks
    assert value,label
    checks+=1
    print('PASS:',label)
def cmd(kind,id,action,payload,who=A,fail=False,receipt=None):
    receipt=receipt or str(uuid.uuid4())
    body=json.dumps(payload).replace("'","''")
    return sql(f"select public.ledger_command('{receipt}','{kind}','{id}','{action}','{body}');",who,fail)
def tx(amount,who=A,kind='INCOME',date=1791500000000):
    row=dict(id=str(uuid.uuid4()),user_id=who,type=kind,amount_minor=amount,account='CASH',destination=None,category_id=who+':other',description='',occurred_at=date)
    cmd('TRANSACTION',row['id'],'INSERT',row,who)
    return row
def snap(who=A): return json.loads(sql('select public.ledger_snapshot();',who))

subprocess.run(['createdb',DB],check=True)
try:
    sql("""do $$ begin
      if not exists(select from pg_roles where rolname='anon') then create role anon; end if;
      if not exists(select from pg_roles where rolname='authenticated') then create role authenticated; end if;
    end $$;
    create schema auth; create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to authenticated,anon;
    """+f"insert into auth.users values('{A}'),('{B}'),('{C}');")
    for name in ['001_private_ledger.sql','002_v1_schema.sql','003_shared_loans.sql']:
        sql((ROOT/'supabase/migrations'/name).read_text())
    check(True,'all three migrations apply cleanly')
    income=tx(100000)
    check(len(snap()['transactions'])==1,'personal record sync')
    check(not snap(B)['transactions'],'owner isolation in snapshot')
    check(sql('select count(*) from public.transactions;',B)=='0','direct RLS owner isolation')
    check('permission denied' in sql('select public.ledger_snapshot();',role='anon',fail=True),'anonymous RPC denied')
    check('permission denied' in sql('delete from public.transactions;',A,True),'clients cannot delete originals early')
    check('permission denied' in sql('select private.ledger_compact(null);',A,True),'private cleanup not exposed')
    cmd('TRANSACTION',income['id'],'INSERT',income)
    check(len(snap()['transactions'])==1,'transaction UUID retry is idempotent')
    forged=dict(income,amount_minor=1)
    check('collision' in cmd('TRANSACTION',income['id'],'INSERT',forged,fail=True),'changed UUID payload rejected')
    forged=dict(income,id=str(uuid.uuid4()),user_id=B)
    check('owner' in cmd('TRANSACTION',forged['id'],'INSERT',forged,fail=True),'forged owner rejected')
    fid=str(uuid.uuid4()); friendship=dict(id=fid,user_id=A,friend_id=B)
    cmd('FRIENDSHIP',fid,'PROPOSE',friendship)
    check('Invalid' in cmd('FRIENDSHIP',fid,'ACCEPT',{},fail=True),'sender cannot accept own friendship')
    cmd('FRIENDSHIP',fid,'ACCEPT',{},B)
    check(snap(B)['friendships'][0]['status']=='ACCEPTED','recipient accepts friendship')
    lid=str(uuid.uuid4()); loan=dict(id=lid,lender_id=A,borrower_id=B,principal_minor=50000,description='Test',proposed_by=A,lender_account='CASH',borrower_account='CASH')
    cmd('LOAN',lid,'PROPOSE',loan)
    check(snap()['loans'][0]['status']=='UNDER_REVIEW','loan starts pending')
    check(sql(f"select private.account_balance('{A}','CASH');")=='100000','pending loan has no financial effect')
    check('counterparty' in cmd('LOAN',lid,'ACCEPT',{'account':'CASH'},fail=True),'proposer cannot self-approve')
    check('counterparty' in cmd('LOAN',lid,'ACCEPT',{'account':'CASH'},C,True),'third party cannot approve')
    receipt=str(uuid.uuid4());cmd('LOAN',lid,'ACCEPT',{'account':'CASH'},B,receipt=receipt)
    cmd('LOAN',lid,'ACCEPT',{'account':'CASH'},B,receipt=receipt)
    check(sql(f"select private.account_balance('{A}','CASH');")=='50000','acceptance posts lender funds once')
    check(sql(f"select private.account_balance('{B}','CASH');")=='50000','acceptance posts borrower funds once')
    check(not snap(C)['loans'],'shared loan invisible to third party')
    eid=str(uuid.uuid4());event=dict(id=eid,loan_id=lid,type='REPAYMENT',amount_minor=20000,proposed_by=B)
    cmd('LOAN_EVENT',eid,'PROPOSE',event,B)
    check('decision' in cmd('LOAN_EVENT',eid,'ACCEPT',{},B,True),'repayment requires other party')
    cmd('LOAN_EVENT',eid,'ACCEPT',{})
    check(snap()['loans'][0]['status']=='PARTIALLY_REPAID','confirmed partial repayment')
    eid2=str(uuid.uuid4());event2=dict(event,id=eid2,amount_minor=40000)
    check('amount' in cmd('LOAN_EVENT',eid2,'PROPOSE',event2,B,True),'overpayment rejected')
    correction=str(uuid.uuid4());ce=dict(id=correction,loan_id=lid,type='CORRECTION',amount_minor=45000,proposed_by=A)
    cmd('LOAN_EVENT',correction,'PROPOSE',ce)
    cmd('LOAN_EVENT',correction,'ACCEPT',{},B)
    loans=snap()['loans']
    check(any(l['id']==lid and l['status']=='SUPERSEDED' for l in loans),'correction preserves superseded original')
    check(any(l['id']==correction and l['repaid_minor']==20000 for l in loans),'replacement carries confirmed repayments')
    old=tx(12345,date=1750000000000)
    before=sql(f"select private.account_balance('{A}','CASH');")
    s=snap();r=next(t for t in s['transactions'] if t['id']==old['id'])
    check(r['summary_status']=='TRASHED' and r['delete_after']-r['trashed_at']==2592000000,'closed month enters 30-day Trash')
    check(sql(f"select private.account_balance('{A}','CASH');")==before,'compaction preserves balance')
    original=r['delete_after'];s=snap()
    check(next(t for t in s['transactions'] if t['id']==old['id'])['delete_after']==original,'compaction retries do not extend deadline')
    sql(f"update public.transactions set trashed_at=0,delete_after=2592000000 where id='{old['id']}';")
    s=snap()
    check(all(t['id']!=old['id'] for t in s['transactions']) and any(d['id']==old['id'] for d in s['deletions']),'expired detail purged with tombstone')
    check(sql(f"select private.account_balance('{A}','CASH');")==before,'deletion preserves account balance')
    cmd('TRANSACTION',old['id'],'INSERT',old)
    check(all(t['id']!=old['id'] for t in snap()['transactions']),'stale device cannot resurrect deleted transaction')
    check(len(snap()['summaries'])==1,'monthly summary retained after purge')
    check('permission denied' in sql("select public.sync_ledger('[]');",A,True),'old sync cannot resurrect expired details')
    old_income=dict(id=str(uuid.uuid4()),kind='INCOME',amount=10000,account='CASH',destination=None,category=None,person=None,note='offline',occurred_at='2026-10-10T06:00:00Z')
    old_loan=dict(old_income,id=str(uuid.uuid4()),kind='LEND',amount=2000,person='Private friend',occurred_at='2026-10-10T06:00:01Z')
    old_expense=dict(old_income,id=str(uuid.uuid4()),kind='EXPENSE',amount=1000,category='EAT',occurred_at='2026-10-10T06:00:02Z')
    batch=[old_income,old_loan,old_expense]
    cmd('LEGACY_IMPORT',str(uuid.uuid4()),'INSERT',batch,C)
    check(sql(f"select private.account_balance('{C}','CASH');")=='7000','mixed offline history imports in order')
    check(len(snap(C)['legacy_movements'])==1 and not snap(C)['loans'],'legacy name remains private without invented approval')
    cmd('LEGACY_IMPORT',str(uuid.uuid4()),'INSERT',batch,C)
    check(sql(f"select private.account_balance('{C}','CASH');")=='7000','repeated offline import does not duplicate balances')
    check(not snap(A)['legacy_movements'],'legacy snapshots remain owner isolated')
    newcat=C+':custom'
    cmd('CATEGORY',newcat,'INSERT',dict(id=newcat,user_id=C,name='Custom',icon='X',is_default=False,sort_order=9),C)
    check(any(c['id']==newcat for c in snap(C)['categories']),'category queue supports stable text IDs')
    print(f'{checks} v1 checks passed')
finally:
    subprocess.run(['dropdb','--if-exists',DB],check=True)
