# Nirmalam Dhanam

Nirmalam Dhanam is a local-first Android personal-finance app built around two simple ideas:

- **Income and expenses are transactions.** Transactions can be imported/exported and reconciled.
- **Investments are assets with balance check-ins.** The user enters current cost and current value; Dhanam derives contributions/withdrawals, value change, and market movement from the prior check-in.

The app keeps its live database encrypted with Room + SQLCipher. A portable, versioned **`.dhanam`** JSON document is the user-owned interchange format shared with the companion Python package. Encrypted **`.ndf`** files remain the full backup/restore format.

## Android Studio setup

Requirements:

- JDK 17
- Android Studio with Android SDK 36
- Android Gradle Plugin 9.4.1
- Gradle 9.7.1

Open the repository root in Android Studio (the directory containing `settings.gradle.kts`), allow Gradle Sync to finish, and run the `app` configuration.

Command-line debug build:

```bash
./gradlew :app:assembleDebug
```

On Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

> The source bundle used for this repair did not contain the standard Gradle wrapper JAR/scripts. The included bootstrap scripts use a normal wrapper JAR if one is later generated, otherwise they bootstrap Gradle 9.7.1. In Android Studio, generate/commit the standard wrapper once Gradle is available (`gradle wrapper --gradle-version 9.7.1`).

## Primary navigation

The UI is intentionally reduced to five destinations:

1. **Home** — what changed, cash position, quick actions
2. **Transactions** — ledger, import/export, reconciliation
3. **Investments** — assets, cost/value check-ins, performance
4. **Insights** — health, explainable insights, goals, time machine, local copilot, timeline
5. **More** — settings, portable files, encrypted backup/restore, advanced options

## Portable `.dhanam` files

`.dhanam` is plaintext JSON designed for portability and automation. Money is stored as integer paise; dates use epoch milliseconds or epoch days. Do not use `.dhanam` when encryption-at-rest is required; use `.ndf` for encrypted backups.

See [`docs/DHANAM_FILE_FORMAT.md`](docs/DHANAM_FILE_FORMAT.md).

## Python package

The publishable package source is under `python/`.

Development test:

```bash
PYTHONPATH=python/src python -m unittest discover -s python/tests -v
```

Once build tooling and PyPI credentials are configured:

```bash
cd python
python -m build
python -m twine check dist/*
python -m twine upload dist/*
```

The package is intentionally not assigned an open-source license in its metadata until the repository owner explicitly chooses and adds a repository license.

## Data safety

Before testing migrations against valuable data, create an encrypted `.ndf` backup. Database schema is now version 14 and includes explicit migration/default handling for older installs.

See [`docs/IMPLEMENTATION_REPORT.md`](docs/IMPLEMENTATION_REPORT.md) for the repair and feature map.
