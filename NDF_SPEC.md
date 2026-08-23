# Nirmalam Dhanam Interchange (NDF)

NDF supports two distinct, user-initiated file contracts. Neither exposes a live database API.

## 1. Encrypted backup: `.ndf`

An `.ndf` file is a ZIP container for backup and full restore. The payload is an already encrypted SQLCipher database; ZIP compression does not add encryption.

| Entry | Required | Description |
| --- | --- | --- |
| `manifest.json` | Yes | UTF-8 NDF manifest |
| `database.sqlcipher` | Yes | SQLCipher-encrypted database payload |

NDF v2 manifests contain:

```json
{
  "format": "nirmalam-dhanam-backup",
  "formatVersion": 2,
  "exportedAtEpochMs": 0,
  "databaseFile": "database.sqlcipher",
  "kdf": "PBKDF2WithHmacSHA256",
  "kdfIterations": 210000,
  "kdfSaltBase64": "...",
  "databaseSha256": "lowercase hex SHA-256",
  "databaseSchemaVersion": 8
}
```

Importers must reject unexpected ZIP entries, duplicate entries, malformed manifests, invalid KDF metadata, oversized payloads, and a v2 digest mismatch. NDF v1 files remain importable for backward compatibility but do not carry a payload digest.

External tools should treat full database access as an advanced compatibility path, not as the primary integration surface. They must never persist, log, or transmit the user's passphrase or derived key.

## 2. Read-only analysis: JSON interchange

`nirmalam-dhanam-interchange` v1 is an explicit, plaintext JSON export for Python, Java, R, or spreadsheet pipelines. It is read-only: JSON is never accepted as an import source.

All monetary fields end in `Paise` and are signed 64-bit integers. This avoids locale and floating-point conversion errors. Dates use either Unix milliseconds (`EpochMs`) or `LocalDate.toEpochDay()` (`EpochDay`). Enums are uppercase strings matching their Android names.

Top-level fields are:

```json
{
  "format": "nirmalam-dhanam-interchange",
  "formatVersion": 1,
  "exportedAtEpochMs": 0,
  "currencyCode": "INR",
  "monetaryUnit": "paise",
  "accounts": [],
  "categories": [],
  "payees": [],
  "transactions": [],
  "investmentBalances": [],
  "netWorthSnapshots": []
}
```

The JSON report can contain sensitive financial information. Its destination must be selected by the user and tools should prefer local processing.

## Compatibility rules

- Reject an unknown major format version.
- Ignore unknown JSON fields when reading a future compatible version.
- Preserve record IDs if a tool only analyses data.
- Use `.ndf` for encrypted backup/restore; do not attempt to recreate SQLCipher files from JSON.
- Never add network endpoints as a substitute for user-selected export/import.
