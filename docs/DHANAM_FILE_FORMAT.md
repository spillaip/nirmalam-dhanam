# `.dhanam` portable file format

## Purpose

`.dhanam` is the stable interchange boundary between Nirmalam Dhanam clients. Android may use an encrypted Room/SQLCipher database internally; Python and future desktop/web tools operate on the portable document instead of depending on Android encryption keys or Room internals.

## Current envelope

```json
{
  "format": "nirmalam-dhanam",
  "formatVersion": 2,
  "exportedAtEpochMs": 0,
  "currencyCode": "INR",
  "monetaryUnit": "paise",
  "accounts": [],
  "categories": [],
  "payees": [],
  "transactions": [],
  "investmentBalances": [],
  "netWorthSnapshots": [],
  "goals": [],
  "goalAllocations": []
}
```

## Conventions

- Monetary values are signed/unsigned integer **paise** fields, never binary floating point.
- Transaction timestamps are Unix epoch milliseconds.
- Investment/net-worth dates are Unix epoch days.
- Identifiers are strings and are stable across export/import.
- Readers must reject an unsupported future `formatVersion` rather than guessing.
- Readers may ignore unknown additive fields from supported versions.
- Android import validates the whole document first and applies it in one Room transaction.
- `.dhanam` is **not encrypted**. It is an explicit portable export. Use `.ndf` for encrypted backup/restore.

## Investment balance derivation

For each asset, sort check-ins by date then creation time.

For a current check-in `C` and previous check-in `P`:

```text
cost_delta       = C.current_cost  - P.current_cost
value_delta      = C.current_value - P.current_value
net_contribution = cost_delta
market_movement  = value_delta - cost_delta
```

For the first check-in, previous cost/value are treated as zero. Therefore its contribution is current cost and market movement is current value minus current cost.

Any edit or deletion of an earlier check-in must re-derive all later check-ins for that asset.

## Compatibility policy

- Current writer: format version 2.
- Android reader: versions 1 through 2.
- Python reader: validates the same format name/unit and rejects unsupported future versions.
- New fields should normally be additive and receive explicit defaults.
- Breaking semantic changes require a new format version and a documented migration.
