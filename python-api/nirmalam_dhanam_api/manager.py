from __future__ import annotations

from dataclasses import replace
from pathlib import Path

from .analytics import (
    cashflow_summary,
    latest_net_worth,
    latest_portfolio_value,
    transaction_totals_by_account,
    transaction_totals_by_category,
    transaction_totals_by_payee,
)
from .filters import TransactionFilter, filter_transactions
from .io import inspect_ndf_manifest, load_interchange, write_interchange
from .models import InterchangeDocument, NdfBackupManifest


class NirmalamDataManager:
    """High-level local API for Nirmalam Dhanam exports."""

    def __init__(self, document: InterchangeDocument) -> None:
        self.document = document

    @classmethod
    def from_interchange(cls, path: str | Path) -> "NirmalamDataManager":
        return cls(load_interchange(Path(path)))

    @staticmethod
    def inspect_backup(path: str | Path) -> NdfBackupManifest:
        return inspect_ndf_manifest(Path(path))

    def summary(self) -> dict[str, object]:
        net_worth = latest_net_worth(self.document)
        return {
            "document": self.document.summary(),
            "cashflow": cashflow_summary(self.document.transactions),
            "byCategory": transaction_totals_by_category(self.document),
            "byPayee": transaction_totals_by_payee(self.document),
            "byAccount": transaction_totals_by_account(self.document),
            "portfolio": latest_portfolio_value(self.document),
            "netWorth": net_worth.netWorthPaise if net_worth else None,
        }

    def filter_transactions(self, criteria: TransactionFilter) -> list:
        return filter_transactions(self.document, criteria)

    def export_filtered_interchange(self, output: str | Path, criteria: TransactionFilter) -> InterchangeDocument:
        transactions = filter_transactions(self.document, criteria)
        reduced = replace(self.document, transactions=transactions)
        write_interchange(Path(output), reduced)
        return reduced
