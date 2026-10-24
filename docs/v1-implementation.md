# v1 implementation and deployment

Implemented in the Android development build on 10 October 2026. This is source/build validation, not a production deployment or a phone acceptance test.

## 24 October update: private loans, lender receipts and undo

- **Add record → Give loan / Borrow → Name only · no app account** creates a private loan using the person's name. Names are trimmed, repeated spaces collapsed, and existing spelling reused without case sensitivity. No friendship or counterparty account is created. **Loan repayment → Name only** records repayment received or paid against that person's net outstanding balance, using the selected account.
- Lenders can select **Record repayment received** on a shared loan. The server confirms this receipt directly, checks borrower funds and outstanding debt, and updates both balances. Borrower-initiated repayment proposals and principal corrections still require counterparty approval. Shared receipts remain queued while offline and affect balances after sync.
- **History → Undo transaction** reverses income, expenses, transfers and private loan records. Original records still retained in Trash can be undone with their monthly summaries adjusted. Deleted originals cannot be undone. Shared loan details let the lender undo a confirmed repayment or an active loan with no repayments. Cancelled loans and undone shared repayments remain visible as activity.
- Undo queues on the device and takes effect after server validation. It cannot leave accounts negative or invalidate subsequent private repayments. Undo dependent records first, then use **Cloud sync → Retry sync** if the original undo was rejected. Deletion markers prevent an old device or offline import from restoring an undone private record.
- Room stays at version 3: the existing movements, command queue and deletion tables support these features. Deploy the new SQL migration below before using the new cloud commands. No hosted deployment is performed by these source changes.

## Implemented behavior

- Room schema version 2 adds transactions, monthly summaries, shared loans, loan events, categories, friendships, profile cache, a persistent command queue, and deletion markers. The 1→2 migration preserves the original movements table. Repository initialization imports original personal records and queues a single atomic migration of the old history.
- Home, Ledger, Add, Loans and Profile navigation includes income, expenses, transfers, loan proposals, repayments, custom categories, username discovery, friend requests, shared decisions, correction proposals, and sync status.
- Writes go to Room first. WorkManager retries queued commands with exponential backoff, up to three attempts per entry; Profile offers an explicit retry. Connectivity changes enqueue a sync; periodic polling covers missed Realtime notifications. Background tasks are scoped to the signed-in owner and cancelled on sign-out.
- Closed-month income/expenses are summarized by month, category **and account**. Summarizing by account preserves account balances after detail deletion. Transfers and all loan records remain individual. Month boundaries use Asia/Dhaka consistently on Android and PostgreSQL.
- Originals enter Trash for 30 days. Summaries retain their totals permanently. Unsynchronized originals are retained until the server acknowledges their summaries. Synced originals are purged locally on the next cleanup run after expiration; the server sweep runs hourly when Cron is enabled. Android background scheduling is approximate, particularly when the device is powered off.
- Deletion markers prevent stale devices from restoring expired originals. Cleanup removes the original Room/server movement backup and queued transaction payloads as well as the current transaction row. Command receipts contain no transaction details.
- Shared loan proposals require an accepted friendship and the other participant's approval. Proposals do not change balances. Lenders can directly record repayment receipts; borrower proposals require approval. Confirmed repayments check the outstanding amount and borrower funds. Corrections create a replacement loan with the prior repayments carried forward, preserve the superseded original, and reject other pending events against it.

## Existing private loans

Old records have names, not verified account IDs or mutual approvals. They remain private legacy records and retain their original account/debt effects. They are never automatically converted into confirmed shared agreements. Server `legacy_movements` and the local original movements table preserve this history; the Loans screen shows its private balances. Cloud-only legacy history is downloaded in the owner-scoped snapshot.

The Profile action **Import original offline ledger into this account** explicitly copies the original unsigned ledger into the selected account. Its history is imported in chronological order, with idempotent IDs. It does not silently attach the device's private history to every account. Existing private loans support the same name-only repayment and undo controls as newly entered private loans.

## Hosted database setup

Apply these files, in order, using an administrator connection or the Supabase SQL editor:

1. `supabase/migrations/001_private_ledger.sql` — only if the original schema is not already installed.
2. `supabase/migrations/002_v1_schema.sql`.
3. `supabase/migrations/003_shared_loans.sql`.
4. `supabase/migrations/20261024145351_undo_private_loans.sql`.

The new migrations have been tested against a disposable PostgreSQL 15 database. They have **not** been applied to the hosted project. Applying migration 003 intentionally disables the old `sync_ledger` write path; old app versions must be upgraded. Existing original data is migrated before that path is disabled.

Enable Supabase Cron before applying 003 to register the hourly `ledger-trash-cleanup` job automatically. If Cron is enabled afterward, register it as an administrator:

```sql
select cron.schedule('ledger-trash-cleanup', '17 * * * *', 'select private.ledger_sweep()');
```

Verify the job exists and succeeds in the Cron dashboard. Without Cron, cleanup still happens during authenticated snapshot pulls, but there is no server background deletion while every device is offline. Migration 003 adds the shared tables to `supabase_realtime` when that publication exists. Verify Realtime replication in the dashboard and test with two accounts.

Clients have SELECT-only access to new financial tables. Explicitly authorized command functions in the private schema validate ownership, participant roles, amount bounds and state transitions. Public wrappers are security invoker. The private functions that need cross-party writes use a fixed empty search path and explicit `auth.uid()` checks. One transaction advisory lock serializes financial mutations and sweeps; this favors correctness for the initial app over high write throughput.

## Optional blockchain

No registry deployment or wallet is configured. Ordinary ledger use does not require either.

The build includes deterministic UTF-8 payloads, Keccak-256, cryptographically random salts stored with Android Keystore-backed encryption, chain/contract domain separation, Amoy EIP-681 wallet requests, and confirmation receipt verification. Hashes are prepared only after mutual loan approval. Submission does not count as confirmation: verification checks chain ID 80002, successful receipt, configured registry address, the `Confirmed` event and payload hash, and at least 12 subsequent blocks. Failed/submitted checks retry during later sync runs.

To enable the proof controls, deploy `SharedAgreementRegistry.sol` on Amoy, arrange compatible external wallets for both parties, and set public `AMOY_REGISTRY` and HTTPS `AMOY_RPC_URL` values in `android/local.properties`, then rebuild. Never put wallet private keys in the app. The proposer opens a hash proposal in their wallet; the other wallet must approve that agreement on the registry before its confirmation transaction is supplied to the app.

Proof state is currently stored on the device and bound to the immutable accepted agreement (repayments do not invalidate it). It is not synchronized as an authoritative server proof. Wallet-to-user identity binding, cross-device salt exchange, hosted proof verification, and live wallet/Amoy acceptance tests remain necessary before offering blockchain proofs as a shared production feature. Contract wallet approvals alone do not establish the wallets' connection to the app's user IDs.

## Verification

24 October validation: **36 Android tests passed** (0 failures/errors/skips), **debug APK built**, **lint: 0 errors / 5 warnings**, and **69 PostgreSQL regression checks passed**, including the follow-up audit with automatic Data API grants disabled. Android validation used Gradle's offline mode. Lint warnings concern SDK/backup configuration and KAPT usage.

From `android/`:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The new Room tests exercise actual SQLite migration, preserving old loans, compaction idempotency, transfer exclusion, pending offline summary merging, Trash deadlines, balance preservation and deletion markers. Hash tests verify the Ethereum Keccak vector, domain separation, approval gating and Amoy request format. Existing accounting and Supabase transport tests remain included.

Against a disposable local PostgreSQL instance:

```bash
PGHOST=/path/to/local/socket PGPORT=55441 python3 supabase/tests/test_v1.py
```

The database suite covers both participants and outsiders, self-approval rejection, idempotent retries, lender receipts, repayments, corrections, private names, legacy imports, Trash expiry, undo of transactions and shared loans, summary preservation and stale-client resurrection prevention. It creates and removes its own test database.

Before release, verify two real accounts on devices: offline save/reconnect, process death, sign-out/account switching, Realtime approvals, duplicate retries, and the deployed Cron job. No hosted, emulator/phone, or live Amoy run was performed in this implementation session.

References checked for the integration: [Supabase changelog](https://supabase.com/changelog), [Kotlin Realtime](https://supabase.com/docs/reference/kotlin/subscribe), [Kotlin RPC](https://supabase.com/docs/reference/kotlin/rpc), [RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), and [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started). The project retains Supabase Kotlin 2.6.1; compilation validates the pinned API usage.
