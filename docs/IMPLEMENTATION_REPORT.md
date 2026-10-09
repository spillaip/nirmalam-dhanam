# Implementation report

## Stabilization and cleanup

| Area | Resolution |
|---|---|
| Room migration gap | Database advanced through v13 for the category/goal repair and v14 for income-category metadata repair. `MIGRATION_12_13` conditionally repairs missing category `priority`/`nature`, adds investment delta fields, and creates goals/allocations; `MIGRATION_13_14` corrects known system income directions. |
| NDF schema mismatch | Backup validation no longer hardcodes database version 2; manifest/validation use supported Room schema versions. |
| Cooling tank expiry | Holding rows remain visible until explicitly confirmed or discarded instead of disappearing after expiry while still affecting balances. |
| 100-row reporting bug | Full-ledger DAO streams/queries now feed reports and analytics. The limited recent list remains only for lightweight recent UI. |
| UTC-midnight bug | Today boundaries use the device time zone and refresh across local midnight. |
| CSV corruption/partial import | Added proper quoted CSV parsing/export, full validation, atomic import, and reconciliation classification. |
| AI context mismatch | AI presets now receive only the structured aggregate evidence each preset requires; unavailable presets are gated and unsupported tax assumptions are explicitly refused. |
| Cooling category logic | WANT/NEED metadata drives envelope behavior instead of the literal category name `Shopping`. |
| Seed direction bug | Income seed categories are CREDIT rather than DEBIT. |
| Savings/emergency creation | Account setup now exposes Daily, Savings, and Emergency purposes. |
| Investment cash-flow analytics | Investment transfers use the INVESTMENT envelope and are excluded from expense/burn totals. |
| Kotlin test mismatch | Kotlin test dependency aligned with Kotlin 2.4.20. |
| Legacy AGP Kotlin flags | Removed old built-in-Kotlin opt-outs and migrated Gradle configuration toward AGP 9.4 built-in Kotlin behavior. |
| Dependency debt | AndroidX/Compose/Room/SQLCipher/coroutines/serialization versions refreshed in `app/build.gradle.kts`. |
| Monolithic MainActivity | Split large settings, transaction, investment and insight surfaces into dedicated Kotlin files; MainActivity reduced substantially. |
| Dead SMS code | Removed disconnected SMS receiver/dispatcher code and related permission surface. |
| Store graphics in APK | Play Store graphics moved to `store-assets`; the installed app now uses adaptive launcher icons with Android 13+ monochrome theming. |
| Signing example | `keystore.properties.example` includes store path/password, alias, and key password. |

## Ten product features implemented

### 1. Automatic investment delta engine

`InvestmentDeltaEngine.kt` derives contribution/withdrawal, value delta and market movement from current cost/current value versus the previous check-in. Editing/deleting history re-derives later rows.

### 2. Unified financial event timeline

`FinancialTimeline.kt` merges transactions and investment check-ins into one chronological narrative, including explicit investment-transfer events.

### 3. Smart reconciliation

`TransactionReconciliationEngine.kt` classifies imports as NEW, PROBABLE_DUPLICATE, POSSIBLE_MATCH or CONFLICT. CSV import uses the classifier before atomic insertion.

### 4. Explainable money intelligence

`FinancialInsights.kt` produces deterministic findings with evidence transaction/snapshot IDs so the UI can answer “Why am I seeing this?”.

### 5. Transparent financial health

`FinancialHealth.kt` calculates component-level health values rather than one opaque score. The Insights UI exposes the contributing components.

### 6. Goal-linked money

Room now contains `GoalEntity` and `GoalAllocationEntity`. Goals reuse existing account/asset balances through allocation basis points rather than duplicating money.

### 7. “What changed?” home dashboard

The app stores the prior-open timestamp and shows changes since that point on Home, driven by actual ledger and investment history.

### 8. Time-machine reconstruction

`FinancialTimeMachine.kt` reconstructs liquid balances, liabilities, investment values, net worth, income and expenses as of a selected historical date. Accounts created after the requested date are excluded.

### 9. Portable file + Python ecosystem

`DhanamPortableFile.kt` implements versioned `.dhanam` read/write. `python/` contains a PyPI-ready package with account/transaction/asset/goal CRUD, investment delta derivation, validation, net-worth calculation and CLI commands.

### 10. Private local copilot

`LocalFinancialCopilot.kt` answers supported structured questions locally and converts write intents into a preview that must be explicitly confirmed before records change.

## Navigation and visual system

The visible information architecture is now Home / Transactions / Investments / Insights / More. A stable Nirmalam green Material 3 light/dark palette replaces device-dependent dynamic colors so the product maintains a consistent identity.

## Verification performed in this repair environment

- Python package unit tests pass (5/5), including the public `Dhanam(path)` open/create workflow.
- The rebuilt Python wheel installs and passes a save/reopen/net-worth smoke test.
- Pure Kotlin finance/domain integration compilation passes at JVM 17 with lightweight Room annotation stubs.
- Cross-fix checks pass for local-day boundaries, WANT cooling, Savings/Emergency transaction eligibility, investment deltas, CSV round-trips, reconciliation, and ledgers beyond 100 rows.
- Room v12→v14 SQL is independently exercised against both historical-v12 and fresh-like-v12 category schemas.
- Static cumulative-integrity checks, Android XML parsing, secret scanning, launcher/store-art validation, and shell bootstrap syntax pass.

A complete Android `assembleDebug` cannot be executed in this container because no Android SDK is installed. Open the project in Android Studio, let SDK/Gradle dependencies sync, then run `./gradlew :app:assembleDebug` as the final device/toolchain verification.

## Recommended release gate

1. Generate/commit the standard Gradle wrapper JAR/scripts from a trusted Gradle 9.7.1 installation.
2. Run `./gradlew clean test :app:assembleDebug`.
3. Run Room migration tests against copies of real v12 and older databases.
4. Exercise `.ndf`, `.dhanam`, and CSV round-trips on disposable test data.
5. Test local-midnight rollover and holding-tank expiry on a device/emulator.
6. Create an encrypted backup before installing over any valuable production database.
