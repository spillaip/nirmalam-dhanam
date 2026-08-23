# Nirmalam Dhanam (निर्मलम् धनम्)

Nirmalam Dhanam is a local-first, privacy-centred personal finance app for Android. It combines encrypted on-device records, thoughtful spending controls, and a calmer financial view designed to support long-term decision-making.

## What you can do

- Unlock a local SQLCipher Room database with a PBKDF2-derived passphrase key.
- Track cash and bank activity with a Vyakti (payee), direction-neutral Varga (category), and optional private description.
- Set up cash, bank, credit, loan, retirement, and investment Khatas: PPF, EPF, NPS, superannuation, mutual funds/ETFs, direct stocks, and bullion.
- Start with optional India-oriented Varga and Vyakti suggestions for everyday merchants, banks, LPG/gas, electricity providers, telecom, travel, and common income sources.
- Check in dated investment cost and value balances for portfolio and net-worth tracking.
- Use **Prarambha** for true available cash, safe-to-spend today, cooling-tank decisions, a local seven-day Money Pulse chart, and a compact portfolio summary.
- Use **Vyavahara** for searchable money activity, account/category filters, inline modifications, reports, and a local-only Spend Map.
- Use **Nivesha** for investment asset CRUD, dated monthly cost/value check-ins, allocation targets, and performance context.
- Use **Sampada** for a live net-worth dashboard with assets, liabilities, allocation, and trend.
- Use **Vinyasa** for neurodiverse mode, currency, privacy guidance, Varga, and Vyakti management.
- Pause wants purchases in a 48-hour cooling tank, with haptic confirmation actions.
- Parse supported Indian financial SMS alerts entirely in memory; raw SMS text is never written to disk.
- Use the encrypted `.ndf` backup engine for local export/import integration. An `.ndf` is a compressed container of the SQLCipher database and non-sensitive format metadata; Vinyasa backup controls are the next UI integration step.
- Produce a versioned, read-only JSON interchange report for local Python/Java analysis; values use integer paise to avoid precision loss.

## Screen map

| Screen | Purpose |
| --- | --- |
| Prarambha | Available cash, daily decision metrics, a seven-day Money Pulse chart, cooling-tank decisions, and portfolio summary. |
| Vyavahara | Searchable income/expense history, filters, reports, and transaction entry. |
| Nivesha | Investment asset management, monthly balances, allocation, and performance history. |
| Sampada | Net worth: current assets, liabilities, allocation, and trend. |
| Vinyasa | Preferences, Varga/Vyakti setup, currency, guide, and privacy disclosure. |

## Technology

Kotlin, Coroutines, StateFlow, KotlinX Serialization, Jetpack Compose Material 3, Room, SQLCipher, and the Android Storage Access Framework.

## Project status

This repository contains Version 1.2.0: local encrypted finance tracking, income/expense reports, investment and net-worth dashboards, and Material 3 screens. Before publishing, target Android API 36, configure Play App Signing, complete accessibility/device testing, publish a privacy-policy URL, accurately complete the Data safety form, and remove the restricted SMS permission unless the app meets Google Play's permitted-use requirements.

## Local build

1. Install JDK 17 and set `JAVA_HOME`.
2. Install Android SDK Platform 35 through Android Studio.
3. Run:

   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```

## Security notes

The user's passphrase must be collected only after device authentication and supplied directly to the database unlock/backup operation. Do not log passphrases, transaction data, or raw SMS content. `.ndf` files remain encrypted, but should still be stored only in locations the user trusts.

See [NDF_SPEC.md](NDF_SPEC.md) for the encrypted backup and JSON interchange contracts.
