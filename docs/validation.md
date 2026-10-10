# Validation

## Supabase integration — 10 October 2026

`./gradlew testDebugUnitTest assembleDebug lintDebug` passed for Android version 0.2.0. The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk`.

- **11 JVM tests passed, 0 failures, 0 errors:** seven existing accounting tests plus four Supabase tests covering exact money/timestamp serialization, server metadata, publishable-key request headers, and preservation of authenticated bearer tokens.
- **Lint: 0 errors, 10 advisory warnings:** dependency/target SDK freshness, backup configuration compatibility, and KAPT-to-KSP migration advice. The HTML report is generated at `android/app/build/reports/lint-results-debug.html`.
- **25 local PostgreSQL checks passed:** all seven record types; owner assignment; name normalization; retry idempotence; owner isolation; anonymous access rejection; update/delete rejection; funds and repayment limits; ID collisions; owner spoofing; invalid amounts/transfers/names; batch rollback; validation on direct inserts; opposing loans; full settlement; concurrent spending; and snapshots larger than 1,000 rows.

The database tests use a fresh temporary database on local PostgreSQL 15.19 with a minimal Auth fixture. The runner is [`supabase/tests/test_sync.py`](../supabase/tests/test_sync.py); it creates and removes only its own temporary database. Local checks do not replace testing against hosted Supabase Auth and PostgREST.

A read-only request with the supplied project URL and publishable key returned HTTP 200 from `/auth/v1/settings`, with email authentication enabled and sign-ups allowed. A zero-row probe of `/rest/v1/movements` returned HTTP 404 / `PGRST205` (table missing from the schema cache). No hosted tables, ledger entries, or test users were created. Apply the SQL using the [setup guide](supabase-setup.md), then verify authenticated sync on a device.

## Remaining manual verification

After database setup, verify sign-up, email confirmation, sign-in, sign-out, uploading the original offline ledger, downloading on a second device, retrying after a lost connection, and isolation when switching users. Also verify offline session restoration, rotation with a form open, every record type, and persistence after restarting. No phone/emulator interaction test was performed for this change.

The separate Solidity tests in `contracts/test/SharedAgreementRegistry.t.sol` remain unrun here; Foundry is required.

## Earlier offline milestone

Before cloud integration, a separate direct compilation of `domain/Ledger.kt` with Kotlin 1.9.24 passed 32 accounting checks. The original Android milestone had seven passing JVM tests and a successful debug build. These historical results are retained for context; the current build uses Kotlin 2.0.10 and the additional Supabase tests above.
