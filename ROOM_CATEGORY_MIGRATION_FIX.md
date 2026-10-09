# Room category migration repair

The historical `MIGRATION_3_4` created `categories` before the `priority` and `nature`
columns existed. Those columns later became required by `CategoryEntity`, so a database
that upgraded through the old migration chain could fail Room schema validation.

## Repair

- Database schema version is 13.
- `MIGRATION_12_13` checks `PRAGMA table_info(categories)` before adding each missing
  column.
- Missing `priority` is added as `TEXT NOT NULL DEFAULT 'NEED'`.
- Missing `nature` is added as `TEXT NOT NULL DEFAULT 'VARIABLE'`.
- The checks are intentionally conditional because a v12 database created fresh by Room
  may already contain these columns, while a v12 database produced by historical
  migrations may not.
- `CategoryEntity` declares matching Room defaults with `@ColumnInfo`.
- The production migration chain includes `MIGRATION_12_13`.
- `CategoryMigrationTest` covers both historical-v12 and fresh-like-v12 shapes and checks
  that existing category values are preserved.

Do not edit `MIGRATION_3_4` to add these columns: released historical migrations should
remain immutable. Repair old schemas with a new forward migration instead.
