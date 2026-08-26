# Nirmalam Dhanam (निर्मलम् धनम्)

Nirmalam Dhanam is a local-first, privacy-centred personal finance app for Android. It combines encrypted on-device records, thoughtful spending controls, and a calmer financial view designed to support long-term decision-making.

## What you can do

- Unlock a local SQLCipher Room database with a PBKDF2-derived passphrase key.
- Track cash and bank activity with a Vyakti (payee), direction-neutral Varga (category), and optional private description.
- Set up cash, bank, credit, loan, retirement, and investment Khatas: PPF, EPF, NPS, superannuation, mutual funds/ETFs, direct stocks, and bullion.
- Start with optional India-oriented Varga and Vyakti suggestions for everyday merchants, banks, LPG/gas, electricity providers, telecom, travel, and common income sources.
- Check in dated investment cost and value balances for portfolio and net-worth tracking.
- Use **Prarambha** for true available cash, safe-to-spend today, cooling-tank decisions, local balance and portfolio charts, a seven-day Money Pulse chart, and a compact portfolio summary.
- Use **Vyavahara** for searchable money activity, account/category filters, inline modifications, reports, and a local-only Spend Map.
- Use **Nivesha** for investment asset CRUD, dated monthly cost/value check-ins, cost-vs-value charts, ABS/XIRR performance context, and locally suggested tracking benchmarks for recognised index funds, ETFs, equities, and gold holdings.
- Use **Sampada** for a live net-worth dashboard with assets, liabilities, allocation, and trend.
- Use **Vinyasa** for neurodiverse mode, currency, date format, privacy guidance, Varga, Vyakti, and About management.
- Optionally enable **Nirmalam AI** in Vinyasa with your own OpenAI-compatible provider, model, and API key. It offers only fixed insight buttons—not chat—and sends a minimised aggregate summary only after you press one.
- Pause wants purchases in a 48-hour cooling tank, with haptic confirmation actions.
- Export and restore encrypted `.ndf` backups from Vinyasa. An `.ndf` is a compressed container of the SQLCipher database and non-sensitive format metadata; the database passphrase is verified before export and required again for restore.
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

This repository contains Version 1.3.0 release-candidate work: local encrypted finance tracking, income/expense reports, investment and net-worth dashboards, portfolio and balance charts, encrypted backup/restore controls, optional BYOL Nirmalam AI insights, and Material 3 screens. The Room schema is currently version 11. The public manifest targets Android API 36 and does not declare restricted SMS permissions. The app now supports a device-locale default date format with explicit override options from Vinyasa. Before publishing, configure Play App Signing, host the included privacy-policy page at a stable HTTPS URL, complete the Play Console declarations, and complete final accessibility/device testing.

## Local build

1. Install JDK 17 and set `JAVA_HOME`.
2. Install Android SDK Platform 36 through Android Studio.
3. Run:

   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```

## Security notes

The user's passphrase must be collected only after device authentication and supplied directly to the database unlock/backup operation. Do not log passphrases, transaction data, or raw SMS content. `.ndf` files remain encrypted, but should still be stored only in locations the user trusts. Nirmalam AI is disabled by default; its BYOL key is protected with Android Keystore and is never included in Room, JSON exports, or `.ndf` backups. Enabling it sends an aggregate summary to the provider the user chooses only when a fixed insight button is pressed.

Benchmark suggestions are local metadata only: recognised holding names can be associated with an appropriate Indian market index (or a domestic gold benchmark), but the app does not fetch prices or transmit portfolio data. Confirm the scheme's official benchmark from its AMC or issuer before relying on a comparison.

See [NDF_SPEC.md](NDF_SPEC.md) for the encrypted backup and JSON interchange contracts.

## Release verification

[RELEASE_DATA_SAFETY_TEST.md](RELEASE_DATA_SAFETY_TEST.md) covers the required real-database regression check for removing starter data, backup passphrase recovery, and empty-state checks. Run it against a copy of a real pre-release database; do not use a customer's only database as a test fixture.

[QA_MATRIX.md](QA_MATRIX.md) is the required API 26/29/33/35/36, accessibility, rotation, tablet, neurodiverse-mode, and large-dataset release matrix.

## Release administration

- [Privacy policy](https://www.nirmalamgroup.in/home/privacypolicy) — public HTTPS URL linked from Vinyasa for the Play listing and in-app disclosure. The repository also keeps the current source copy at [docs/privacy-policy.html](docs/privacy-policy.html).
- [Play Console submission checklist](PLAY_CONSOLE_SUBMISSION.md) — Data safety, app-content, and content-rating preparation.
- Upload signing is configured locally with a 4,096-bit RSA key at `release-signing/nirmalam-upload-key.jks` and ignored `keystore.properties`. Back up both into a password manager or encrypted vault; never commit either file. Enable Play App Signing when uploading the first AAB.
