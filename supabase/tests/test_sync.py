"""Run against a disposable local PostgreSQL server; creates/drops its own test DB.

Example: PGHOST=/tmp PGPORT=55439 python3 supabase/tests/test_sync.py
Requires psql/createdb/dropdb on PATH. Does not connect to the hosted project.
"""
import json
import os
from pathlib import Path
import subprocess
import uuid
from concurrent.futures import ThreadPoolExecutor

DB = 'ledgerx_test_' + uuid.uuid4().hex
OWNER = '11111111-1111-1111-1111-111111111111'
OTHER = '22222222-2222-2222-2222-222222222222'
ROOT = Path(__file__).resolve().parents[2]
ENV = dict(os.environ, PGDATABASE=DB)
checks = 0


def sql(statement, owner=None, role=None, succeeds=True):
    prefix = ''
    if role or owner:
        prefix = 'set role ' + (role or 'authenticated') + ';\n'
    if owner:
        prefix += "set request.jwt.claim.sub = '" + owner + "';\n"
    result = subprocess.run(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'],
                            input=prefix + statement, text=True, capture_output=True, env=ENV)
    if succeeds and result.returncode:
        raise AssertionError(result.stderr)
    if not succeeds and not result.returncode:
        raise AssertionError('Expected rejection: ' + statement)
    return result.stdout.strip() if succeeds else result.stderr


def record(kind, amount, account='CASH', person=None, category=None, destination=None):
    return dict(id=str(uuid.uuid4()), kind=kind, amount=amount, account=account,
                person=person, category=category, destination=destination, note='',
                occurred_at='2026-10-10T06:00:00.123Z')


def sync(rows, owner=OWNER, succeeds=True):
    payload = json.dumps(rows).replace("'", "''")
    result = sql("select public.sync_ledger('" + payload + "'::jsonb);", owner=owner, succeeds=succeeds)
    return json.loads(result)['movements'] if succeeds else result


def check(condition, label):
    global checks
    assert condition, label
    checks += 1
    print('PASS:', label)


subprocess.run(['createdb', DB], check=True, env=os.environ)
try:
    sql("""
    do $$ begin
      if not exists (select from pg_roles where rolname='anon') then create role anon; end if;
      if not exists (select from pg_roles where rolname='authenticated') then create role authenticated; end if;
    end $$;
    create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
    grant usage on schema auth to anon, authenticated;
    insert into auth.users values ('11111111-1111-1111-1111-111111111111'), ('22222222-2222-2222-2222-222222222222');
    """)
    sql((ROOT / 'supabase/migrations/001_private_ledger.sql').read_text())
    rows = [record('INCOME', 100000), record('LEND', 30000, person=' Rahim  Office '),
            record('BORROW', 20000, person='Karim'), record('RECEIVE_REPAYMENT', 10000, person='rahim office'),
            record('PAY_REPAYMENT', 5000, person='Karim'), record('EXPENSE', 12550, category='EAT'),
            record('TRANSFER', 40000, destination='BANK')]
    result = sync(rows)
    check(len(result) == 7, 'all seven record types post atomically')
    check(result[0]['owner_id'] == OWNER, 'owner comes from auth context')
    check({r['person'] for r in result if r['kind'] in ('LEND', 'RECEIVE_REPAYMENT')} == {'Rahim Office'}, 'case and whitespace normalize to one person')
    check(len(sync(rows)) == 7, 'retry of a spent opening income and entire batch is idempotent')
    check(sync([], OTHER) == [], 'second user cannot see first user snapshot')
    check(sql('select count(*) from public.movements;', owner=OTHER) == '0', 'RLS isolates direct reads')
    check('permission denied' in sql('select * from public.movements;', role='anon', succeeds=False), 'anonymous reads denied')
    check('permission denied' in sql("select public.sync_ledger('[]');", role='anon', succeeds=False), 'anonymous RPC denied')
    check('permission denied' in sql('delete from public.movements;', owner=OWNER, succeeds=False), 'deletion denied')
    check('permission denied' in sql("update public.movements set amount=1;", owner=OWNER, succeeds=False), 'editing denied')
    check('Insufficient' in sync([record('EXPENSE', 42451, category='EAT')], succeeds=False), 'account sufficiency includes transfers and repayments')
    check('Repayment' in sync([record('RECEIVE_REPAYMENT', 20001, person='Rahim Office')], succeeds=False), 'over-receipt rejected')
    check('Repayment' in sync([record('PAY_REPAYMENT', 15001, person='Karim')], succeeds=False), 'overpayment rejected')
    check('Repayment' in sync([record('RECEIVE_REPAYMENT', 1, person='Unknown')], succeeds=False), 'repayment for unknown person rejected')
    mismatch = dict(rows[0], amount=100001)
    check('different content' in sync([mismatch], succeeds=False), 'UUID payload collision rejected')
    spoof = dict(record('INCOME', 1), owner_id=OTHER)
    check('another owner' in sync([spoof], succeeds=False), 'client-supplied other owner rejected')
    check('violates check constraint' in sync([record('TRANSFER', 1, destination='CASH')], succeeds=False), 'self-transfer rejected')
    check('violates check constraint' in sync([record('INCOME', 0)], succeeds=False), 'zero amount rejected')
    check('violates check constraint' in sync([record('LEND', 1, person='   ')], succeeds=False), 'blank loan name rejected')
    sync([record('INCOME', 1), record('EXPENSE', 99999999, category='OTHER')], succeeds=False)
    check(len(sync([])) == 7, 'failed batch leaves no partially uploaded income')
    direct = "insert into public.movements(id, kind, amount, account, category, occurred_at) values ('%s', 'EXPENSE', 99999999, 'CASH', 'EAT', now());" % uuid.uuid4()
    check('Insufficient' in sql(direct, owner=OWNER, succeeds=False), 'direct insert cannot bypass posting rules')
    sync([record('INCOME', 100), record('LEND', 30, person='Alex'), record('BORROW', 50, person='Alex')], OTHER)
    check('Repayment' in sync([record('RECEIVE_REPAYMENT', 1, person='Alex')], OTHER, succeeds=False), 'opposing loans use net debt direction')
    sync([record('PAY_REPAYMENT', 20, person='Alex')], OTHER)
    check(len(sync([], OTHER)) == 4, 'net debt settles exactly')
    # OTHER now has 100 poisha. Two independent sessions both try to spend 80.
    def spend(_):
        try:
            sync([record('EXPENSE', 80, category='OTHER')], OTHER)
            return True
        except AssertionError as error:
            assert 'Insufficient' in str(error), str(error)
            return False
    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(spend, range(2)))
    check(sum(outcomes) == 1, 'concurrent devices cannot both spend the same funds')
    # A scalar JSON snapshot must include more than PostgREST's default 1000 rows.
    many = [record('INCOME', 1) for _ in range(1001)]
    check(len(sync(many, OTHER)) == 1006, 'snapshot contains all records beyond 1000 rows')
    print(f'{checks} database checks passed.')
finally:
    subprocess.run(['dropdb', DB], check=True, env=os.environ)
