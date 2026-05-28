#!/usr/bin/env bash
# Run the full backend-ledger-spec matrix against jraft-ledger.
# Short durations by default so the matrix completes in <20 min on a laptop.
# Override per-scenario via env vars before invoking.
#
# Usage:
#   loadtest/scripts/run-all.sh
#   LT_LOOSE_THRESHOLDS=1 loadtest/scripts/run-all.sh   # don't fail on 503 storms
#
# Pre-reqs: cluster up, accounts seeded.

set -uo pipefail

cd "$(dirname "$0")/../.."

SCRIPT="loadtest/scripts/run-local.sh"

run() {
    local name="$1"; shift
    echo "============================================================"
    echo "  $name"
    echo "============================================================"
    env "$@" bash "$SCRIPT" "$name" || echo "[warn] $name exited non-zero (continuing matrix)"
}

# Override durations for the short-matrix mode.
run 03-internal-transfer    LT_DURATION="${DUR_03:-2m}"   LT_RATE="${RATE_03:-1000}"
run 05-balance-read         LT_DURATION="${DUR_05:-1m}"   LT_RATE="${RATE_05:-2000}"
run 06-idempotency-replay   LT_DURATION="${DUR_06:-2m}"   LT_ITERATIONS="${ITER_06:-500}"
run 07-mixed-realistic      LT_MIXED_DURATION="${DUR_07:-3m}"   LT_WRITE_RATE="${W_07:-700}" LT_READ_RATE="${R_07:-300}"
run 08-burst-5k             # ramps in stages, fixed
run 09-burst-10k            LT_DURATION="${DUR_09:-60s}"  LT_VUS="${VUS_09:-400}"
run 10-all-types-2k         LT_DURATION="${DUR_10:-60s}"  LT_VUS="${VUS_10:-30}"
run 14-hot-omnibus          LT_DURATION="${DUR_14:-60s}"  LT_VUS="${VUS_14:-50}" HOT_COUNT="${HOT_COUNT:-5}" HOT_RATIO="${HOT_RATIO:-0.9}"

echo "============================================================"
echo "  matrix done — reports in loadtest/reports/"
echo "============================================================"
ls -1t loadtest/reports/ | head -10
