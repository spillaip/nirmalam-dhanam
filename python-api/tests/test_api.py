from pathlib import Path
import json
import tempfile
import unittest
from zipfile import ZipFile

from nirmalam_dhanam_api import (
    NirmalamDataManager,
    TransactionFilter,
    cashflow_summary,
    filter_transactions,
    inspect_ndf_manifest,
    latest_portfolio_value,
    load_interchange,
    write_interchange,
)


SAMPLE = {
    "format": "nirmalam-dhanam-interchange",
    "formatVersion": 1,
    "exportedAtEpochMs": 1720000000000,
    "currencyCode": "INR",
    "monetaryUnit": "paise",
    "accounts": [
        {"id": "a1", "name": "Daily Cash", "kind": "SPENDING", "productType": "CASH", "assetClass": "CASH", "targetAllocationBps": 0, "openingBalancePaise": 100000}
    ],
    "categories": [
        {"id": "c1", "name": "Food", "direction": "DEBIT", "isSystem": False, "iconKey": "restaurant"},
        {"id": "c2", "name": "Salary", "direction": "CREDIT", "isSystem": True, "iconKey": "payments"},
    ],
    "payees": [
        {"id": "p1", "name": "Cafe", "defaultCategory": "Food", "lastUsedEpochMs": 1720000000000},
        {"id": "p2", "name": "Employer", "defaultCategory": "Salary", "lastUsedEpochMs": 1720000000000},
    ],
    "transactions": [
        {"id": "t1", "accountId": "a1", "amountPaise": 25000, "direction": "DEBIT", "payee": "Cafe", "category": "Food", "description": "Lunch", "envelopeType": "NEEDS", "occurredAtEpochMs": 1720000001000, "isHoldingTank": False, "coolDownExpiryEpochMs": None},
        {"id": "t2", "accountId": "a1", "amountPaise": 500000, "direction": "CREDIT", "payee": "Employer", "category": "Salary", "description": "Monthly salary", "envelopeType": None, "occurredAtEpochMs": 1720000002000, "isHoldingTank": False, "coolDownExpiryEpochMs": None},
    ],
    "investmentBalances": [
        {"id": "i1", "accountId": "a1", "asOfEpochDay": 20000, "totalCostPaise": 1000000, "currentValuePaise": 1150000, "netContributionPaise": 1000000, "note": "Opening"},
    ],
    "netWorthSnapshots": [
        {"id": "n1", "asOfEpochDay": 20000, "netWorthPaise": 1250000, "portfolioValuePaise": 1150000}
    ],
}


class PythonApiTests(unittest.TestCase):
    def test_load_and_summary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.json"
            path.write_text(json.dumps(SAMPLE), encoding="utf-8")
            document = load_interchange(path)
            self.assertEqual(document.summary()["transactions"], 2)
            self.assertEqual(cashflow_summary(document.transactions)["netPaise"], 475000)

    def test_filter_and_write(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "sample.json"
            target = Path(directory) / "food.json"
            source.write_text(json.dumps(SAMPLE), encoding="utf-8")
            document = load_interchange(source)
            filtered = filter_transactions(document, TransactionFilter(category="Food"))
            reduced = type(document)(**{**document.to_dict(), "transactions": [item.__dict__ for item in filtered]})
            write_interchange(target, reduced)
            reloaded = load_interchange(target)
            self.assertEqual(len(reloaded.transactions), 1)
            self.assertEqual(reloaded.transactions[0].category, "Food")

    def test_portfolio(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.json"
            path.write_text(json.dumps(SAMPLE), encoding="utf-8")
            document = load_interchange(path)
            portfolio = latest_portfolio_value(document)
            self.assertIsNotNone(portfolio)
            assert portfolio is not None
            self.assertEqual(portfolio["gainPaise"], 150000)

    def test_manager_summary_and_export(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "sample.json"
            target = Path(directory) / "salary.json"
            source.write_text(json.dumps(SAMPLE), encoding="utf-8")
            manager = NirmalamDataManager.from_interchange(source)
            summary = manager.summary()
            self.assertEqual(summary["document"]["accounts"], 1)
            manager.export_filtered_interchange(
                target,
                TransactionFilter(category="Salary", direction="CREDIT"),
            )
            reloaded = load_interchange(target)
            self.assertEqual(len(reloaded.transactions), 1)
            self.assertEqual(reloaded.transactions[0].category, "Salary")

    def test_ndf_manifest_read(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "backup.ndf"
            with ZipFile(path, "w") as archive:
                archive.writestr(
                    "manifest.json",
                    json.dumps(
                        {
                            "format": "nirmalam-dhanam-backup",
                            "formatVersion": 2,
                            "exportedAtEpochMs": 1720000000000,
                            "databaseFile": "database.sqlcipher",
                            "kdf": "PBKDF2WithHmacSHA256",
                            "kdfIterations": 210000,
                            "kdfSaltBase64": "abc",
                            "databaseSha256": "deadbeef",
                            "databaseSchemaVersion": 12,
                        }
                    ),
                )
                archive.writestr("database.sqlcipher", b"ciphertext")
            manifest = inspect_ndf_manifest(path)
            self.assertEqual(manifest.formatVersion, 2)


if __name__ == "__main__":
    unittest.main()
