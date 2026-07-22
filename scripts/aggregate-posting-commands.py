#!/usr/bin/env python3
"""Aggregate posting commands into expected balances.

Input: JSONL — one POST /ledger/postings body per line (intent or COMPLETED).
Output: TSV — account_id, balance_type, currency, expected_amount

Sign convention matches journal_line aggregation (NORMAL_CREDIT default).
Deduplicates by requestId (first occurrence wins — idempotent retries).
Optional --applied-ids: only count postings whose requestId appears in journal
(so rejected intents and duplicate retries are excluded).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict


def _apply_line(
    sums: dict,
    account: str,
    balance_type: str,
    currency: str,
    entry_type: str,
    amount: str,
    sign: str = "NORMAL_CREDIT",
) -> None:
    try:
        value = float(amount)
    except (TypeError, ValueError):
        return
    if entry_type == "CREDIT":
        if sign == "NORMAL_DEBIT":
            sums[account][balance_type][currency] -= value
        else:
            sums[account][balance_type][currency] += value
    elif entry_type == "DEBIT":
        if sign == "NORMAL_DEBIT":
            sums[account][balance_type][currency] += value
        else:
            sums[account][balance_type][currency] -= value


def _process_posting(sums: dict, posting: dict) -> None:
    for leg in posting.get("legs") or []:
        leg_amount = leg.get("amount", "0")
        leg_currency = leg.get("currency", "")
        for line in leg.get("lines") or []:
            account = line.get("accountId", "")
            if not account:
                continue
            balance_type = line.get("balanceType", "AVAILABLE_BALANCE")
            currency = line.get("currency") or leg_currency
            entry_type = line.get("entryType", "")
            _apply_line(sums, account, balance_type, currency, entry_type, leg_amount)


def _parse_posting_line(raw: str) -> dict | None:
    raw = raw.strip()
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        pass
    # k6 Go logger escapes quotes and wraps msg="...JSON..." source=console
    s = re.sub(r"\s+source=console\s*$", "", raw)
    s = s.replace('\\"', '"')
    while s.endswith('"'):
        s = s[:-1]
    try:
        return json.loads(s)
    except json.JSONDecodeError:
        return None


def aggregate(
    path: str,
    applied_ids: set[str] | None = None,
) -> dict[tuple[str, str, str], float]:
    sums: dict = defaultdict(lambda: defaultdict(lambda: defaultdict(float)))
    seen_request_ids: set[str] = set()
    skipped = 0
    filtered = 0
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            posting = _parse_posting_line(raw)
            if posting is None:
                skipped += 1
                continue
            request_id = posting.get("requestId") or ""
            if request_id:
                if request_id in seen_request_ids:
                    continue
                seen_request_ids.add(request_id)
                if applied_ids is not None and request_id not in applied_ids:
                    filtered += 1
                    continue
            _process_posting(sums, posting)
    if skipped:
        print(f"warning: skipped {skipped} malformed command lines", file=sys.stderr)
    if filtered:
        print(
            f"warning: excluded {filtered} intents not present in journal",
            file=sys.stderr,
        )
    flat: dict[tuple[str, str, str], float] = {}
    for account in sums:
        for balance_type in sums[account]:
            for currency in sums[account][balance_type]:
                flat[(account, balance_type, currency)] = sums[account][balance_type][currency]
    return flat


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ledger", help="posting-commands JSONL")
    parser.add_argument(
        "--applied-ids",
        metavar="FILE",
        help="one request_id per line; only aggregate matching postings",
    )
    args = parser.parse_args()
    applied_ids: set[str] | None = None
    if args.applied_ids:
        with open(args.applied_ids, encoding="utf-8") as handle:
            applied_ids = {line.strip() for line in handle if line.strip()}
    flat = aggregate(args.ledger, applied_ids)
    for account, balance_type, currency in sorted(flat):
        print(f"{account}\t{balance_type}\t{currency}\t{flat[(account, balance_type, currency)]}")


if __name__ == "__main__":
    main()
