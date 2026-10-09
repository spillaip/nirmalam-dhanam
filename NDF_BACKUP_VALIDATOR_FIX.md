# NDF backup validator schema-version fix

## Problem
The old NDF verifier created a `SupportSQLiteOpenHelper.Callback(2)` even after the Room database schema had advanced. Opening a newer SQLCipher database through that helper could be treated as a downgrade and make backup export/import validation fail.

## Fix
- The current database export path validates against `NirmalamDatabase.SCHEMA_VERSION` (currently 14).
- Imported backups validate against the schema version stored in their NDF manifest, after the manifest version is checked to be supported by the current app.
- `NdfDatabaseValidationCallback` now throws on both upgrade and downgrade callbacks. Validation therefore cannot silently rewrite `PRAGMA user_version` when the manifest and encrypted payload disagree.
- After opening, validation explicitly reads `PRAGMA user_version` and requires an exact match.
- Added instrumentation regression tests for exact match, older-version mismatch, and newer-version mismatch.

## Why not simply hard-code 13?
Older valid NDF backups may contain older Room schemas. They need to be authenticated and verified at their own recorded version first; after import, normal Room migrations can bring them forward. Hard-coding the current version into the staging validator risks mutating an older backup before Room gets a chance to migrate it properly.
