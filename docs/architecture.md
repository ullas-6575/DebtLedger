# Implementation boundary

The first milestone follows the brief's recommended sequence: build the normal finance app before adding cloud and blockchain coordination.

## Android

Compose screens → LedgerViewModel → LedgerRepository → Room.

`domain/Ledger.kt` is independent of Android. Money uses integer poisha and checked addition. The ledger is append-only; balances are derived from records. Repository writes read the existing ledger, normalize a person's name, validate, and insert inside one Room transaction so competing saves cannot both spend the same funds. Repayments cannot cross zero. Opposing new loans can cross zero because they represent new lending or borrowing.

| Record | Selected account | Person |
| --- | --- | --- |
| Income | +amount | — |
| Expense | −amount | — |
| Transfer | −amount, destination +amount | — |
| Loan given | −amount | +amount |
| Loan taken | +amount | −amount |
| Repayment received | +amount | −amount |
| Repayment paid | −amount | +amount |

Category totals include only Expense. Transfers, loans and repayments are excluded. Account and person balances are all-time; category reports use the current calendar month in the device timezone. Dates currently use the record creation time.

## Supabase stage

Supabase Auth (email/password) and PostgREST are connected through a pinned Kotlin SDK. BuildConfig reads the HTTPS URL and publishable key from local.properties or environment variables. Publishable keys are sent in `apikey`; authenticated user JWTs supply the bearer token. The app never embeds privileged server keys.

The original Room database remains the offline ledger. Signed-in accounts use separate database files scoped to the project URL and user ID. Session changes switch the observed repository; operations serialize account switches, saves, and sync. SDK session storage preserves login across restarts. Network refresh failures retain access to the stored user's local ledger; explicit sign-out clears the local session.

Manual Sync posts the ordered local ledger to `sync_ledger(records jsonb)`. The SQL function and insert trigger run as the caller under RLS. A transaction-level advisory lock per owner serializes writes. The trigger normalizes person names, enforces account sufficiency/repayment bounds, rejects reused IDs with different content, and skips identical retries. Any validation error rolls back the entire batch. Direct inserts have the same trigger protection.

The RPC returns a complete owner snapshot inside a JSON object to avoid PostgREST row truncation. The app checks ownership and local IDs before merging it into Room. Upload offline records explicitly includes the original device ledger in the same exchange; regular Sync never copies another ledger implicitly.

There is no WorkManager/background retry or incremental paging yet. Failed requests retain local records for a manual retry, while repeated UUIDs prevent duplicates after a lost response. Cross-device accounting conflicts are rejected and require reconciliation; a conflict-resolution editor remains future work. See [Supabase setup](supabase-setup.md) for the SQL deployment still required on the configured hosted project.

Friends and shared agreements need their own tables and policies. Do not reuse private person names as authenticated identities. Pending shared proposals must not affect financial balances. Apply accepted shared records once, transactionally; record the reciprocal account effects according to each participant's selected account.

## Blockchain stage

The contract stores only salted payload hashes, participant addresses, status and timestamps. `msg.sender` authenticates the on-chain approvals. A proposal signals the proposer agrees; confirmation requires the counterparty. Original payload hashes remain immutable even when superseded. Rejected hashes cannot be proposed again; generate a new salted version.

Before connecting Android, implement a versioned deterministic payload format, random salt, Keccak-256, chain/contract domain separation, proof storage and RPC event verification. A backend must verify event receipts, chain, contract, participants and hash, handle finality/reorganizations and apply each confirmed agreement at most once. Wallet address ownership needs verified challenge signatures. Use wallet software to sign; never store a user's wallet private key in the app.

There is no token, payable function, payment gateway or automatic transfer of actual funds. Mutual blockchain acknowledgment does not prove physical payment happened.

## Validation

JVM tests exercise decimal parsing, loan polarity, partial repayments, overpayment rejection, transfers, category totals, insufficient funds, and net-worth conservation. Solidity tests exercise participants, duplicate hashes, rejection, corrections and competing corrections. The contract suite requires Foundry. Local PostgreSQL tests cover policies, atomicity, idempotence, and concurrent spending; hosted authentication and sync still need verification after schema deployment. UI/device behavior needs emulator or phone testing.
