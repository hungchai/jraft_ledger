#!/usr/bin/env python3
"""Extract COMPLETED posting JSON from k6 stdout log lines (COMMAND_LEDGER: prefix)."""
from __future__ import annotations

import json
import re
import sys


def normalize_payload(raw: str) -> str:
    s = re.sub(r"\s+source=console\s*$", "", raw.strip())
    s = s.replace('\\"', '"')
    while s.endswith('"'):
        s = s[:-1]
    return s


def extract_from_stream(stream) -> tuple[int, int]:
    written = 0
    skipped = 0
    for line in stream:
        if "COMMAND_LEDGER:" not in line:
            continue
        payload = normalize_payload(line.split("COMMAND_LEDGER:", 1)[1])
        if not payload:
            skipped += 1
            continue
        try:
            obj = json.loads(payload)
        except json.JSONDecodeError:
            skipped += 1
            continue
        print(json.dumps(obj, separators=(",", ":")))
        written += 1
    return written, skipped


def main() -> None:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <k6-output.log>", file=sys.stderr)
        sys.exit(1)
    written = 0
    skipped = 0
    with open(sys.argv[1], encoding="utf-8", errors="replace") as handle:
        written, skipped = extract_from_stream(handle)
    if skipped:
        print(f"warning: skipped {skipped} malformed COMMAND_LEDGER lines", file=sys.stderr)
    if written == 0:
        print("warning: no COMMAND_LEDGER lines found in k6 output", file=sys.stderr)


if __name__ == "__main__":
    main()
