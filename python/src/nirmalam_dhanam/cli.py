from __future__ import annotations
import argparse
import json
from .document import Dhanam, DhanamError


def main() -> None:
    parser = argparse.ArgumentParser(prog="dhanam", description="Inspect and edit portable .dhanam files")
    parser.add_argument("file")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("validate")
    sub.add_parser("networth")
    sub.add_parser("accounts")
    tx = sub.add_parser("transactions")
    tx.add_argument("--account")
    assets = sub.add_parser("assets")
    assets.add_argument("--history")
    args = parser.parse_args()
    try:
        db = Dhanam.open(args.file)
        if args.command == "validate":
            db.validate(); print("OK")
        elif args.command == "networth":
            print(db.networth_current())
        elif args.command == "accounts":
            print(json.dumps(db.accounts.list(include_archived=True), indent=2, ensure_ascii=False))
        elif args.command == "transactions":
            print(json.dumps(db.transactions.list(account_id=args.account), indent=2, ensure_ascii=False))
        elif args.command == "assets":
            rows = db.assets.history(args.history) if args.history else db.assets.list()
            print(json.dumps(rows, indent=2, ensure_ascii=False))
    except DhanamError as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    main()
