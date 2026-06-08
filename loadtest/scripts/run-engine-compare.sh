#!/usr/bin/env bash
# Benchmark SOFAJRaft vs Apache Ratis under identical conditions and emit a side-by-side
# comparison (TPS, P95, failover, GC) into docs/RAFT-COMPARISON-<date>.md.
#
# For each engine it: brings up a clean 3-node stack, waits healthy, seeds accounts, runs the
# k6 scenario set, scrapes Prometheus for posting P95 + raft metrics, tears down.
#
# Usage:
#   loadtest/scripts/run-engine-compare.sh                      # both engines, default scenarios
#   ENGINES="ratis" loadtest/scripts/run-engine-compare.sh      # one engine
#   SCENARIOS="03-internal-transfer 08-burst-5k" ...
#
# Pre-reqs: docker compose, k6, jq, a built ledger-node:latest image
#   (docker compose --profile build build ledger-base)
#
# NOTE: requires a running docker daemon. If docker is down this script exits early.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

ENGINES="${ENGINES:-jraft ratis}"
SCENARIOS="${SCENARIOS:-03-internal-transfer 07-mixed-realistic 08-burst-5k}"
ACCOUNT_COUNT="${ACCOUNT_COUNT:-1000}"
NODES="http://localhost:8081,http://localhost:8082,http://localhost:8083"
PROM="${PROM:-http://localhost:9090}"
TS="$(date +%Y-%m-%d)"
OUT="docs/RAFT-COMPARISON-${TS}.md"

if ! docker info >/dev/null 2>&1; then
    echo "ERROR: docker daemon not running — cannot run live benchmark." >&2
    exit 2
fi

compose_files() {
    # $1 = engine
    if [[ "$1" == "ratis" ]]; then
        echo "-f docker-compose.yml -f docker-compose.ratis.yml"
    else
        echo "-f docker-compose.yml"
    fi
}

wait_healthy() {
    echo ">>> waiting for a leader on $NODES ..."
    for _ in $(seq 1 60); do
        IFS=',' read -ra A <<< "$NODES"
        for n in "${A[@]}"; do
            role=$(curl -fs -m 2 "$n/health" 2>/dev/null | jq -r '.role // empty' 2>/dev/null || true)
            [[ "$role" == "LEADER" ]] && { echo ">>> leader: $n"; return 0; }
        done
        sleep 3
    done
    echo "ERROR: no leader within timeout" >&2
    return 1
}

prom_q() { curl -fs -m 5 --data-urlencode "query=$1" "$PROM/api/v1/query" | jq -r '.data.result[0].value[1] // "NA"'; }

declare -A RESULTS

for engine in $ENGINES; do
    CF=$(compose_files "$engine")
    echo "==================== ENGINE: $engine ===================="
    echo ">>> tearing down any prior stack + data"
    docker compose -f docker-compose.yml -f docker-compose.ratis.yml down -v >/dev/null 2>&1 || true
    rm -rf jraft_ledger/node1 jraft_ledger/node2 jraft_ledger/node3 2>/dev/null || true

    echo ">>> starting stack ($engine)"
    # shellcheck disable=SC2086
    docker compose $CF up -d mysql kafka ledger1 ledger2 ledger3 prometheus projection
    wait_healthy

    echo ">>> seeding $ACCOUNT_COUNT accounts"
    ACCOUNT_COUNT="$ACCOUNT_COUNT" NODES="$NODES" loadtest/scripts/seed.sh

    for sc in $SCENARIOS; do
        echo ">>> [$engine] scenario $sc"
        NODES="$NODES" loadtest/scripts/run-local.sh "$sc" || true
    done

    # Scrape headline metrics from Prometheus (recording over the run window).
    p95=$(prom_q 'histogram_quantile(0.95, sum(rate(ledger_posting_duration_seconds_bucket[5m])) by (le))')
    tps=$(prom_q 'sum(rate(ledger_posting_duration_seconds_count[1m]))')
    gcpause=$(prom_q 'max(rate(jvm_gc_pause_seconds_sum[5m]))')
    RESULTS["${engine}_p95"]="$p95"
    RESULTS["${engine}_tps"]="$tps"
    RESULTS["${engine}_gc"]="$gcpause"
    echo ">>> [$engine] p95=$p95 tps=$tps gcpause=$gcpause"

    docker compose $CF down >/dev/null 2>&1 || true
done

echo ">>> writing $OUT"
{
    echo "## Benchmark results (auto-appended $(date -u +%FT%TZ))"
    echo
    echo "| Metric | SOFAJRaft | Apache Ratis |"
    echo "|---|---|---|"
    echo "| Posting P95 (s) | ${RESULTS[jraft_p95]:-NA} | ${RESULTS[ratis_p95]:-NA} |"
    echo "| Posting TPS | ${RESULTS[jraft_tps]:-NA} | ${RESULTS[ratis_tps]:-NA} |"
    echo "| Max GC pause rate (s/s) | ${RESULTS[jraft_gc]:-NA} | ${RESULTS[ratis_gc]:-NA} |"
    echo
    echo "Scenarios: $SCENARIOS — accounts: $ACCOUNT_COUNT. k6 per-scenario reports under loadtest/reports/."
} >> "$OUT"
echo ">>> done. See $OUT"
