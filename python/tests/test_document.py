import json
import tempfile
import unittest
from pathlib import Path
from datetime import date

from nirmalam_dhanam import Dhanam


class DhanamTests(unittest.TestCase):

    def test_convenience_constructor_creates_then_reopens(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "convenience.dhanam"
            db = Dhanam(path)
            account = db.accounts.add(name="Savings", kind="SAVINGS", opening_balance_paise=250000)
            db.save()
            reopened = Dhanam(path)
            self.assertEqual(reopened.accounts.get(account["id"])["kind"], "SAVINGS")

    def test_account_crud(self):
        with tempfile.TemporaryDirectory() as td:
            db = Dhanam.create(Path(td) / "crud.dhanam")
            account = db.accounts.add(name="Primary Bank", kind="SPENDING", opening_balance_paise=50000)
            self.assertEqual("Primary Bank", db.accounts.get(account["id"])["name"])
            db.accounts.update(account["id"], name="Main Bank")
            self.assertEqual("Main Bank", db.accounts.get(account["id"])["name"])
            self.assertTrue(db.accounts.archive(account["id"]))
            self.assertEqual([], db.accounts.list())

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "test.dhanam"
        self.db = Dhanam.create(self.path)
        self.db.data["accounts"].extend([
            {"id":"bank","name":"Bank","kind":"SPENDING","productType":"BANK","assetClass":"CASH","targetAllocationBps":0,"openingBalancePaise":100000,"isArchived":False},
            {"id":"fund","name":"Fund","kind":"INVESTMENT","productType":"MUTUAL_FUNDS","assetClass":"EQUITY","targetAllocationBps":10000,"openingBalancePaise":0,"isArchived":False},
        ])

    def tearDown(self):
        self.tmp.cleanup()

    def test_transaction_crud_and_atomic_save(self):
        row = self.db.transactions.add(account_id="bank", amount_paise=1250, direction="DEBIT", category="Food", payee="Cafe")
        self.assertEqual(self.db.transactions.get(row["id"])["amountPaise"], 1250)
        self.db.transactions.update(row["id"], amountPaise=1500)
        self.db.save()
        reopened = Dhanam.open(self.path)
        self.assertEqual(reopened.transactions.get(row["id"])["amountPaise"], 1500)
        self.assertTrue(reopened.transactions.delete(row["id"]))

    def test_asset_delta_engine(self):
        first = self.db.assets.update_balance("fund", current_cost_paise=100000, current_value_paise=105000, as_of=date(2026,1,1))
        second = self.db.assets.update_balance("fund", current_cost_paise=120000, current_value_paise=130000, as_of=date(2026,2,1))
        self.assertEqual(first["netContributionPaise"], 100000)
        self.assertEqual(first["marketMovementPaise"], 5000)
        self.assertEqual(second["netContributionPaise"], 20000)
        self.assertEqual(second["valueDeltaPaise"], 25000)
        self.assertEqual(second["marketMovementPaise"], 5000)

    def test_goal_and_networth(self):
        self.db.goals.add(name="Emergency", target_amount_paise=500000, account_id="bank")
        self.db.transactions.add(account_id="bank", amount_paise=10000, direction="CREDIT")
        self.assertEqual(self.db.networth_current(), 110000)
        self.assertEqual(len(self.db.goals.list()), 1)


if __name__ == "__main__":
    unittest.main()
