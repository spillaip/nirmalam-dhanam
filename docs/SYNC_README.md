# Nirmalam Dhanam v1.7 consolidated sync — features #1–#5 + UI/UX polish

This source tree is cumulative. Copy/merge `app/src/main/java/com/nirmalamgroup/nirmalamdhanam/` into the existing Android project; do not apply earlier feature ZIPs afterward.

Included feature state:

- #1 Automatic Investment Balance-Delta Engine v2
- #2 Financial Event Timeline v2 / Story of my money
- #3 Smart Reconciliation v2
  - NEW / PROBABLE DUPLICATE / POSSIBLE MATCH / CONFLICT / ALREADY RECONCILED
  - persistent local source fingerprints and reconciliation timestamps
  - merchant/payee + description-aware deterministic matching
  - CSV round-trip transaction IDs/provenance
- #4 Explainable Money Intelligence v2
  - 30-day category spending compared with the preceding 90-day monthly average
  - deterministic reasoning steps
  - actual supporting transactions and investment check-ins shown under “Why am I seeing this?”
- #5 Financial Health Graph v2
  - transparent health components: Cash flow, Savings discipline, Emergency reserve, Investment diversification, Portfolio alignment, Liability resilience
  - visible relationship model connecting Income, Spending, Savings, Emergency reserve, Investments, Liabilities and Net worth


## UI/UX polish pass

This baseline also includes a compact UI pass focused on small-screen stability and simpler operations:

- primary navigation, screen titles, action labels, chips, tabs, dropdown items and key money metrics are single-line with ellipsis instead of wrapping
- explanatory body copy remains multi-line so important guidance is not truncated
- Transactions now uses Search + one overflow menu for Filters/Reports instead of three competing top-bar actions
- Investments gives recurring **Check-in** primary emphasis and keeps **Add asset** secondary
- Home portfolio operations are shortened to **Check-in** / **Contribute**, and the portfolio add menu contains only investment types
- More → Data & interoperability is reduced to three explicit tools: CSV, `.dhanam`, and encrypted `.ndf`, each with short Export/Import or Backup/Restore actions
- verbose action labels such as “Why am I seeing this?”, “Open story”, and “Update allocation” are shortened while their detailed content remains available on demand

Database note: schema is now **15**. Migration 14→15 adds nullable transaction provenance fields (`sourceFingerprint`, `sourceKind`, `reconciledAtEpochMs`) plus an index. No existing ledger amount/balance is rewritten.

Portable `.dhanam` format is now **v3**, retaining backwards import compatibility for v1/v2 documents through defaulted provenance fields.

Keep the app release at `versionCode = 17`, `versionName = "1.7.0"` during this feature-development pass.

Local integration gates:

```powershell
.\gradlew.bat :app:assembleDebug --warning-mode all
.\gradlew.bat test
```
