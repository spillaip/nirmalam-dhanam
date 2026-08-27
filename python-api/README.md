# Nirmalam Dhanam Python API

`nirmalam-dhanam-api` is a typed, local-first Python toolkit for working with Nirmalam Dhanam export files.

It is built for data management and analysis workflows such as:

- validating JSON interchange exports before downstream processing,
- loading records into Python pipelines without floating-point money loss,
- filtering and summarising Vyavahara data,
- inspecting `.ndf` backup manifest metadata without decrypting the SQLCipher payload.

It is intentionally not a live database API. The Android application remains the source of truth and the only supported writer of the encrypted finance database.

## Capabilities

- Strongly typed interchange and backup manifest models
- Validation of supported interchange and backup major versions
- Referential integrity checks across Khata, Varga, Vyakti, and Nivesha records
- Local filtering helpers for account, category, payee, direction, date window, and cooling-tank inclusion
- Aggregations for cashflow, totals by category, payee, account, latest portfolio value, and latest net worth
- CLI entry point for validation, summary generation, export filtering, and `.ndf` manifest inspection

## Installation

```powershell
pip install nirmalam-dhanam-api
```

For local development:

```powershell
cd .\python-api
python -m pip install -e .[dev]
```

## Safety model

- Use JSON interchange for analysis and local automation.
- Use `.ndf` only for encrypted backup and restore.
- Do not treat `.ndf` as a writable integration format.
- Never log passphrases, derived keys, or decrypted sensitive payloads.

## Quick start

```python
from nirmalam_dhanam_api import NirmalamDataManager

manager = NirmalamDataManager.from_interchange("nirmalam-dhanam-interchange.json")
print(manager.summary())
```

## Library examples

```python
from nirmalam_dhanam_api import NirmalamDataManager, TransactionFilter

manager = NirmalamDataManager.from_interchange("nirmalam-dhanam-interchange.json")

salary = manager.filter_transactions(
    TransactionFilter(category="Salary", direction="CREDIT")
)

print("Salary entries:", len(salary))
print("Portfolio snapshot:", manager.summary()["portfolio"])
```

## CLI examples

Validate an interchange file:

```powershell
nirmalam-dhanam validate .\sample.json
```

Show a compact summary:

```powershell
nirmalam-dhanam summary .\sample.json
```

Filter transactions and write a reduced JSON file:

```powershell
nirmalam-dhanam export-filter .\sample.json .\food.json --category Food --direction DEBIT
```

Read `.ndf` backup manifest metadata:

```powershell
nirmalam-dhanam inspect-ndf .\backup.ndf
```

## Build and publish

```powershell
cd .\python-api
python -m build
python -m twine check dist/*
python -m twine upload dist/*
```
