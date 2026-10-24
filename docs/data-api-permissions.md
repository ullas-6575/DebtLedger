# Supabase Data API permissions: October 30, 2026

Supabase's notice changes the defaults for **new** tables in `public`. Existing tables retain their grants. A database recreated from migrations must grant access explicitly. The announcement is not evidence that an existing LedgerX database is broken.

LedgerX migrations already include the required grants alongside their tables and functions. No permission-widening migration is needed for this announcement:

| Object | Signed-in app access | Anonymous access |
| --- | --- | --- |
| `profiles` | SELECT and INSERT, subject to RLS | None |
| Financial tables, including transactions, loans and loan events | SELECT, subject to RLS | None |
| `ledger_command` and `ledger_snapshot` RPCs | EXECUTE, with explicit ownership/participant validation | None |
| Old `sync_ledger` write API | Disabled after migration 003 | None |

Financial writes go through the command API. Do not copy a generic `GRANT ALL` or grant INSERT/UPDATE/DELETE to every table. Do not disable RLS to fix missing grants. LedgerX's Android client does not need `service_role` access or a service-role key; grant server-only access separately if a future backend actually requires it.

## Verify the hosted project

1. Open the intended project directly in the Supabase Dashboard and inspect **Integrations → Data API**. Keep `public` exposed for the app's profile access and public RPCs. Keep `private` out of the exposed schemas.
2. Run [the read-only access audit](../supabase/checks/data_api_access.sql) in SQL Editor. After migrations 001–003, all 18 rows should be `OK`. The audit checks grants and RLS enablement; it does not verify the contents of RLS policies or hosted API settings.
3. If an object is missing, apply its outstanding migration in the [documented order](v1-implementation.md#hosted-database-setup). Do not rerun table-creation migrations that have already been applied, and do not reset the production database.
4. If only a grant is missing, restore only the privilege named by the audit. For example, a missing SELECT on transactions uses `grant select on public.transactions to authenticated;`. Profiles require `grant select, insert on public.profiles to authenticated;`. Investigate unexpected extra privileges before revoking them, since other deployed services may depend on them.
5. Verify profile setup, friend lookup and sync with signed-in test accounts. Confirm that a second account cannot read another user's private ledger. Applying the new undo/private-loan migration is a separate deployment step from this permissions notice.

Future table migrations should define required grants and RLS policies together. Changing default privileges affects future objects, so it does not repair a table that is already missing a grant.

The local PostgreSQL regression suite explicitly disables automatic table/sequence grants before applying all migrations, checks the permission matrix, exercises authenticated profile creation and friend discovery, and verifies that the audit catches a deliberately missing grant. This does not establish the hosted project's actual deployment state.

Sources: [Supabase rollout announcement](https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically), [Securing your API](https://supabase.com/docs/guides/api/securing-your-api).
