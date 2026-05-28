#!/usr/bin/env bash
# Run a k6 scenario against a locally-running jraft-ledger cluster.
#
# Usage:
#   run-local.sh                              # default: 07-mixed-realistic
#   run-local.sh 03-internal-transfer
#   LT_DURATION=2m run-local.sh 08-burst-5k
#
# Pre-reqs:
#   * docker compose up -d mysql kafka ledger1 ledger2 ledger3
#   * loadtest/scripts/seed.sh   (creates target/loadtest-accounts.json)
#   * k6 installed: brew install k6

set -euo pipefail

SCENARIO="${1:-07-mixed-realistic}"
SCRIPT="$(dirname "$0")/../k6/scenarios/${SCENARIO}.js"

if [[ ! -f "$SCRIPT" ]]; then
    echo "No such scenario: $SCRIPT" >&2
    echo "Available scenarios:" >&2
    ls "$(dirname "$0")/../k6/scenarios/" >&2
    exit 1
fi

NODES="${NODES:-http://localhost:8081,http://localhost:8082,http://localhost:8083}"
LEDGER_ENDPOINT="${LEDGER_ENDPOINT:-${NODES%%,*}}"

TS="$(date +%Y%m%d-%H%M%S)"
REPORT_DIR="$(dirname "$0")/../reports/${TS}-${SCENARIO}"
mkdir -p "$REPORT_DIR"
LOG="$REPORT_DIR/stdout.log"
SUMMARY="$REPORT_DIR/summary.json"

echo ">>> running $SCENARIO"
echo ">>> NODES=$NODES"
echo ">>> LEDGER_ENDPOINT=$LEDGER_ENDPOINT"
echo ">>> report -> $REPORT_DIR"

k6 run \
    --env NODES="$NODES" \
    --env LEDGER_ENDPOINT="$LEDGER_ENDPOINT" \
    --env LT_DURATION="${LT_DURATION:-}" \
    --env LT_RATE="${LT_RATE:-}" \
    --env LT_MIXED_DURATION="${LT_MIXED_DURATION:-}" \
    --env LT_WRITE_RATE="${LT_WRITE_RATE:-}" \
    --env LT_READ_RATE="${LT_READ_RATE:-}" \
    --env LT_ITERATIONS="${LT_ITERATIONS:-}" \
    --env LT_VUS="${LT_VUS:-}" \
    --env HOT_COUNT="${HOT_COUNT:-}" \
    --env HOT_RATIO="${HOT_RATIO:-}" \
    --env LT_LOOSE_THRESHOLDS="${LT_LOOSE_THRESHOLDS:-}" \
    --env CURRENCY="${CURRENCY:-USD}" \
    --summary-trend-stats="min,med,avg,p(50),p(95),p(99),p(99.9),max" \
    --summary-export="$SUMMARY" \
    "$SCRIPT" 2>&1 | tee "$LOG"

echo ">>> report:"
echo "     $LOG"
echo "     $SUMMARY"
