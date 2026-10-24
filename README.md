# LedgerX / DebtLedger

**24 October 2026 update:** Added name-only private loans, direct repayment recording by lenders, and transaction undo after sync validation. See [usage and migration instructions](docs/v1-implementation.md#24-october-update-private-loans-lender-receipts-and-undo). The new SQL migration must be applied to the hosted database before these cloud commands can sync.

**Development update (10 October 2026):** The workspace now includes the Room v2 overhaul, automatic sync, monthly summaries with 30-day Trash, friends, and shared-loan approvals. See [implementation and deployment notes](docs/v1-implementation.md) for setup, verification, and the optional blockchain limitations. The hosted migrations still need deployment. The v0.2.0 documentation below describes the previous release.

LedgerX is a native Android app for tracking personal money, spending, and loans in Bangladeshi taka (BDT). It shows the money in your accounts, what other people owe you, and what you owe them.

The current version is **0.2.0**, built with Kotlin and Jetpack Compose. It supports an offline ledger plus optional email sign-in and manual Supabase synchronization. Offline use needs no login or network; cloud features require the [Supabase setup](docs/supabase-setup.md).

**New to the app? Read the [user manual](docs/user-manual.md)** for installation, first-time setup, and instructions for every record type.

## Current status

Status reviewed on **10 October 2026**, against the current source and recorded validation results.

| Area | Status |
| --- | --- |
| Android app | Version **0.2.0** implemented; debug APK built successfully. |
| Offline ledger | Income, expenses, transfers, loans, repayments, reports, and local persistence implemented. |
| Supabase integration | Email sign-up/sign-in, account isolation, manual Sync, and Upload offline records implemented in Android. |
| Hosted project | URL and publishable key configured locally. The last Auth probe succeeded; hosted ledger schema deployment is still unconfirmed. |
| Database setup | Initial SQL includes private tables, row-level security, posting validation, and the `sync_ledger` function. **25 local PostgreSQL checks passed.** |
| Android validation | **11 JVM tests passed**, with **0 failures and 0 errors**. Lint reported **0 errors and 10 warnings**. |
| End-to-end verification | Hosted sign-in/sync and phone/emulator interaction remain unverified. |
| Blockchain | Separate contract and tests supplied; no recorded deployment, test execution, or Android integration. |

**Next step for cloud use:** complete the [hosted database setup](docs/supabase-setup.md#2-create-the-ledger-schema), then verify sign-in and synchronization on a device. At the last recorded project check, `public.movements` was missing from the API schema cache. The publishable key alone does not create the schema. This documentation update does not represent a new live-project check.

## Contents

- [Current status](#current-status)
- [Current features](#current-features)
- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [Build and run](#build-and-run)
- [Project structure and technology](#project-structure-and-technology)
- [Data storage and privacy](#data-storage-and-privacy)
- [Supabase connection and blockchain foundation](#supabase-connection-and-blockchain-foundation)
- [Current limitations](#current-limitations)
- [Validation](#validation)
- [Documentation](#documentation)

## Current features

| Feature | Available behavior |
| --- | --- |
| Three accounts | Separate **Cash**, **bKash**, and **Bank** balances, each starting at zero. |
| Income | Record money received into a selected account. |
| Expenses | Record spending under **Eat**, **Payment**, or **Other**. |
| Transfers | Move recorded funds between two different accounts without changing total available money. |
| Loans given and taken | Track lending and borrowing by a person's name. |
| Repayments | Record partial or full repayments received or paid, limited to the outstanding balance. |
| Overview | Available money, net worth, account balances, amounts owed in both directions, current-month expense categories, and the five most recent records. |
| Money screen | Account balances, current-month income and expense totals, and category amounts with proportion bars. |
| Loans screen | People listed alphabetically with **Owes you**, **You owe**, or **Settled** status. |
| History | All saved records, newest first, including type, amount, account, date/time, and any person, category, destination, or note. |
| Entry assistance | Reuse an existing person's name; names are matched without case sensitivity and extra spaces are normalized. |
| Local persistence | Saved records remain available after closing and reopening the app. |
| Cloud account | Email sign-up/sign-in, separate local storage per account, and sign-out back to the original offline ledger. |
| Manual sync | Upload/download private records through Supabase; explicitly copy the original offline ledger with **Upload offline records**. Requires the database setup. |
| Validation | Positive amounts, two-decimal precision, account sufficiency, repayment limits, and name/note length checks. |

The app records real-world movements. Selecting bKash or Bank does not connect to a financial service or send money. A private loan record does not notify the other person or establish their approval.

## How it works

### Records determine balances

Every saved entry adds a record to the local ledger. The app calculates balances from these records; there is no separate balance-editing screen.

| Record type | Effect on selected account | Effect on person's signed balance |
| --- | --- | --- |
| Income | Increases | None |
| Expense | Decreases | None |
| Transfer | Decreases; destination increases equally | None |
| Loan given | Decreases | Increases |
| Loan taken | Increases | Decreases |
| Repayment received | Increases | Decreases toward zero |
| Repayment paid | Decreases | Increases toward zero |

A **positive** person balance means they owe you. A **negative** balance means you owe them. Zero means settled. Lending and borrowing involving the same person combine into one net balance, even when different accounts are used.

For example, lending Rahim ৳1,000 and later borrowing ৳400 from Rahim leaves a net balance of **৳600 owed to you**. Repayments use this net balance, rather than an individual loan agreement.

### Dashboard calculations

```text
Available money = Cash + bKash + Bank
Owed to you      = Sum of positive person balances
You owe         = Absolute total of negative person balances
Net worth       = Available money + Owed to you - You owe
```

Account and loan balances cover all saved records. Monthly income totals include only **Income** records; monthly expense totals include only **Expense** records. Transfers, loans, and repayments are excluded from both totals.

Monthly reports use the current calendar month in the device's timezone. A record receives its date and time when it is created; there is no date picker or historical-month selector. Opening funds entered as Income are included in that month's income total.

### Saving and validation

The entry form passes records through the ViewModel to the repository. Inside one Room database transaction, the repository reads existing records, normalizes the person's name, trims the note, validates the entry, and saves it. The UI observes database changes and recalculates its displays.

Money is stored as integer **poisha**: ৳1.00 is 100 poisha. Accounting uses integer arithmetic rather than floating-point amounts.

- Amounts must be greater than zero, have at most two decimal places, and be no more than **৳1,000,000,000 per record**.
- Expenses, transfers, loans given, and repayments paid require enough money in the selected account.
- A transfer requires different source and destination accounts.
- Repayments require an existing balance in the correct direction and cannot exceed it.
- Names are required for loans and repayments and allow up to 80 characters after normalization. Notes are optional and allow up to 300 characters after trimming.

See the [architecture notes](docs/architecture.md) for implementation details.

## Quick start

### Use the offline ledger

1. Install and open **LedgerX** on an Android 8.0 or newer device using the build instructions below.
2. Tap **Add record**, change **Type** to **Income**, choose **Cash**, enter your existing cash amount, and save. Repeat for bKash and Bank if they have opening funds.
3. Record purchases as **Expense**, selecting the account used and a category.
4. Use **Loan given** when you lend money and **Loan taken** when you borrow. Enter the person's name.
5. Use **Repayment received** or **Repayment paid** when money is returned. Choose the existing person to keep their balance together.
6. Use **Transfer** when moving money between your own accounts.
7. Review **Overview**, **Money**, **Loans**, and **History** to check records and balances.

Review entries before saving: the current app does not support editing or deleting records. The [user manual](docs/user-manual.md) includes detailed procedures, a worked example, and troubleshooting.

### Use a cloud account

After completing the [Supabase setup](docs/supabase-setup.md):

1. Open **Cloud** in the top bar, then **Create account** or **Sign in**. Confirm your email first if the project requires it.
2. Tap **Sync** to load the signed-in account's records. Each account has its own local ledger.
3. To copy the original offline ledger into that account, check the displayed email and tap **Upload offline records**. The original stays on the device.
4. Add records normally, then tap **Cloud → Sync** to upload changes and download records from other devices.
5. On another device, sign into the same account and tap **Sync**. **Sign out** returns to the original offline ledger.

Saving a record and signing in do not automatically synchronize it. Sync is manual; only successfully synced records can be recovered on another device. Repeating an upload with the same record IDs does not create duplicates.

## Build and run

### Requirements

- JDK 17.
- Android SDK 34 and an Android development environment, such as Android Studio.
- An Android 8.0+ device or emulator (API 26 or newer).
- Internet access for initial Gradle and dependency downloads. The installed app supports offline record keeping; authentication and synchronization need a connection.

The repository includes a Gradle wrapper, so a separate Gradle installation is unnecessary. Supabase configuration is optional for offline use; see the [cloud setup guide](docs/supabase-setup.md) to enable it. Foundry is not required for Android.

### Android Studio

1. Open the repository's **`android/` directory** as the project.
2. Set the Gradle JDK to **17** and install Android SDK platform **34** if needed.
3. Let Gradle sync finish.
4. Select an emulator or a connected Android device with USB debugging enabled.
5. Run the **app** configuration.

If the SDK location is not detected, create `android/local.properties` with your actual SDK path:

```properties
sdk.dir=/absolute/path/to/your/Android/Sdk
```

This machine-specific file is ignored by version control.

### Optional Supabase configuration

For cloud-enabled builds, add these settings to the same `android/local.properties` file:

```properties
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_your_key
```

The supplied project settings are already stored locally in this workspace. For another checkout, use [local.properties.example](android/local.properties.example) and your own project values. Environment variables with the same names override the file. Rebuild after changing configuration; never include a service-role key or database password in the app.

Configuration and schema deployment are separate steps. Follow the [setup guide](docs/supabase-setup.md) to apply the SQL and configure email authentication.

### Command line

From the repository root on Linux or macOS:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

On Windows, run `gradlew.bat testDebugUnitTest assembleDebug` from `android/`.

The debug APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

With a device or emulator connected, install the debug app from `android/`:

```bash
./gradlew installDebug
```

Alternatively, transfer the generated APK to your phone and open it to install. See [installation in the user manual](docs/user-manual.md#install-and-open-the-app). This procedure creates a development APK; release signing and store publishing are outside the current setup.

## Project structure and technology

```text
android/
  local.properties.example     Optional cloud configuration template
  app/gradle.lockfile           Locked application dependencies
  app/src/main/java/com/ledgerx/
    MainActivity.kt             App entry point and dependency setup
    ui/LedgerApp.kt             Compose screens and record-entry form
    ui/LedgerViewModel.kt        State, account switching, save/sync handling
    ui/CloudDialog.kt           Email sign-in and manual sync
    domain/Ledger.kt            Record models, money parsing, accounting rules
    data/LedgerDatabase.kt      Room database, DAO, and repository
    data/SupabaseLedger.kt      Supabase client and cloud record mapping
  app/src/test/java/com/ledgerx/domain/LedgerTest.kt
  app/src/test/java/com/ledgerx/data/SupabaseLedgerTest.kt
contracts/
  src/SharedAgreementRegistry.sol
  test/SharedAgreementRegistry.t.sol
  foundry.toml
supabase/
  migrations/001_private_ledger.sql
  tests/test_sync.py
docs/
  user-manual.md
  supabase-setup.md
  architecture.md
  validation.md
```

| Component | Repository configuration |
| --- | --- |
| Android package/application ID | `com.ledgerx` |
| App version | `0.2.0` (version code `2`) |
| Kotlin / JVM target | Kotlin `2.0.10` / Java `17` |
| Android SDK | Minimum `26`, compile and target `34` |
| Build tools | Android Gradle Plugin `8.5.1`, Gradle wrapper `8.7` |
| UI | Jetpack Compose BOM `2024.06.00`, Material 3, Compose compiler plugin `2.0.10` |
| Local storage | Room `2.6.1`; `ledgerx.db` for offline use, separate databases per project/user for cloud accounts |
| State | AndroidX ViewModel, Kotlin coroutines and Flow |
| Cloud | Supabase Kotlin BOM `2.6.1`, Auth (`gotrue-kt`), PostgREST, Ktor OkHttp `2.3.12` |
| Unit tests | JUnit `4.13.2` |
| Optional contract | Solidity `0.8.24`, Foundry |

Application dependencies are locked in [`android/app/gradle.lockfile`](android/app/gradle.lockfile). Version declarations and lockfiles should be updated together when intentionally changing dependencies.

## Data storage and privacy

Records are stored in app-private Room databases on the device. The original offline ledger stays separate from each signed-in user's ledger. Cloud records are sent to the configured Supabase project when you tap Sync or Upload offline records. Loan names remain private labels, with no phone-contact import or identity matching.

The local database has no separate application-level encryption or PIN/biometric lock. Android backup is disabled. **Uninstalling the app or clearing storage removes local records and the stored session.** Only successfully synced records can be recovered by signing in and syncing again; offline and pending records need separate safekeeping. File export/import is not implemented.

## Supabase connection and blockchain foundation

### Supabase connection

The app uses the supplied project URL and publishable key from Git-ignored `android/local.properties`. Open **Cloud** to create an account or sign in, then use **Sync** to exchange private ledger records. **Upload offline records** copies the original device ledger into the selected account without deleting the original.

[`001_private_ledger.sql`](supabase/migrations/001_private_ledger.sql) defines owner-isolated tables with row-level security, a validation trigger, and the atomic `sync_ledger` function. It validates balances and repayments on the server, serializes each owner's writes, and treats matching UUIDs as retries. Both the function and trigger use the caller's permissions.

**Hosted deployment remains unconfirmed.** The last recorded read-only check on 10 October 2026 reached Auth successfully, but returned HTTP 404 / `PGRST205` for the ledger table. Follow the [Supabase setup guide](docs/supabase-setup.md) to apply the initial SQL if it has not already been run, then verify authenticated sync. The schema passed 25 checks against a temporary local PostgreSQL database; those checks do not establish hosted deployment or end-to-end success.

Sync is manual and uses the full ledger. Background retries, automatic conflict resolution, friends, and shared approvals remain future work. Conflicting offline spending is rejected by the server and kept locally for reconciliation.

### Shared agreement contract

[`SharedAgreementRegistry.sol`](contracts/src/SharedAgreementRegistry.sol) is an EVM acknowledgment registry. A proposal represents the proposer's approval; its counterparty can confirm or reject it. Corrections require another approval and preserve the original hash while linking a replacement. Duplicate hashes and invalid participants are rejected.

The contract stores payload hashes, participant addresses, status, timestamps, and replacement references. It does not store amounts or person names as fields, hold funds, or transfer money. Salted canonical payload generation and verification still need to be implemented off-chain.

To run the separate contract tests with Foundry installed:

```bash
cd contracts
forge test
```

For local blockchain development, `anvil` starts a local EVM node; it does not deploy the contract automatically. The repository documents no contract deployment, and the Android app has no wallet connection or on-chain verification.

## Current limitations

- Android only; no web or iOS client.
- BDT only; account names and expense categories are fixed.
- No editing, deletion, undo, dedicated correction workflow, or backdating.
- No separate opening-balance or existing-debt import flow. Loans always affect an account, and opening funds entered as Income affect monthly income.
- No history search, filters, historical-month reports, or file export/import.
- No individual loan schedules, due dates, interest calculations, reminders, or notifications.
- No standalone contact creation, renaming, or removal; people come from loan and repayment records.
- No background sync, conflict-resolution editor, OAuth/MFA/password-recovery flow, friends, shared approvals, wallet signing, or blockchain proof verification in the app.

The [architecture notes](docs/architecture.md) describe the remaining cloud and blockchain integration work.

## Validation

Run the Android checks from `android/`:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The permanent domain suite covers decimal parsing, loan directions, partial repayments, overpayment rejection, transfers, expense categories, insufficient funds, and net-worth calculations.

Recorded results for version 0.2.0:

| Check | Result |
| --- | --- |
| Android unit tests | **11 passed**: seven accounting tests and four Supabase serialization/header tests; zero failures/errors. |
| Debug build | **Passed**; APK generated at the path above. |
| Android lint | **0 errors, 10 advisory warnings** concerning dependency/SDK freshness, backup compatibility, and KAPT usage. |
| Local database regression | **25 passed**, covering owner isolation, validation, atomic rollback, retries, concurrent spending, and complete snapshots beyond 1,000 records. |
| Hosted Auth probe | HTTP 200 at the last recorded check; email authentication and sign-ups enabled. |
| Hosted ledger probe | HTTP 404 / `PGRST205` at the last recorded check; schema setup requires confirmation. |
| Hosted authenticated sync, device UI, Solidity tests | Not yet verified. |

To repeat the database regression checks against a disposable **local** PostgreSQL server, from the repository root:

```bash
PGHOST=/path/to/local/socket PGPORT=5432 python3 supabase/tests/test_sync.py
```

The runner requires `psql`, `createdb`, `dropdb`, and a local administrator able to create databases/roles. It creates and removes its own test database; do not point it at the hosted project. See the [validation log](docs/validation.md) for scope and remaining manual checks. The results above come from the recorded integration run; tests were not rerun for this README-only update.

## Documentation

- [User manual](docs/user-manual.md): installation, screen guide, daily workflows, examples, and troubleshooting.
- [Supabase setup](docs/supabase-setup.md): project configuration, SQL deployment, sign-in, and sync.
- [Architecture](docs/architecture.md): accounting behavior and future integration boundaries.
- [Validation](docs/validation.md): recorded checks and remaining verification work.
