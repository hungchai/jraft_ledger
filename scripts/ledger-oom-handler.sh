#!/bin/sh
PID="$1"
TS=$(date -u +%Y%m%dT%H%M%SZ)
OUT="/var/lib/ledger/jstack-${PID}-${TS}.log"
jstack -l "$PID" > "$OUT" 2>&1 || true
