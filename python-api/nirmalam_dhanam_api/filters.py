from __future__ import annotations

from dataclasses import dataclass

from .models import InterchangeDocument, TransactionRecord


@dataclass(frozen=True)
class TransactionFilter:
    account_id: str | None = None
    category: str | None = None
    payee: str | None = None
    direction: str | None = None
    include_holding_tank: bool = True
    occurred_after_epoch_ms: int | None = None
    occurred_before_epoch_ms: int | None = None


def filter_transactions(
    document: InterchangeDocument,
    criteria: TransactionFilter,
) -> list[TransactionRecord]:
    items = document.transactions
    if criteria.account_id is not None:
        items = [item for item in items if item.accountId == criteria.account_id]
    if criteria.category is not None:
        items = [item for item in items if item.category == criteria.category]
    if criteria.payee is not None:
        items = [item for item in items if item.payee == criteria.payee]
    if criteria.direction is not None:
        items = [item for item in items if item.direction == criteria.direction]
    if not criteria.include_holding_tank:
        items = [item for item in items if not item.isHoldingTank]
    if criteria.occurred_after_epoch_ms is not None:
        items = [item for item in items if item.occurredAtEpochMs >= criteria.occurred_after_epoch_ms]
    if criteria.occurred_before_epoch_ms is not None:
        items = [item for item in items if item.occurredAtEpochMs <= criteria.occurred_before_epoch_ms]
    return sorted(items, key=lambda item: item.occurredAtEpochMs, reverse=True)
