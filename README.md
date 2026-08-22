# Nirmalam Dhanam (निर्मलम् धनम्)

Nirmalam Dhanam is a local-first, privacy-centred personal finance app for Android. It combines encrypted on-device records, thoughtful spending controls, and a calmer financial view designed to support long-term decision-making.

## Highlights

- SQLCipher-encrypted Room database with PBKDF2-derived keys.
- Reactive cash, envelope, daily safe-to-spend, and autonomy-runway calculations.
- In-memory parsing of Indian financial SMS alerts; raw SMS text is never stored.
- Wants-purchase cooling tank with a 48-hour review period.
- Labour-hour cost conversion and long-term portfolio de-emphasis.
- Adaptive Compose tablet layout, haptic cooldown actions, and anti-panic portfolio reveal.
- Optional neurodiverse mode with lower visual density, one primary daily focus, reduced trend motion, and unchanged financial calculations.
- Encrypted `.ndf` backup export/import through Android's system file picker. An `.ndf` is a compressed container of the SQLCipher database and non-sensitive format metadata.

## Technology

Kotlin, Coroutines, StateFlow, KotlinX Serialization, Jetpack Compose Material 3, Room, SQLCipher, and the Android Storage Access Framework.

## Project status

This repository contains the Version 1.1.0 architectural and UI foundation. Before a release build, complete the application entry/unlock flow, dependency injection graph, runtime SMS permission consent, automated tests, and CI signing configuration.

## Local build

1. Install JDK 17 and set `JAVA_HOME`.
2. Install Android SDK Platform 35 through Android Studio.
3. Run:

   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```

## Security notes

The user's passphrase must be collected only after device authentication and supplied directly to the database unlock/backup operation. Do not log passphrases, transaction data, or raw SMS content. `.ndf` files remain encrypted, but should still be stored only in locations the user trusts.
