# nirmalam-dhanam

Local-first Python CRUD for the portable `.dhanam` format used by the Nirmalam Dhanam Android app. Money is stored as integer **paise** and no network access is required.

```python
from nirmalam_dhanam import Dhanam

db = Dhanam("my-finances.dhanam")
rows = db.transactions.list()
db.transactions.add(account_id="bank-1", amount_paise=125000, direction="DEBIT", category="Travel", payee="Railways")
checkin = db.assets.update_balance("mf-1", current_cost_paise=50000000, current_value_paise=54120000)
print(checkin["marketMovementPaise"])
db.save()
```

CLI examples:

```bash
dhanam my-finances.dhanam validate
dhanam my-finances.dhanam transactions
dhanam my-finances.dhanam assets --history mf-1
dhanam my-finances.dhanam networth
```

Build for PyPI with `python -m build` and upload with `twine upload dist/*` after configuring your PyPI credentials.
