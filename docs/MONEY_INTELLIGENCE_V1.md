# Dhanam Money Intelligence v1

This change adds five local-first, deterministic features without adding a Room migration.

1. **Money Compass** — prioritises up to three review items from existing Dhanam facts. It never writes data.
2. **Spending Pace** — compares month-to-date non-investment spending with an elapsed-month projection and recent monthly history.
3. **Recurring Money Radar** — detects stable weekly/monthly inflow and outflow patterns from ledger timing and amount consistency.
4. **Reserve Runway** — compares Savings/Emergency balances with a 90-day NEED-spending burn rate.
5. **Portfolio Concentration Lens** — shows largest holding, largest asset class and target-allocation drift from latest investment check-ins.

## UI and navigation

- Bottom navigation labels are shortened to **Home / Activity / Wealth / Insights / More**.
- Insights is split into **Overview / Plan / Explore** rather than one long mixed-purpose scroll.
- Overview uses progressive disclosure: action priorities first, then supporting intelligence, then Financial Health and explainable signals.
- Plan isolates goal-linked money.
- Explore groups reflective tools: Time Machine, local copilot and Story of my money.

## Data semantics

All five features are derived from existing encrypted records and therefore require no database schema change.

- Cooling-tank entries are excluded from spending/recurrence calculations.
- Investment transfers are excluded from ordinary spending/recurrence calculations.
- Recurring patterns require at least three observations and constrained interval/amount variability.
- Reserve runway uses NEED-labelled spending only and returns unknown when evidence is insufficient.
- Portfolio concentration is descriptive and does not create trading or rebalancing instructions.
