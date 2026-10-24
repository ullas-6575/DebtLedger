# Supabase setup

**October 30, 2026 Data API notice:** LedgerX already grants the app access explicitly in its migrations. See the [permissions guide and read-only hosted audit](data-api-permissions.md) before changing grants in response to Supabase's notice. For the current app's full schema, follow the [v1 deployment instructions](v1-implementation.md#hosted-database-setup); the original setup below describes version 0.2.0.

LedgerX 0.2.0 connects to Supabase through the Kotlin SDK's Auth and PostgREST modules. It uses the private `movements` table, not the `todos` table from the Supabase quickstart.

## Current project status

The configured project is `https://qjoenlyqeifxptyccqdd.supabase.co`. A read-only check on 10 October 2026 confirmed that the provided publishable key reaches Auth and email sign-up is enabled. The Data API returned `PGRST205` for `public.movements`: the ledger table was not available in the schema cache at that check.

The Android integration and SQL are prepared. **The SQL still needs to be run in the hosted project before sync will work.** A publishable key cannot create tables or install database functions. No hosted ledger records or test users were created during development.

## 1. Configure the Android build

In `android/local.properties`, alongside your `sdk.dir`, set:

```properties
SUPABASE_URL=https://qjoenlyqeifxptyccqdd.supabase.co
SUPABASE_PUBLISHABLE_KEY=your_publishable_key
```

The supplied URL and publishable key have already been saved in this workspace's Git-ignored file. [The example configuration](../android/local.properties.example) is safe to commit. Environment variables with the same names override the file for CI builds. Rebuild after changing either setting.

Only publishable keys belong in the app. They are included in the APK and are not server secrets; access to records depends on a user's Auth session and database policies. Do not add a database password, secret key, or service-role key to Android configuration.

A build without the two settings can still use its offline ledger. Its Cloud dialog explains that configuration is missing.

## 2. Create the ledger schema

For this project's initial setup:

1. Open the project's [SQL Editor](https://supabase.com/dashboard/project/qjoenlyqeifxptyccqdd/sql/new).
2. Copy the complete contents of [`001_private_ledger.sql`](../supabase/migrations/001_private_ledger.sql).
3. Run the SQL once.
4. Check that `public.profiles`, `public.movements`, and `public.sync_ledger(jsonb)` exist and that row-level security is enabled on both tables.

This is an initial migration, not an idempotent reset script. If an earlier copy of the migration has already been applied elsewhere, do not rerun its `create table` statements. Apply the new section starting at `-- Server posting rules for Android's manual sync` to that existing schema once, after checking its structure. Do not drop existing tables or disable RLS to fix setup errors.

The script grants authenticated users access to their own rows and prevents anonymous access. It adds a validation trigger and a transaction-based synchronization function. Both run with the caller's permissions; neither bypasses row-level security. The profiles table is reserved for later profile features; current email authentication and ledger sync do not require a profile row.

## 3. Check authentication settings

Email/password authentication must be enabled. Use your Supabase Auth email settings to configure email delivery and a valid Site URL for confirmation links. With confirmation enabled, the user creates an account, confirms the email in a browser, returns to LedgerX, and signs in with their email and password. This version does not handle OAuth callbacks, deep links, CAPTCHA challenges, password recovery, or MFA flows.

No existing database password is required in the app. The person names on loans remain private labels and are not matched to Auth users.

## 4. Build and use the app

From `android/`:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew installDebug
```

Then:

1. Open **Cloud** in the top bar.
2. Choose **Create account**, confirm the email if required, and **Sign in**.
3. The app opens that user's separate ledger. Tap **Sync** to download existing cloud records.
4. To copy the original offline ledger into this account, choose **Upload offline records**. Check the signed-in email before doing so. The original offline ledger remains on this device.
5. Add records normally. Records save locally first; tap **Cloud → Sync** to upload changes and receive other devices' records.
6. On another device, sign into the same account and tap **Sync**.
7. **Sign out** returns to the original offline ledger. Each user's local records stay in a separate database and are shown again when that account signs in.

Sync is manual. There is no background worker, automatic upload after saving, or live subscription. Repeating a successful upload is safe: matching UUIDs are treated as retries, not new transactions.

## How sync works

```text
Email/password → Supabase Auth → user access token
                                     ↓
Local user ledger → sync_ledger RPC → owner lock + validation → Supabase movements
        ↑                                                        ↓
        └──────────── complete owner snapshot, merged locally ───┘
```

The app sends amounts as integer poisha and timestamps in ISO 8601 format. The server obtains ownership from `auth.uid()`, validates the batch in order, and serializes changes for the same owner. The validation trigger also protects direct Data API inserts. A failed record rolls back the entire upload, and local data is preserved.

A response contains a JSON object with the complete owner snapshot, avoiding the Data API's default row limit. The app verifies ownership and that no local IDs disappeared, then merges the snapshot in a Room transaction. Saves, account switches, and sync are coordinated so a pending request cannot write into another user's local ledger.

The pinned SDK is `supabase-kt 2.6.1`, with Ktor `2.3.12`, Kotlin `2.0.10`, and the matching Compose/serialization compiler plugins. SDK 2.x uses the `gotrue-kt` artifact and `io.github.jan.supabase.gotrue` package for Auth. A compatibility hook keeps the new publishable key in the `apikey` header and removes it from the bearer header; signed-in user tokens remain in `Authorization`. Tests cover this behavior. See [Supabase Kotlin installation](https://supabase.com/docs/reference/kotlin/installing) and [API key guidance](https://supabase.com/docs/guides/getting-started/api-keys).

## Failures and limits

- **Missing table/function:** apply the complete initial SQL and retry. A key alone does not provision the schema.
- **Sign-in rejected:** check credentials, email confirmation, and email-provider settings.
- **Network failure:** local records remain available. Reconnect and retry Sync. If the server committed but the response was lost, the same IDs prevent duplicate uploads.
- **Conflicting offline spending or repayments:** the server rejects a batch that would overdraw an account or overpay a loan. It keeps the existing cloud ledger and the app retains the pending local records. This version has no conflict-resolution editor; reconcile the underlying records before retrying. Do not add fictional income to bypass a conflict.
- **Changing phones/uninstalling:** only successfully synced records can be recovered by signing in and syncing. The original offline ledger and unsynced records are device-only.
- **Sign-out:** the app clears its local session even when the network sign-out request fails. Local cached ledger files remain app-private on the device. Sessions use SDK local storage and are not protected by an additional app lock.
- **Large ledgers:** each sync sends the full local ledger and returns the full cloud snapshot. Incremental synchronization and background retry remain future work.

## Verification

Android tests cover domain accounting, exact serialization, timestamp conversion, publishable-key headers, and preservation of user bearer tokens. The database regression runner creates its own temporary database on a local PostgreSQL server, checks RLS, validation, atomic rollback, concurrent spending, and complete snapshots, then drops only that test database:

```bash
PGHOST=/path/to/local/socket PGPORT=5432 python3 supabase/tests/test_sync.py
```

It requires `psql`, `createdb`, `dropdb`, and a local test administrator able to create databases/roles. It models Supabase's `auth.uid()` using the request JWT subject. Do not run it against the hosted production project. Hosted authenticated sync and phone interaction still require verification after applying the SQL.
