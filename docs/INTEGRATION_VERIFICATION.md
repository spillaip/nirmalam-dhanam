# Cumulative integration verification

This document records the final integration pass after the 16 requested fixes were combined.

## Cross-fix corrections made during integration

- CASH/BANK Khatas classified as `SAVINGS` or `EMERGENCY` are now accepted everywhere a normal ledger account is expected: manual transaction entry, CSV import destination, local-copilot transaction preview, and investment contribution source selection.
- Synthetic demo balances and transactions are seeded only in debug builds. Release builds seed reference categories/payees but start the user's financial ledger without fake money data.
- The cumulative Gradle verification guard was made comment-aware so explanatory source comments cannot create false build failures.
- The Python API now supports the intended `Dhanam("my-finances.dhanam")` workflow: existing files open and validate; missing paths start a new document that is atomically written on `save()`.

## Verification completed in this environment

- 39/39 cumulative source/resource integrity checks passed.
- All Android manifest/resource XML files parsed successfully.
- No likely embedded API key/password secret was detected by the release-tree scan.
- Pure Kotlin finance/domain compilation and a cross-feature JVM integration smoke test passed at JVM target 17.
- Room v12→v14 migration SQL passed independent SQLite simulations for both:
  - a historical v12 database missing `categories.priority` / `categories.nature`;
  - a fresh-like v12 database where those columns already exist and have user values.
- Python package tests: 5/5 passed.
- Rebuilt `nirmalam_dhanam-0.1.0-py3-none-any.whl` installed successfully and passed save/reopen/net-worth smoke testing.
- `gradlew` shell syntax passed.

## Android build limitation here

A complete AGP/Room/KSP/Compose `:app:assembleDebug` cannot be run in this container because the Android SDK is unavailable and outbound access to the Gradle distribution host is blocked. This is an environment limitation, not a successful Android-build claim.

## Final Android Studio release gate

On a normal development machine with Android SDK 36 and internet/cached dependencies:

```powershell
.\gradlew.bat clean test :app:assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

Run the instrumentation command with an API 26+ emulator/device. For migration confidence before production rollout, also install a copy of a real older database on a disposable test device and verify unlock, automatic migration, `.ndf` export/import, `.dhanam` round-trip, CSV round-trip, cooling-tank confirmation, and a local-midnight rollover.
