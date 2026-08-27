from __future__ import annotations

from collections import defaultdict

from .models import InterchangeDocument, NetWorthRecord, TransactionRecord


def cashflow_summary(transactions: list[TransactionRecord]) -> dict[str, int]:
    total_in = sum(item.amountPaise for item in transactions if item.direction == "CREDIT")
    total_out = sum(item.amountPaise for item in transactions if item.direction == "DEBIT")
    return {
        "inflowPaise": total_in,
        "outflowPaise": total_out,
        "netPaise": total_in - total_out,
        "entryCount": len(transactions),
    }


def transaction_totals_by_category(document: InterchangeDocument) -> dict[str, int]:
    totals: dict[str, int] = defaultdict(int)
    for item in document.transactions:
        totals[item.category or "Uncategorised"] += item.amountPaise
    return dict(sorted(totals.items(), key=lambda entry: (-entry[1], entry[0])))


def transaction_totals_by_payee(document: InterchangeDocument) -> dict[str, int]:
    totals: dict[str, int] = defaultdict(int)
    for item in document.transactions:
        totals[item.payee or "Unknown"] += item.amountPaise
    return dict(sorted(totals.items(), key=lambda entry: (-entry[1], entry[0])))


def transaction_totals_by_account(document: InterchangeDocument) -> dict[str, int]:
    names = {account.id: account.name for account in document.accounts}
    totals: dict[str, int] = defaultdict(int)
    for item in document.transactions:
        totals[names.get(item.accountId, item.accountId)] += item.amountPaise if item.direction == "CREDIT" else -item.amountPaise
    return dict(sorted(totals.items(), key=lambda entry: (-abs(entry[1]), entry[0])))


def latest_portfolio_value(document: InterchangeDocument) -> dict[str, int] | None:
    if not document.investmentBalances:
        return None
    latest_by_account: dict[str, tuple[int, int, int]] = {}
    for item in document.investmentBalances:
        current = latest_by_account.get(item.accountId)
        candidate = (item.asOfEpochDay, item.totalCostPaise, item.currentValuePaise)
        if current is None or candidate[0] >= current[0]:
            latest_by_account[item.accountId] = candidate
    total_cost = sum(item[1] for item in latest_by_account.values())
    total_value = sum(item[2] for item in latest_by_account.values())
    return {
        "costPaise": total_cost,
        "valuePaise": total_value,
        "gainPaise": total_value - total_cost,
    }


def latest_net_worth(document: InterchangeDocument) -> NetWorthRecord | None:
    if not document.netWorthSnapshots:
        return None
    return max(document.netWorthSnapshots, key=lambda item: item.asOfEpochDay)
