# Nirmalam Dhanam (निर्मलम् धनम्)

Nirmalam Dhanam is a local-first, privacy-centred personal finance app for Android. It combines encrypted on-device records, thoughtful spending controls, and a calmer financial view designed to support long-term decision-making.

## What you can do

- Unlock a local SQLCipher Room database with a PBKDF2-derived passphrase key.
- Track cash and bank activity with a payee, category, and optional private description.
- Set up cash, credit, retirement, and investment accounts: PPF, EPF, NPS, superannuation, mutual funds, and equity.
- Check in dated investment cost and value balances for portfolio and net-worth tracking.
- Use the **Home** screen for true available cash, safe-to-spend today, cooling-tank decisions, and quick entry.
- Use the **Ledger** for a searchable money pulse, income/spend filters, account-scoped results, day-grouped entries, and a local-only Spend Map.
- Use the **Portfolio** screen for valuation history, allocation targets, net worth, and long-term trend context.
- Use **Settings** for neurodiverse mode and privacy guidance.
- Pause wants purchases in a 48-hour cooling tank, with haptic confirmation actions.
- Parse supported Indian financial SMS alerts entirely in memory; raw SMS text is never written to disk.
- Export and import encrypted `.ndf` backups through Android's system file picker. An `.ndf` is a compressed container of the SQLCipher database and non-sensitive format metadata.

## Screen map

| Screen | Purpose |
| --- | --- |
| Home | Daily cashflow, safe-to-spend, quick income/expense entry, and cooling-tank decisions. |
| Ledger | Searchable transaction history with an account dropdown, income/spend filters, Money Pulse, and Spend Map. |
| Portfolio | Investment check-ins, allocation, performance context, and net-worth history. |
| Settings | Neurodiverse presentation preference and local-data privacy guidance. |

## Technology

Kotlin, Coroutines, StateFlow, KotlinX Serialization, Jetpack Compose Material 3, Room, SQLCipher, and the Android Storage Access Framework.

## Project status

This repository contains the Version 1.1.0 MVP: encrypted local unlock, core money entry, Ledger, portfolio tracking, and Material 3 screens are runnable. Before a production release, add biometric-assisted unlock, runtime SMS consent UX, comprehensive automated tests, accessibility testing, and CI signing configuration.

## Local build

1. Install JDK 17 and set `JAVA_HOME`.
2. Install Android SDK Platform 35 through Android Studio.
3. Run:

   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```

## Security notes

The user's passphrase must be collected only after device authentication and supplied directly to the database unlock/backup operation. Do not log passphrases, transaction data, or raw SMS content. `.ndf` files remain encrypted, but should still be stored only in locations the user trusts.
