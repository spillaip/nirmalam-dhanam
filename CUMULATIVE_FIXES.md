# Cumulative fixes

This working tree is the integrated cumulative patch set requested by the user. The 16 fixes below are retained together and followed by cross-fix hardening/verification.

## Applied

1. Room category schema repair
   - Introduced database schema version 13 for the category-column repair; current cumulative schema is version 14.
   - `MIGRATION_12_13` conditionally adds `categories.priority` and `categories.nature` for historical upgrade paths.
   - Entity defaults align with migration defaults.
   - Regression migration coverage added.

2. NDF backup validator schema-version repair
   - Removed hard-coded validation schema version.
   - Current export validation tracks `NirmalamDatabase.SCHEMA_VERSION`.
   - Imported backups validate against the manifest and reject actual DB version mismatches without mutating `user_version`.
   - Upgrade/downgrade mismatch coverage added.

3. Cooling-tank persistence and expiry repair
   - `observeHoldingTank()` returns every row with `isHoldingTank = 1`, including already-expired rows.
   - Expired rows remain visible and excluded from balances until explicitly confirmed or discarded.
   - Confirmation is enforced at DAO level and cannot occur before `coolDownExpiryEpochMs`.
   - Cooldown card now advances its own clock while open and enables confirmation at the actual expiry instant.
   - Destructive action renamed from `Release` to `Discard`.
   - Instrumentation coverage added for expired visibility, future visibility, successful expired confirmation, and rejection of early confirmation.
4. Full-ledger reporting / analytics repair
   - `observeRecent(limit = 100)` is retained only as a lightweight Home/autocomplete feed.
   - `observeAll()` is the authoritative live ledger in `MvpFinanceState.allTransactions`.
   - Reports, period comparisons, trends, search, explainable insights, health score, timeline, time-machine, local copilot, and Nirmalam AI summary consume the full ledger rather than the 100-row feed.
   - `getBetween(start, end)` remains available for bounded full-ledger reporting/export operations.
   - Instrumentation regression coverage now inserts 125/150 transactions and proves the recent feed caps at 100 while authoritative analytics queries return every row and full totals.


5. Local-calendar "Today" boundary repair
   - Replaced UTC/epoch-day assumptions with `LocalDayClock`, which derives start/end from the device's current `ZoneId` and local calendar date.
   - `Safe to spend today` and `todaySpentPaise` now query `[local midnight, next local midnight)`.
   - The active day window is re-evaluated while the app remains open, so local midnight, manual date changes, and timezone changes do not require re-unlocking the database.
   - Rechecks occur at least once per minute and wake just after the next local midnight when close to the boundary.
   - Day length is timezone-aware (including 23/25-hour DST days) rather than hard-coded to 86,400,000 ms.
   - Unit coverage added for Asia/Kolkata midnight boundaries, a DST transition day, and boundary refresh timing.

## 6. Standards-compliant, atomic CSV import/export

- Replaced ad-hoc CSV parsing with `CsvCodec`, a strict RFC-4180-style state-machine parser/writer.
- Export correctly escapes commas, double-quotes, CR/LF, and embedded newlines; doubled quotes round-trip correctly.
- Export emits CRLF record separators for interoperable CSV output.
- Malformed quoting (unterminated quotes, quotes inside unquoted fields, trailing characters after a closing quote) is rejected before any database write.
- All transaction rows are parsed and validated before the Room write transaction begins.
- Categories, payees, and accepted transactions are written in one `database.withTransaction` block; any exception rolls back the complete import.
- Supporting categories/payees are now derived only from accepted rows, so rejected duplicate/conflict rows cannot leave orphan metadata behind.
- Import failures are surfaced as `CsvImportResult.Failure` with an explicit "No changes were committed" message instead of appearing successful.
- Added `CsvCodecTest` regression coverage for commas, doubled quotes, embedded LF/CRLF, trailing newline behavior, and malformed quoting.

## 7. AI preset data-contract and evidence gating repair

- Replaced the one-size-fits-all `buildNirmalamAiSummary()` request path with `prepareNirmalamAiContext()`, which builds the minimum aggregate evidence required by the selected preset.
- Portfolio Drift now receives current asset-class values, calculated current allocation percentages, recorded target percentages, and exact Rupee target gaps; it is disabled unless all active investments have current check-ins and targets total 100%.
- Surplus Router now receives current-month income, consumption expense, arithmetic surplus, and the same measured target gaps; it is framed as a mechanical illustration rather than investment advice.
- Tax-Shield now receives current Indian financial-year PPF/EPF/NPS snapshot-derived positive cost deltas, withdrawals, and check-in counts. It explicitly distinguishes these from verified tax-qualified contributions and is disabled when current-FY evidence is missing.
- Net Worth Quality receives liquid assets, recorded liabilities, portfolio value, arithmetic net worth, liquidity/solvency ratios, latest contribution delta, and latest market movement.
- Emergency Runway receives available cash/reserves after recorded credit liabilities plus a trailing-90-day consumption burn rate; it is disabled if the evidence window cannot support a runway calculation.
- Token Health is intentionally disabled because the current schema lacks structured asset-backing, liquidity/haircut, and regulatory-classification evidence; the model is never asked to invent a score.
- The AI settings UI shows per-preset readiness and missing-data reasons, and unavailable presets cannot issue provider requests. The ViewModel repeats the availability check before any network call.
- Preset prompts were tightened to prohibit unsupported claims and recommendation language. Raw payees, descriptions, account names/IDs, and transaction text remain excluded.
- Added `NirmalamAiContextTest` coverage for portfolio allocation evidence, tax-year evidence gating, and Token Health refusal. Pure Kotlin context compilation and representative assertions pass.

## 8. Category-priority-driven cooling policy

- Cooling eligibility is now driven by `CategoryPriority.WANT`, never by the literal category label `Shopping` and never solely by a caller-supplied `EnvelopeType`.
- `CoolDownTankInterceptorUseCase` accepts the resolved category priority and derives the transaction envelope itself: WANT -> `EnvelopeType.WANTS`, NEED -> `EnvelopeType.NEEDS`; credits remain unenveloped and investment transfers retain `EnvelopeType.INVESTMENT`.
- Every WANT category above the configured impulse threshold enters the 48-hour cooling tank, including Travel, Entertainment, Quick commerce, Home & family, Gifts & transfers, and user-defined WANT categories.
- `RoomFinanceRepository.saveTransaction()` resolves category metadata from Room before applying the cooling policy, so repository callers cannot accidentally bypass the rule by omitting or mis-setting `envelopeType`.
- Transaction creation and editing both re-run the category-priority policy. Editing an already-held WANT transaction preserves its original cooldown expiry rather than restarting the 48-hour timer.
- NEED categories do not enter the cooling tank even if stale/caller-supplied data incorrectly marks the transaction envelope as WANTS.
- CSV bookkeeping imports derive WANT/NEED envelopes from existing category metadata but intentionally do not put historical bulk-import rows into the behavioral cooling tank.
- Added `CoolDownTankCategoryPriorityTest` coverage for multiple WANT category names, priority overriding stale envelope data, threshold behavior, credits, investment transfers, and preservation of an existing cooldown expiry.
- Pure Kotlin/JVM 17 policy verification passes for Shopping, Travel, Entertainment, Quick commerce, NEED override, and preserved expiry scenarios.

## 9. Correct income category direction metadata

- Fresh starter data classifies `Salary & wages`, `Freelance & business`, `Interest & dividends`, and `Refunds & cashback` as `TransactionDirection.CREDIT`; expense-oriented starter categories remain `DEBIT`.
- Bumped the Room database to schema version 14 with `MIGRATION_13_14`, a data-only migration that repairs historically seeded system income categories that were incorrectly stored as `DEBIT`.
- The migration is intentionally scoped to `isSystem = 1` and the four known starter income category names, so user-created categories are never reclassified by the migration.
- Existing expense system categories are left unchanged, and custom/user categories retain their stored direction.
- Added instrumentation regression coverage proving system income categories are repaired while system expenses and user categories remain untouched.
- Because the schema version constant is shared by NDF validation, current backup export/validation automatically advances to database schema version 14 without reintroducing a hard-coded backup version.

## 10. Savings and emergency Khata creation/editing

- CASH and BANK Khatas expose an explicit Purpose selector with `Daily`, `Savings`, and `Emergency`; `Daily` remains the safe default for new accounts.
- Added `AccountRolePolicy` as the single source of truth for product-to-role mapping. CASH/BANK preserve the selected role, CREDIT_CARD/LOAN always resolve to `CREDIT`, and investment products always resolve to `INVESTMENT`.
- The ViewModel uses the same policy for both create and update operations, preventing stale or invalid caller values from assigning an impossible role.
- Khata management now allows an existing CASH/BANK account to be reclassified among `SPENDING`, `SAVINGS`, and `EMERGENCY` instead of making the role an irreversible creation-time choice.
- Khata cards display their accounting role alongside the product type, and setup guidance explains how Savings/Emergency roles affect reserve/runway reporting.
- Existing report/health/net-worth logic already consumes `AccountKind.SAVINGS` and `AccountKind.EMERGENCY`, so newly created or reclassified accounts immediately participate in reserve and liquidity calculations.
- Added `AccountRolePolicyTest` coverage for selectable cash roles, invalid-role fallback, fixed liability mapping, fixed investment mapping, and product eligibility.
- Pure Kotlin/JVM 17 verification passes for BANK->SAVINGS, CASH->EMERGENCY, invalid cash-role fallback, LOAN->CREDIT, and MUTUAL_FUNDS->INVESTMENT.

## 11. Kotlin test dependency aligned with project Kotlin version

- Updated `kotlin-test-junit` from the stale `2.2.10` line to `2.4.20`, matching the Kotlin Gradle plugin/Compose/serialization toolchain selected by the project.
- Kept the version explicit because Gradle's `kotlin(...)` dependency helper does not itself guarantee selection of the Android project's KGP version when no dependency version is supplied.
- Verified there are no remaining references to `kotlin-test-junit:2.2.10` or Kotlin `2.2.10` in the cumulative project.


## 12. AGP 9.4 built-in Kotlin and new DSL migration

- The project now relies on AGP 9.4's default built-in Kotlin support and new Android DSL; the global opt-outs `android.builtInKotlin=false` and `android.newDsl=false` are absent.
- `org.jetbrains.kotlin.android` / `kotlin-android` is not applied anywhere. Kotlin source compilation is owned by AGP built-in Kotlin.
- No `android.kotlinOptions {}`, `kotlin.sourceSets {}`, `applicationVariants`, `libraryVariants`, or `variantFilter` legacy APIs remain in active Gradle build logic.
- Java/Kotlin bytecode target remains Java 17 through `android.compileOptions`; built-in Kotlin inherits `jvmTarget` from `targetCompatibility`, per AGP's built-in Kotlin behavior.
- The root `buildscript` KGP 2.4.20 classpath is intentionally retained: AGP documents this as the supported way to select a KGP version newer than AGP's default runtime KGP while still using built-in Kotlin. Compose and serialization compiler plugins remain on the same 2.4.20 toolchain.
- Added `verifyModernAgpConfiguration`, a build-time verification task that fails if the project reintroduces the two opt-out properties, the legacy Kotlin Android plugin, `kotlinOptions`, or the old variant APIs.
- Every Android `preBuild` depends on that verification task, so future configuration drift is caught before compilation rather than hidden by warning-suppression flags.
- `gradle.properties` documents the intentional modern-mode requirement and contains no AGP legacy-warning suppression switches.

## 13. Modern AndroidX / Compose dependency baseline

**Issue:** The app previously referenced a September 2024 Compose BOM plus old Activity, Lifecycle, and Core releases.

**Fix:** Standardized the Android UI/runtime stack on the current stable production line and kept Compose artifacts versionless behind a single BOM declaration:

- Compose BOM: `2026.09.00`
- `androidx.activity:activity-compose`: `1.13.0`
- `androidx.lifecycle:lifecycle-*`: `2.11.0`
- `androidx.core:core-ktx`: `1.19.1`
- Room: `2.8.5`
- AndroidX SQLite / SQLite Framework: `2.7.1`

The same Compose BOM object is reused for application and instrumentation-test dependencies so Compose UI/test artifacts cannot drift independently. No alpha, beta, RC, or stale 2024 dependency declarations remain in the Gradle files.

**Compatibility:** The project remains on AGP 9.4.1, Kotlin 2.4.20, Java 17, compileSdk/targetSdk 36. Lifecycle 2.11.0 requires a modern Compose/AGP baseline, which this project satisfies. A static scan found no old `2024.09`, Activity `1.9.2`, Lifecycle `2.8.6`, Core `1.13.1`, SQLite `2.7.0`, or pre-release dependency declarations.

## 14. MainActivity monolith split into app, presentation, navigation, and persistence components

- Reduced `MainActivity.kt` from roughly 1,250 lines / 96 KB in the cumulative tree to a 15-line Android launcher whose only responsibility is edge-to-edge setup and `setContent { NirmalamMvpApp() }`.
- Moved app theme/root composition and unlock UI to `DhanamApp.kt`.
- Moved `MvpFinanceState`, package-level finance constants, and shared state helpers to `FinanceState.kt`.
- Moved `NirmalamMvpViewModel` out of the Activity into `NirmalamMvpViewModel.kt`.
- Extracted starter/reference database seeding into `FinanceDatabaseSeeder.kt` so demo/reference persistence no longer lives inside the ViewModel.
- Extracted investment-delta re-derivation and net-worth snapshot persistence into `InvestmentLedgerService.kt`.
- Replaced six independent Home navigation booleans with a single typed `DhanamDestination` state in `DhanamNavigation.kt`.
- Kept the Home dashboard orchestration in `HomeScreen.kt` and moved chart/visual helpers into `HomeCharts.kt`.
- Moved money/date/performance formatting and shared back-icon support into `UiSupport.kt`.
- Existing Transactions, Investments, Insights, and Settings surfaces remain in their dedicated source files rather than being folded back into the launcher Activity.
- Added `verifySourceArchitecture` to the root build and wired it into Android `preBuild`. It fails if `MainActivity.kt` grows beyond 60 lines or regains `AndroidViewModel`, Room/database, `@Composable`, or transaction orchestration responsibilities.
- Lightweight source-integrity checks confirm balanced Kotlin delimiters, one declaration each for the launcher/ViewModel/state/app root/Home/navigation types, and a passing equivalent of the architecture guard.

## 15. Remove dormant SMS ingestion surface

- Removed the unused SMS ingestion path from the cumulative source tree; there is no `SmsBroadcastReceiver` or `SmsIngestionDispatcher` implementation left in `app/src/main`.
- The manifest intentionally declares neither `RECEIVE_SMS` nor `READ_SMS` and contains no `SMS_RECEIVED` receiver registration.
- The app therefore does not request sensitive SMS permissions for functionality that is not wired to the transaction ledger.
- Added `verifyNoDeadSmsIngestion` to the root build and wired it into Android `preBuild`. It fails if the old receiver/dispatcher names, SMS receive intent, or SMS permissions are reintroduced without deliberately changing the guard as part of a complete feature implementation.
- If SMS-based transaction capture is added in the future, it must be implemented end-to-end (explicit opt-in, runtime permission flow where applicable, parsing/reconciliation, duplicate handling, privacy disclosure, and Play policy review) rather than as dormant receiver code.

## 16. Keep Play Store artwork out of the APK and use adaptive launcher icons

- Play Store-only assets live under `store-assets/`, outside `app/src/main/res`, so the 512x512 listing icon and 1024x500 feature graphic are not packaged in the APK/AAB.
- Replaced the legacy density-specific `mipmap-*/ic_launcher.png` and `ic_launcher_round.png` bitmaps with Android adaptive-icon XML resources in `mipmap-anydpi-v26`.
- Added separate density-aware transparent foreground layers and a branded pale-green background layer, allowing the launcher to apply circle, squircle, rounded-square, and OEM masks correctly.
- Added Android 13+ `mipmap-anydpi-v33` adaptive resources with a dedicated monochrome alpha layer so themed icons can tint the Nirmalam mark correctly.
- The original high-resolution launcher master is retained only under `branding/` as source artwork and is not an Android runtime resource.
- Added `store-assets/README.md` documenting the separation between Play listing artwork and installed-app launcher resources.
- Added `verifyNoPackagedStoreArtwork` and wired it into Android `preBuild`. It fails if Play Store graphic names appear under `res/`, legacy launcher PNGs reappear under `mipmap-*`, or the adaptive/themed launcher XML resources are removed.
- Static resource verification confirms no store-listing art exists under `app/src/main/res`, no legacy `mipmap-*/ic_launcher*.png` files remain, and the adaptive icon XML files are present for API 26+ and themed icons for API 33+.



## Integration hardening after cumulative fixes

- Savings and Emergency Khatas are now fully transactional, not merely creatable/reportable: manual entry, CSV target selection, and local copilot previews accept SPENDING/SAVINGS/EMERGENCY/CREDIT roles through `AccountRolePolicy.supportsTransactions()`.
- Investment contributions may source funds from SPENDING, SAVINGS, or EMERGENCY cash roles instead of failing when no Daily/SPENDING account exists.
- Transaction-role eligibility is centralized in `AccountRolePolicy`, preventing UI/ViewModel drift between account creation and ledger operations.

- Production-data safety: release builds seed only reference categories/payees; synthetic demo accounts, balances, transactions, and investment snapshots are now restricted to `BuildConfig.DEBUG` so a real user's reports never begin with fake financial data.

## Final integration verification

After the 16 requested fixes were combined, a final cross-fix pass added the following hardening:

- `SAVINGS` and `EMERGENCY` Khatas are accepted by manual transaction entry, CSV import targeting, local copilot previews, and investment funding-source selection through the centralized account-role policy.
- Synthetic financial demo records are debug-only; release ledgers start with user-owned financial data rather than fake balances/transactions.
- The Gradle cumulative-integrity guard ignores comments when checking for forbidden label-driven cooling logic, preventing a false `preBuild` failure caused by documentation text.
- The Python client now supports the intended `Dhanam("my-finances.dhanam")` constructor workflow while retaining explicit `Dhanam.open()` and `Dhanam.create()` APIs.
- The Python wheel was rebuilt after the API hardening and smoke-tested from the wheel itself.

Verification in this environment:

- 39/39 cumulative source/resource checks passed.
- Android manifest/resource XML parsing passed.
- Secret scan found no likely embedded API keys/passwords.
- Broad JVM-17 finance/domain integration smoke test passed.
- Independent SQLite v12→v14 migration simulations passed for historical and fresh-like v12 schemas.
- Python tests pass 5/5 under both pytest and unittest discovery.
- Portable `.dhanam` Python writer compatibility smoke test passed.
- Rebuilt PyPI wheel install/save/reopen/net-worth smoke test passed.
- `gradlew` shell syntax passed.

A full AGP/Room/KSP/Compose Android build still needs Android Studio/SDK 36 plus Gradle dependencies; this container has no Android SDK and cannot resolve the Gradle distribution host. See `docs/INTEGRATION_VERIFICATION.md` for the exact final release gate.
