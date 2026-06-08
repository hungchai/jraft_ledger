#!/usr/bin/env bash
# Match backend-ledger's run-hot-matrix.sh exactly:
#   random 1k, random 2k, hot1 2k, random 5k, hot1 5k
# All constant-arrival-rate, 30s each.
#
# Pre-reqs: cluster up, accounts seeded.

set -uo pipefail
cd "$(dirname "$0")/../.."

NODES="${NODES:-http://localhost:8081,http://localhost:8082,http://localhost:8083}"
OUT="loadtest/reports/$(date +%Y%m%d-%H%M%S)-matrix-bl"
mkdir -p "$OUT"

# Resolve leader for warm-up
LEADER=""
IFS=',' read -ra NODE_ARR <<< "$NODES"
for n in "${NODE_ARR[@]}"; do
    if curl -fs -m 2 "$n/health" 2>/dev/null | grep -q LEADER; then LEADER="$n"; break; fi
done
echo ">>> leader=$LEADER"

# 5s warm-up (not recorded)
LT_DURATION=5s LT_RATE=200 LT_LOOSE_THRESHOLDS=1 NODES="$NODES" \
    bash loadtest/scripts/run-local.sh 03-internal-transfer >/dev/null 2>&1 || true
rm -rf loadtest/reports/2026*-03-internal-transfer 2>/dev/null

run() {
    local label="$1" scenario="$2" rate="$3" hot_count="${4:-1}"
    echo ""
    echo "============================================================"
    echo "  $label  ($scenario rate=$rate hot=$hot_count duration=30s)"
    echo "============================================================"
    LT_DURATION=30s LT_RATE="$rate" HOT_COUNT="$hot_count" LT_LOOSE_THRESHOLDS=1 NODES="$NODES" \
        bash loadtest/scripts/run-local.sh "$scenario" 2>&1 | tee "$OUT/$label.log" | tail -30
    # Copy the report into our matrix dir
    latest=$(ls -1dt loadtest/reports/2026*-"$scenario" | head -1)
    if [[ -n "$latest" && -f "$latest/summary.json" ]]; then
        cp "$latest/summary.json" "$OUT/$label.json"
    fi
    sleep 3
}

run random-1k 03-internal-transfer 1000 1
run random-2k 03-internal-transfer 2000 1
run hot1-2k   15-hot-arrival       2000 1
run random-5k 03-internal-transfer 5000 1
run hot1-5k   15-hot-arrival       5000 1

echo ""
echo "============================================================"
echo "  SUMMARY ($OUT)"
echo "============================================================"
for f in "$OUT"/*.json; do
    label=$(basename "$f" .json)
    jq -r --arg l "$label" '
      "  \($l):  reqs=\(.metrics.http_reqs.count)  rps=\(.metrics.http_reqs.rate | floor)  fail=\((.metrics.http_req_failed.value // 0) * 100 | tostring | .[0:5])%  p50=\(.metrics.http_req_duration["p(50)"])ms  p95=\(.metrics.http_req_duration["p(95)"])ms  p99=\(.metrics.http_req_duration["p(99)"])ms  p99.9=\(.metrics.http_req_duration["p(99.9)"])ms  max=\(.metrics.http_req_duration.max)ms"
    ' "$f"
done
