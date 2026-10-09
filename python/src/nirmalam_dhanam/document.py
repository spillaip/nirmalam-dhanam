from __future__ import annotations

import json
import os
import tempfile
import uuid
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Iterable

FORMAT = "nirmalam-dhanam"
FORMAT_VERSION = 2


class DhanamError(Exception):
    pass


class ValidationError(DhanamError):
    pass


def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def _new_document(currency_code: str = "INR") -> dict[str, Any]:
    return {
        "format": FORMAT,
        "formatVersion": FORMAT_VERSION,
        "exportedAtEpochMs": _now_ms(),
        "currencyCode": currency_code,
        "monetaryUnit": "paise",
        "accounts": [],
        "categories": [],
        "payees": [],
        "transactions": [],
        "investmentBalances": [],
        "netWorthSnapshots": [],
        "goals": [],
        "goalAllocations": [],
    }


class AccountStore:
    def __init__(self, owner: "Dhanam") -> None:
        self.owner = owner

    def list(self, *, include_archived: bool = False) -> list[dict[str, Any]]:
        return [row.copy() for row in self.owner.data["accounts"] if include_archived or not row.get("isArchived", False)]

    def get(self, account_id: str) -> dict[str, Any] | None:
        row = next((row for row in self.owner.data["accounts"] if row["id"] == account_id), None)
        return row.copy() if row is not None else None

    def add(
        self, *, name: str, kind: str = "SPENDING", product_type: str = "BANK",
        asset_class: str = "CASH", opening_balance_paise: int = 0,
        target_allocation_bps: int = 0, account_id: str | None = None
    ) -> dict[str, Any]:
        kind = kind.upper(); product_type = product_type.upper(); asset_class = asset_class.upper()
        _require(name.strip() != "", "account name is required")
        _require(kind in {"SPENDING", "CREDIT", "SAVINGS", "EMERGENCY", "INVESTMENT"}, "invalid account kind")
        _require(0 <= int(target_allocation_bps) <= 10000, "target_allocation_bps must be 0..10000")
        row = {
            "id": account_id or str(uuid.uuid4()), "name": name.strip(), "kind": kind,
            "productType": product_type, "assetClass": asset_class,
            "targetAllocationBps": int(target_allocation_bps),
            "openingBalancePaise": int(opening_balance_paise), "currentMarketPaise": 0,
            "intrinsicValuePaise": 0, "benchmarkIndexName": None,
            "benchmarkTrackingMethod": "NONE", "benchmarkIsTotalReturn": True,
            "isArchived": False, "createdAtEpochMs": _now_ms(),
        }
        _require(self.get(row["id"]) is None, f"account {row['id']} already exists")
        self.owner.data["accounts"].append(row); self.owner._touch()
        return row.copy()

    def update(self, account_id: str, **changes: Any) -> dict[str, Any]:
        row = next((row for row in self.owner.data["accounts"] if row["id"] == account_id), None)
        if row is None:
            raise KeyError(account_id)
        allowed = {"name", "kind", "productType", "assetClass", "targetAllocationBps", "openingBalancePaise", "currentMarketPaise", "intrinsicValuePaise", "benchmarkIndexName", "benchmarkTrackingMethod", "benchmarkIsTotalReturn", "isArchived"}
        unknown = set(changes) - allowed
        _require(not unknown, f"unsupported account field(s): {', '.join(sorted(unknown))}")
        updated = dict(row); updated.update(changes)
        _require(str(updated["name"]).strip() != "", "account name is required")
        _require(0 <= int(updated.get("targetAllocationBps", 0)) <= 10000, "targetAllocationBps must be 0..10000")
        row.clear(); row.update(updated); self.owner._touch()
        return row.copy()

    def archive(self, account_id: str) -> bool:
        row = next((row for row in self.owner.data["accounts"] if row["id"] == account_id), None)
        if row is None:
            return False
        row["isArchived"] = True; self.owner._touch(); return True


class TransactionStore:
    def __init__(self, owner: "Dhanam") -> None:
        self.owner = owner

    def list(self, *, account_id: str | None = None, direction: str | None = None) -> list[dict[str, Any]]:
        rows = list(self.owner.data["transactions"])
        if account_id is not None:
            rows = [row for row in rows if row["accountId"] == account_id]
        if direction is not None:
            rows = [row for row in rows if row["direction"] == direction.upper()]
        return sorted(rows, key=lambda row: (row.get("occurredAtEpochMs", 0), row["id"]), reverse=True)

    def get(self, transaction_id: str) -> dict[str, Any] | None:
        return next((row for row in self.owner.data["transactions"] if row["id"] == transaction_id), None)

    def add(
        self,
        *,
        account_id: str,
        amount_paise: int,
        direction: str,
        occurred_at_epoch_ms: int | None = None,
        category: str | None = None,
        payee: str | None = None,
        description: str | None = None,
        envelope_type: str | None = None,
        transaction_id: str | None = None,
    ) -> dict[str, Any]:
        self.owner._require_account(account_id)
        _require(int(amount_paise) > 0, "amount_paise must be positive")
        direction = direction.upper()
        _require(direction in {"DEBIT", "CREDIT"}, "direction must be DEBIT or CREDIT")
        row = {
            "id": transaction_id or str(uuid.uuid4()),
            "accountId": account_id,
            "amountPaise": int(amount_paise),
            "direction": direction,
            "merchant": payee,
            "payee": payee,
            "category": category,
            "description": description,
            "envelopeType": envelope_type,
            "occurredAtEpochMs": int(occurred_at_epoch_ms or _now_ms()),
            "isHoldingTank": False,
            "coolDownExpiryEpochMs": None,
            "note": None,
        }
        _require(self.get(row["id"]) is None, f"transaction {row['id']} already exists")
        self.owner.data["transactions"].append(row)
        self.owner._touch()
        return row.copy()

    def update(self, transaction_id: str, **changes: Any) -> dict[str, Any]:
        row = self.get(transaction_id)
        if row is None:
            raise KeyError(transaction_id)
        allowed = {"accountId", "amountPaise", "direction", "merchant", "payee", "category", "description", "envelopeType", "occurredAtEpochMs", "isHoldingTank", "coolDownExpiryEpochMs", "note"}
        unknown = set(changes) - allowed
        _require(not unknown, f"unsupported transaction field(s): {', '.join(sorted(unknown))}")
        updated = dict(row)
        updated.update(changes)
        self.owner._require_account(updated["accountId"])
        _require(int(updated["amountPaise"]) > 0, "amountPaise must be positive")
        _require(updated["direction"] in {"DEBIT", "CREDIT"}, "direction must be DEBIT or CREDIT")
        row.clear(); row.update(updated)
        self.owner._touch()
        return row.copy()

    def delete(self, transaction_id: str) -> bool:
        before = len(self.owner.data["transactions"])
        self.owner.data["transactions"] = [row for row in self.owner.data["transactions"] if row["id"] != transaction_id]
        changed = len(self.owner.data["transactions"]) != before
        if changed:
            self.owner._touch()
        return changed

    def search(self, text: str) -> list[dict[str, Any]]:
        needle = text.casefold()
        return [row for row in self.list() if any(needle in str(row.get(field) or "").casefold() for field in ("payee", "merchant", "category", "description"))]


class AssetStore:
    def __init__(self, owner: "Dhanam") -> None:
        self.owner = owner

    def list(self) -> list[dict[str, Any]]:
        return [row.copy() for row in self.owner.data["accounts"] if row.get("kind") == "INVESTMENT"]

    def history(self, account_id: str) -> list[dict[str, Any]]:
        self.owner._require_account(account_id)
        rows = [row.copy() for row in self.owner.data["investmentBalances"] if row["accountId"] == account_id]
        return sorted(rows, key=lambda row: (row["asOfEpochDay"], row.get("createdAtEpochMs", 0)))

    def latest(self, account_id: str) -> dict[str, Any] | None:
        rows = self.history(account_id)
        return rows[-1] if rows else None

    def update_balance(
        self,
        account_id: str,
        *,
        current_cost_paise: int,
        current_value_paise: int,
        as_of: date | str | None = None,
        note: str | None = None,
    ) -> dict[str, Any]:
        account = self.owner._require_account(account_id)
        _require(account.get("kind") == "INVESTMENT", "balance snapshots are only valid for INVESTMENT accounts")
        _require(current_cost_paise >= 0 and current_value_paise >= 0, "cost and value must be non-negative")
        if as_of is None:
            as_of = date.today()
        elif isinstance(as_of, str):
            as_of = date.fromisoformat(as_of)
        epoch_day = (as_of - date(1970, 1, 1)).days
        existing = next((row for row in self.owner.data["investmentBalances"] if row["accountId"] == account_id and row["asOfEpochDay"] == epoch_day), None)
        snapshot_id = existing["id"] if existing else str(uuid.uuid4())
        if existing:
            self.owner.data["investmentBalances"].remove(existing)
        self.owner.data["investmentBalances"].append({
            "id": snapshot_id,
            "accountId": account_id,
            "asOfEpochDay": epoch_day,
            "totalCostPaise": int(current_cost_paise),
            "currentValuePaise": int(current_value_paise),
            "netContributionPaise": 0,
            "previousCostPaise": None,
            "previousValuePaise": None,
            "costDeltaPaise": 0,
            "valueDeltaPaise": 0,
            "marketMovementPaise": 0,
            "note": note,
            "createdAtEpochMs": existing.get("createdAtEpochMs", _now_ms()) if existing else _now_ms(),
        })
        self._rederive(account_id)
        self.owner._touch()
        return next(row.copy() for row in self.owner.data["investmentBalances"] if row["id"] == snapshot_id)

    def delete_balance(self, snapshot_id: str) -> bool:
        snapshot = next((row for row in self.owner.data["investmentBalances"] if row["id"] == snapshot_id), None)
        if snapshot is None:
            return False
        account_id = snapshot["accountId"]
        self.owner.data["investmentBalances"].remove(snapshot)
        self._rederive(account_id)
        self.owner._touch()
        return True

    def _rederive(self, account_id: str) -> None:
        rows = sorted((row for row in self.owner.data["investmentBalances"] if row["accountId"] == account_id), key=lambda row: (row["asOfEpochDay"], row.get("createdAtEpochMs", 0)))
        previous: dict[str, Any] | None = None
        for row in rows:
            cost = int(row["totalCostPaise"]); value = int(row["currentValuePaise"])
            previous_cost = int(previous["totalCostPaise"]) if previous else None
            previous_value = int(previous["currentValuePaise"]) if previous else None
            cost_delta = cost - (previous_cost or 0)
            value_delta = value - (previous_value or 0)
            row["previousCostPaise"] = previous_cost
            row["previousValuePaise"] = previous_value
            row["costDeltaPaise"] = cost_delta
            row["valueDeltaPaise"] = value_delta
            row["netContributionPaise"] = cost_delta
            row["marketMovementPaise"] = value_delta - cost_delta
            previous = row


class GoalStore:
    def __init__(self, owner: "Dhanam") -> None:
        self.owner = owner

    def list(self, *, include_archived: bool = False) -> list[dict[str, Any]]:
        return [row.copy() for row in self.owner.data["goals"] if include_archived or not row.get("isArchived", False)]

    def add(self, *, name: str, target_amount_paise: int, target_date: date | str | None = None, account_id: str | None = None) -> dict[str, Any]:
        _require(name.strip() != "" and target_amount_paise > 0, "goal name and positive target are required")
        epoch_day = None
        if target_date is not None:
            if isinstance(target_date, str):
                target_date = date.fromisoformat(target_date)
            epoch_day = (target_date - date(1970, 1, 1)).days
        goal_id = str(uuid.uuid4())
        row = {"id": goal_id, "name": name.strip(), "targetAmountPaise": int(target_amount_paise), "targetDateEpochDay": epoch_day, "note": None, "isArchived": False, "createdAtEpochMs": _now_ms()}
        self.owner.data["goals"].append(row)
        if account_id:
            self.owner._require_account(account_id)
            self.owner.data["goalAllocations"].append({"id": str(uuid.uuid4()), "goalId": goal_id, "accountId": account_id, "allocationBps": 10000})
        self.owner._touch()
        return row.copy()

    def archive(self, goal_id: str) -> bool:
        row = next((row for row in self.owner.data["goals"] if row["id"] == goal_id), None)
        if row is None:
            return False
        row["isArchived"] = True
        self.owner._touch()
        return True


class Dhanam:
    """Mutable in-memory representation of a portable `.dhanam` document.

    Use :meth:`open` to load an existing file or :meth:`create` for a new file. Mutations are
    persisted only when :meth:`save` is called, allowing callers to batch CRUD operations atomically.
    """

    def __init__(self, path: str | os.PathLike[str], data: dict[str, Any] | None = None) -> None:
        """Open an existing portable file or start a new in-memory document.

        ``Dhanam("my-finances.dhanam")`` is the convenient user-facing entry point.
        Existing files are loaded and validated. A missing path starts a new document that is
        written atomically when :meth:`save` is called. ``open`` and ``create`` remain available
        when callers want explicit existence semantics.
        """
        self.path = Path(path)
        if data is None:
            if self.path.exists():
                try:
                    data = json.loads(self.path.read_text(encoding="utf-8"))
                except (OSError, json.JSONDecodeError) as exc:
                    raise DhanamError(f"unable to open {self.path}: {exc}") from exc
            else:
                data = _new_document()
        self.data = data
        self.accounts = AccountStore(self)
        self.transactions = TransactionStore(self)
        self.assets = AssetStore(self)
        self.goals = GoalStore(self)
        self._dirty = False
        self.validate()

    @classmethod
    def open(cls, path: str | os.PathLike[str]) -> "Dhanam":
        path = Path(path)
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise DhanamError(f"unable to open {path}: {exc}") from exc
        return cls(path, data)

    @classmethod
    def create(cls, path: str | os.PathLike[str], *, currency_code: str = "INR") -> "Dhanam":
        return cls(Path(path), _new_document(currency_code))

    def _touch(self) -> None:
        self._dirty = True
        self.data["exportedAtEpochMs"] = _now_ms()

    def _require_account(self, account_id: str) -> dict[str, Any]:
        row = next((row for row in self.data["accounts"] if row["id"] == account_id), None)
        if row is None:
            raise ValidationError(f"unknown accountId: {account_id}")
        return row

    def validate(self) -> None:
        _require(self.data.get("format") == FORMAT, "not a Nirmalam Dhanam file")
        version = int(self.data.get("formatVersion", 0))
        _require(1 <= version <= FORMAT_VERSION, f"unsupported formatVersion {version}")
        _require(self.data.get("monetaryUnit") == "paise", "monetaryUnit must be paise")
        for name in ("accounts", "categories", "payees", "transactions", "investmentBalances", "netWorthSnapshots", "goals", "goalAllocations"):
            self.data.setdefault(name, [])
            _require(isinstance(self.data[name], list), f"{name} must be a list")
        account_ids = {row["id"] for row in self.data["accounts"]}
        _require(len(account_ids) == len(self.data["accounts"]), "duplicate account id")
        for tx in self.data["transactions"]:
            _require(tx["accountId"] in account_ids, f"transaction {tx.get('id')} references unknown account")
            _require(int(tx["amountPaise"]) > 0, f"transaction {tx.get('id')} has invalid amount")
        for snapshot in self.data["investmentBalances"]:
            _require(snapshot["accountId"] in account_ids, f"investment snapshot {snapshot.get('id')} references unknown account")
        goal_ids = {row["id"] for row in self.data["goals"]}
        for allocation in self.data["goalAllocations"]:
            _require(allocation["goalId"] in goal_ids and allocation["accountId"] in account_ids, "goal allocation references unknown id")
            _require(0 <= int(allocation["allocationBps"]) <= 10000, "goal allocation must be 0..10000 bps")

    def save(self, path: str | os.PathLike[str] | None = None) -> Path:
        destination = Path(path) if path is not None else self.path
        self.validate()
        destination.parent.mkdir(parents=True, exist_ok=True)
        fd, temp_name = tempfile.mkstemp(prefix=destination.name + ".", suffix=".tmp", dir=destination.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
                json.dump(self.data, handle, indent=2, ensure_ascii=False)
                handle.write("\n")
                handle.flush(); os.fsync(handle.fileno())
            os.replace(temp_name, destination)
        finally:
            if os.path.exists(temp_name):
                os.unlink(temp_name)
        self.path = destination
        self._dirty = False
        return destination

    def networth_current(self) -> int:
        balances: dict[str, int] = {row["id"]: int(row.get("openingBalancePaise", 0)) for row in self.data["accounts"]}
        for tx in self.data["transactions"]:
            if tx.get("isHoldingTank"):
                continue
            balances[tx["accountId"]] = balances.get(tx["accountId"], 0) + (int(tx["amountPaise"]) if tx["direction"] == "CREDIT" else -int(tx["amountPaise"]))
        latest_investments = {}
        for snapshot in self.data["investmentBalances"]:
            prev = latest_investments.get(snapshot["accountId"])
            if prev is None or snapshot["asOfEpochDay"] > prev["asOfEpochDay"]:
                latest_investments[snapshot["accountId"]] = snapshot
        total = 0
        for account in self.data["accounts"]:
            if account.get("isArchived"):
                continue
            if account.get("kind") == "INVESTMENT":
                total += int(latest_investments.get(account["id"], {}).get("currentValuePaise", 0))
            else:
                total += balances.get(account["id"], 0)
        return total
