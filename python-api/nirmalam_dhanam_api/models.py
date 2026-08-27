from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any


class NdfValidationError(ValueError):
    pass


@dataclass(frozen=True)
class AccountRecord:
    id: str
    name: str
    kind: str
    productType: str
    assetClass: str
    targetAllocationBps: int
    openingBalancePaise: int


@dataclass(frozen=True)
class CategoryRecord:
    id: str
    name: str
    direction: str
    isSystem: bool
    iconKey: str | None = None


@dataclass(frozen=True)
class PayeeRecord:
    id: str
    name: str
    defaultCategory: str | None
    lastUsedEpochMs: int


@dataclass(frozen=True)
class TransactionRecord:
    id: str
    accountId: str
    amountPaise: int
    direction: str
    payee: str | None
    category: str | None
    description: str | None
    envelopeType: str | None
    occurredAtEpochMs: int
    isHoldingTank: bool
    coolDownExpiryEpochMs: int | None


@dataclass(frozen=True)
class InvestmentBalanceRecord:
    id: str
    accountId: str
    asOfEpochDay: int
    totalCostPaise: int
    currentValuePaise: int
    netContributionPaise: int
    note: str | None


@dataclass(frozen=True)
class NetWorthRecord:
    id: str
    asOfEpochDay: int
    netWorthPaise: int
    portfolioValuePaise: int


@dataclass(frozen=True)
class NdfBackupManifest:
    format: str
    formatVersion: int
    exportedAtEpochMs: int
    databaseFile: str
    kdf: str
    kdfIterations: int
    kdfSaltBase64: str
    databaseSha256: str | None = None
    databaseSchemaVersion: int | None = None


@dataclass(frozen=True)
class InterchangeDocument:
    format: str
    formatVersion: int
    exportedAtEpochMs: int
    currencyCode: str
    monetaryUnit: str
    accounts: list[AccountRecord] = field(default_factory=list)
    categories: list[CategoryRecord] = field(default_factory=list)
    payees: list[PayeeRecord] = field(default_factory=list)
    transactions: list[TransactionRecord] = field(default_factory=list)
    investmentBalances: list[InvestmentBalanceRecord] = field(default_factory=list)
    netWorthSnapshots: list[NetWorthRecord] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    def summary(self) -> dict[str, Any]:
        return {
            "format": self.format,
            "formatVersion": self.formatVersion,
            "currencyCode": self.currencyCode,
            "accounts": len(self.accounts),
            "categories": len(self.categories),
            "payees": len(self.payees),
            "transactions": len(self.transactions),
            "investmentBalances": len(self.investmentBalances),
            "netWorthSnapshots": len(self.netWorthSnapshots),
        }
