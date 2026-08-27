from __future__ import annotations

import json
from dataclasses import fields
from pathlib import Path
from typing import Any, TypeVar
from zipfile import ZipFile

from .models import (
    AccountRecord,
    CategoryRecord,
    InterchangeDocument,
    InvestmentBalanceRecord,
    NdfBackupManifest,
    NdfValidationError,
    NetWorthRecord,
    PayeeRecord,
    TransactionRecord,
)
from .validation import require, require_literal, require_type

T = TypeVar("T")


def _decode_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _build_record(record_type: type[T], raw: dict[str, Any]) -> T:
    allowed = {field.name for field in fields(record_type)}
    values = {key: raw.get(key) for key in allowed}
    return record_type(**values)


def load_interchange(path: Path) -> InterchangeDocument:
    raw = _decode_json(path)
    require_literal(raw, "format", "nirmalam-dhanam-interchange")
    format_version = require_type(raw, "formatVersion", int)
    require(format_version == 1, f"Unsupported interchange formatVersion: {format_version}")
    require_literal(raw, "monetaryUnit", "paise")

    document = InterchangeDocument(
        format=raw["format"],
        formatVersion=format_version,
        exportedAtEpochMs=require_type(raw, "exportedAtEpochMs", int),
        currencyCode=require_type(raw, "currencyCode", str),
        monetaryUnit=raw["monetaryUnit"],
        accounts=[_build_record(AccountRecord, item) for item in require_type(raw, "accounts", list)],
        categories=[_build_record(CategoryRecord, item) for item in require_type(raw, "categories", list)],
        payees=[_build_record(PayeeRecord, item) for item in require_type(raw, "payees", list)],
        transactions=[_build_record(TransactionRecord, item) for item in require_type(raw, "transactions", list)],
        investmentBalances=[_build_record(InvestmentBalanceRecord, item) for item in require_type(raw, "investmentBalances", list)],
        netWorthSnapshots=[_build_record(NetWorthRecord, item) for item in require_type(raw, "netWorthSnapshots", list)],
    )
    _validate_references(document)
    return document


def write_interchange(path: Path, document: InterchangeDocument) -> None:
    path.write_text(json.dumps(document.to_dict(), indent=2, ensure_ascii=False), encoding="utf-8")


def inspect_ndf_manifest(path: Path) -> NdfBackupManifest:
    with ZipFile(path, "r") as archive:
        names = archive.namelist()
        require("manifest.json" in names, "NDF archive is missing manifest.json")
        require("database.sqlcipher" in names, "NDF archive is missing database.sqlcipher")
        with archive.open("manifest.json", "r") as handle:
            raw = json.loads(handle.read().decode("utf-8"))

    require_literal(raw, "format", "nirmalam-dhanam-backup")
    format_version = require_type(raw, "formatVersion", int)
    require(format_version in (1, 2), f"Unsupported backup formatVersion: {format_version}")
    manifest = NdfBackupManifest(
        format=raw["format"],
        formatVersion=format_version,
        exportedAtEpochMs=require_type(raw, "exportedAtEpochMs", int),
        databaseFile=require_type(raw, "databaseFile", str),
        kdf=require_type(raw, "kdf", str),
        kdfIterations=require_type(raw, "kdfIterations", int),
        kdfSaltBase64=require_type(raw, "kdfSaltBase64", str),
        databaseSha256=raw.get("databaseSha256"),
        databaseSchemaVersion=raw.get("databaseSchemaVersion"),
    )
    require(manifest.databaseFile == "database.sqlcipher", "Unexpected backup database payload name")
    return manifest


def _validate_references(document: InterchangeDocument) -> None:
    account_ids = {account.id for account in document.accounts}
    category_names = {category.name for category in document.categories}
    payee_names = {payee.name for payee in document.payees}

    for transaction in document.transactions:
        require(transaction.accountId in account_ids, f"Transaction {transaction.id} references missing account {transaction.accountId}")
        if transaction.category is not None:
            require(transaction.category in category_names, f"Transaction {transaction.id} references missing category {transaction.category}")
        if transaction.payee is not None:
            require(transaction.payee in payee_names, f"Transaction {transaction.id} references missing payee {transaction.payee}")

    for balance in document.investmentBalances:
        require(balance.accountId in account_ids, f"Investment balance {balance.id} references missing account {balance.accountId}")
