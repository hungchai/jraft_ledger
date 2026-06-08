#!/usr/bin/env bash
# OOM Disaster-Recovery test — drives load against a heap-throttled cluster until the leader
# OOMs and restarts, then verifies the DR pass bar:
#   (1) 0 lost committed writes  — every journalId the API acked is still queryable after recovery
#   (2) clean recovery           — a leader is re-elected and serves writes again
#   (3) no cross-node divergence — seeded-account balances match across all 3 nodes
#
# Runs identically for both engines so results are comparable:
#   ENGINE=jraft scripts/oom-dr-test.sh
#   ENGINE=ratis scripts/oom-dr-test.sh
#
# Pre-reqs: docker daemon up, ledger-node:latest image built, jq, curl.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENGINE="${ENGINE:-jraft}"
ACCOUNT_COUNT="${ACCOUNT_COUNT:-200}"
LOAD_REQUESTS="${LOAD_REQUESTS:-200000}"   # upper bound; we stop early once OOM+recovery seen
CURRENCY="${CURRENCY:-USD}"
NODES=(http://localhost:8081 http://localhost:8082 http://localhost:8083)
TS="$(date +%Y%m%d-%H%M%S)"
WORK="$ROOT/logs/oom-dr-${ENGINE}-${TS}"
mkdir -p "$WORK"
ACKED="$WORK/acked-journals.txt"   # journalIds the API confirmed COMPLETED
: > "$ACKED"

if ! docker info >/dev/null 2>&1; then
    echo "ERROR: docker daemon not running — cannot run OOM DR test." >&2
    exit 2
fi

CF=(-f docker-compose.yml -f docker-compose.oom.yml)
[[ "$ENGINE" == "ratis" ]] && CF=(-f docker-compose.yml -f docker-compose.ratis.yml -f docker-compose.oom.yml)

leader() {
    for n in "${NODES[@]}"; do
        r=$(curl -fs -m 2 "$n/health" 2>/dev/null | jq -r '.role // empty' 2>/dev/null || true)
        [[ "$r" == "LEADER" ]] && { echo "$n"; return 0; }
    done
    return 1
}

wait_leader() { for _ in $(seq 1 "${1:-60}"); do L=$(leader) && { echo "$L"; return 0; }; sleep 2; done; return 1; }

echo "==================== OOM DR TEST — engine=$ENGINE ===================="
echo ">>> clean start"
docker compose -f docker-compose.yml -f docker-compose.ratis.yml -f docker-compose.oom.yml down -v >/dev/null 2>&1 || true
rm -rf jraft_ledger/node1 jraft_ledger/node2 jraft_ledger/node3 2>/dev/null || true
docker compose "${CF[@]}" up -d mysql kafka ledger1 ledger2 ledger3 projection

L=$(wait_leader 90) || { echo "ERROR: no leader at startup"; exit 1; }
echo ">>> leader: $L"
ACCOUNT_COUNT="$ACCOUNT_COUNT" NODES="$(IFS=,; echo "${NODES[*]}")" loadtest/scripts/seed.sh

echo ">>> driving load (until OOM+recovery observed, max $LOAD_REQUESTS reqs)"
restart_seen=0
sent=0
for i in $(seq 1 "$LOAD_REQUESTS"); do
    L=$(leader) || { sleep 1; restart_seen=1; continue; }   # leader gone == likely OOM/election
    req="oom-${TS}-${i}"
    body=$(cat <<JSON
{"requestId":"$req","businessEventType":"INTERNAL_TRANSFER","businessEventRef":"oom-$i","valueDate":"$(date +%F)","legs":[{"legId":"l1","balanceType":"AVAILABLE_BALANCE","amount":"1.00","currency":"$CURRENCY","lines":[{"accountId":"load-m-1","balanceType":"AVAILABLE_BALANCE","subAccount":"CURRENT","entryType":"CREDIT","memo":"oom"},{"accountId":"load-s-1","balanceType":"AVAILABLE_BALANCE","subAccount":"CURRENT","entryType":"DEBIT","memo":"oom"}]}]}
JSON
)
    jid=$(curl -fs -m 3 -X POST "$L/ledger/postings" -H 'Content-Type: application/json' -d "$body" 2>/dev/null \
            | jq -r 'select(.status=="COMPLETED") | .journalId // empty' 2>/dev/null || true)
    [[ -n "$jid" ]] && { echo "$jid" >> "$ACKED"; sent=$((sent+1)); }
    # stop ~2s after we've seen a restart AND a leader is back
    if [[ "$restart_seen" == "1" ]] && leader >/dev/null; then
        [[ $((i % 200)) -eq 0 ]] && break
    fi
done
acked_count=$(wc -l < "$ACKED" | tr -d ' ')
echo ">>> acked COMPLETED journals: $acked_count"

echo ">>> confirming OOM actually happened (container restart count / hs_err)"
for c in ledger-node-1 ledger-node-2 ledger-node-3; do
    rc=$(docker inspect "$c" --format '{{.RestartCount}}' 2>/dev/null || echo "?")
    echo "    $c restartCount=$rc"
done
ls -1 jraft_ledger/node*/hs_err_pid*.log 2>/dev/null && echo "    (heap dump / hs_err present — OOM confirmed)" || echo "    WARN: no hs_err found — heap may not have been small enough; lower -Xmx in docker-compose.oom.yml"

echo ">>> waiting for clean recovery (leader re-elected)"
L=$(wait_leader 90) || { echo "FAIL: cluster did not recover a leader"; exit 1; }
echo ">>> recovered leader: $L"
sleep 5  # let projection drain

echo ">>> (1) checking 0 lost committed writes — every acked journalId still queryable"
missing=0; checked=0
while read -r jid; do
    [[ -z "$jid" ]] && continue
    checked=$((checked+1))
    found=$(curl -fs -m 3 "$L/ledger/journals/$jid" 2>/dev/null | jq -r '.journalId // empty' 2>/dev/null || true)
    [[ "$found" != "$jid" ]] && missing=$((missing+1))
done < "$ACKED"
echo "    checked=$checked missing=$missing"

echo ">>> (3) checking cross-node balance consistency for load-m-1 / load-s-1"
declare -A bal
div=0
for acc in load-m-1 load-s-1; do
    prev=""
    for n in "${NODES[@]}"; do
        b=$(curl -fs -m 3 "$n/ledger/accounts/$acc/balances?balanceType=AVAILABLE_BALANCE&currency=$CURRENCY" 2>/dev/null \
              | jq -r '.balances[0].balance // .balance // empty' 2>/dev/null || true)
        echo "    $acc @ $n = $b"
        [[ -n "$prev" && -n "$b" && "$b" != "$prev" ]] && div=1
        [[ -n "$b" ]] && prev="$b"
    done
done

echo "==================== RESULT — engine=$ENGINE ===================="
echo "acked_committed=$acked_count  lost=$missing  cross_node_divergence=$div"
if [[ "$missing" -eq 0 && "$div" -eq 0 ]]; then
    echo "PASS: 0 lost committed writes, clean recovery, no divergence."
    rc=0
else
    echo "FAIL: missing=$missing divergence=$div"
    rc=1
fi
echo ">>> artifacts in $WORK"
docker compose "${CF[@]}" down >/dev/null 2>&1 || true
exit $rc
