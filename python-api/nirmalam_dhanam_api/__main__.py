from __future__ import annotations

import argparse
import json
from dataclasses import replace
from pathlib import Path

from .filters import TransactionFilter, filter_transactions
from .io import inspect_ndf_manifest, load_interchange, write_interchange
from .manager import NirmalamDataManager


def main() -> None:
    parser = argparse.ArgumentParser(prog="nirmalam_dhanam_api")
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("input", type=Path)

    summary_parser = subparsers.add_parser("summary")
    summary_parser.add_argument("input", type=Path)

    filter_parser = subparsers.add_parser("export-filter")
    filter_parser.add_argument("input", type=Path)
    filter_parser.add_argument("output", type=Path)
    filter_parser.add_argument("--account-id")
    filter_parser.add_argument("--category")
    filter_parser.add_argument("--payee")
    filter_parser.add_argument("--direction")
    filter_parser.add_argument("--exclude-holding-tank", action="store_true")
    filter_parser.add_argument("--from-epoch-ms", type=int)
    filter_parser.add_argument("--to-epoch-ms", type=int)

    ndf_parser = subparsers.add_parser("inspect-ndf")
    ndf_parser.add_argument("input", type=Path)

    args = parser.parse_args()

    if args.command == "validate":
        manager = NirmalamDataManager.from_interchange(args.input)
        print(json.dumps(manager.document.summary(), indent=2))
        return

    if args.command == "summary":
        manager = NirmalamDataManager.from_interchange(args.input)
        print(json.dumps(manager.summary(), indent=2, ensure_ascii=False))
        return

    if args.command == "export-filter":
        manager = NirmalamDataManager.from_interchange(args.input)
        filtered_transactions = filter_transactions(
            manager.document,
            TransactionFilter(
                account_id=args.account_id,
                category=args.category,
                payee=args.payee,
                direction=args.direction,
                include_holding_tank=not args.exclude_holding_tank,
                occurred_after_epoch_ms=args.from_epoch_ms,
                occurred_before_epoch_ms=args.to_epoch_ms,
            ),
        )
        reduced = replace(manager.document, transactions=filtered_transactions)
        write_interchange(args.output, reduced)
        print(json.dumps({"written": str(args.output), "transactions": len(filtered_transactions)}, indent=2))
        return

    if args.command == "inspect-ndf":
        manifest = inspect_ndf_manifest(args.input)
        print(json.dumps(manifest.__dict__, indent=2))


if __name__ == "__main__":
    main()
