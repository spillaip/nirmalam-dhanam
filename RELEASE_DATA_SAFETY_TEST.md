# Data safety release verification

Run this checklist on a physical device or emulator with a restored copy of a real existing Nirmalam Dhanam database. Never use the only copy of a person's data for this verification.

## Remove starter data

1. Before upgrading, create one personal daily Khata, one personal Vyavahara, one personal Varga, one personal Vyakti, and one investment check-in. Record their names and counts.
2. Upgrade to this build and unlock the existing database. Confirm the personal records are still present.
3. In **Vinyasa → Starter data**, choose **Remove starter data** and confirm the warning.
4. Verify only IDs supplied by the app disappeared: demo Khatas and records, suggested Varga/Vyakti, demo investment snapshots, and demo net-worth snapshots.
5. Verify every personal Khata, Vyavahara, Varga, Vyakti, and investment check-in remains with its original values.
6. Force-stop, reopen, and unlock the app twice. Confirm starter data does not return. Add one new personal record, reopen again, and confirm it remains.

The implementation performs the cleanup in one Room transaction, deletes only known starter ID prefixes, and persists `starterDataRemoved=true` before the transaction finishes. Seeding is skipped whenever that persisted flag is set.

## Backups and interchange

1. Export JSON interchange from Vinyasa. Confirm the plaintext warning appears before Android's file picker.
2. Export `.ndf` with the correct database passphrase. Confirm the file is created.
3. Try exporting with an incorrect passphrase. Confirm no file is created and the app explains that the database passphrase could not be verified.
4. Try importing the `.ndf` with an incorrect passphrase. Confirm the recovery message says the current local data was left unchanged; unlock and check that data.
5. Import with the correct passphrase. Confirm the app returns to the lock screen, then unlock it using that backup's passphrase and verify restored records.

## Empty states

On a fresh local database after starter data is removed, verify:

- Prarambha shows **No Khatas yet** and distinguishes daily Khatas, liabilities, and investment holdings.
- Vyavahara shows its no-transaction state.
- Nivesha summary shows **No Nivesha yet**.
- Reports show **No report data for this period**.
