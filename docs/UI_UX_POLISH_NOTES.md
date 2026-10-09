# UI/UX polish notes

This pass is intentionally presentation-only. It does not change Room schema, portable `.dhanam` schema, finance calculations, or release version.

## Single-line policy

Single-line + ellipsis is used for compact UI elements where wrapping damages layout: primary navigation labels, screen titles/subtitles, action labels, chips, tabs, dropdown items, text-field labels/placeholders, list/card titles, and key money metrics.

Explanatory paragraphs and safety/confirmation copy remain multi-line so information is not silently truncated.

## Operation simplification

- Home portfolio: investment-only add menu; recurring actions are **Check-in** (primary) and **Contribute** (secondary).
- Transactions: top bar reduced to **Search** plus one overflow menu containing **Filters** and **Reports**.
- Investments: **Check-in** is the primary recurring action; **Add asset** is secondary.
- Insights: compact action wording such as **Why?**, **Story**, **Details**, **Link/Update**, while the underlying detail remains available.
- More → Data & interoperability: three focused tools only — CSV transactions, `.dhanam` portable data, and encrypted `.ndf` backup/restore.

Keep `versionCode = 17`, `versionName = "1.7.0"` during the current feature-development pass.
