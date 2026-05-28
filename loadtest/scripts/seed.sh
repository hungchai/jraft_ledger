#!/usr/bin/env bash
# Seeds the loadtest cluster with:
#   * N master COMPANY accounts (load-m-1..N)            — DEBIT side (auto-topup)
#   * N sub CLIENT accounts     (load-s-1..N)            — CREDIT side
#   * 1 SUSPENSE account        (load-suspense)          — deposit/withdrawal flows
#   * Writes target/loadtest-accounts.json that the k6 scripts read at start-of-test
#
# Idempotent against the API: creates use unique requestIds keyed by epoch + index.
# Re-running with the same N will hit idempotency replays for any duplicate requestId,
# but the produced JSON file is overwritten each run.
#
# Env:
#   BASE=http://localhost:8081   ledger node base URL (must be leader, or follower will 503)
#   NODES=http://localhost:8081,http://localhost:8082,http://localhost:8083  for leader auto-resolve
#   ACCOUNT_COUNT=1000           N master + N sub

set -euo pipefail

NODES="${NODES:-http://localhost:8081,http://localhost:8082,http://localhost:8083}"
ACCOUNT_COUNT="${ACCOUNT_COUNT:-1000}"
CURRENCY="${CURRENCY:-USD}"

# Resolve leader if BASE not pinned.
if [[ -z "${BASE:-}" ]]; then
    IFS=',' read -ra NODE_ARR <<< "$NODES"
    for n in "${NODE_ARR[@]}"; do
        role=$(curl -fs -m 2 "$n/health" 2>/dev/null | jq -r '.role // empty' 2>/dev/null || true)
        if [[ "$role" == "LEADER" ]]; then BASE="$n"; break; fi
    done
    : "${BASE:?could not auto-resolve LEADER from NODES=$NODES; set BASE explicitly}"
fi
echo ">>> using leader: $BASE"

EPOCH=$(date +%s)

create_account() {
    local payload="$1"
    curl -fs -X POST "$BASE/ledger/accounts" \
        -H "Content-Type: application/json" \
        -d "$payload" > /dev/null
}

echo ">>> creating 1 suspense account"
create_account "$(cat <<JSON
{
  "requestId": "seed-suspense-$EPOCH",
  "accountId": "load-suspense",
  "accountType": "SUSPENSE",
  "displayName": "Loadtest Suspense",
  "balanceInitializations": [{"balanceType": "AVAILABLE_BALANCE", "currency": "$CURRENCY"}]
}
JSON
)"

echo ">>> creating $ACCOUNT_COUNT master COMPANY accounts (DEBIT hotspots)"
for i in $(seq 1 "$ACCOUNT_COUNT"); do
    create_account "$(cat <<JSON
{
  "requestId": "seed-m-$i-$EPOCH",
  "accountId": "load-m-$i",
  "accountType": "COMPANY",
  "displayName": "Loadtest Master $i",
  "balanceInitializations": [{"balanceType": "AVAILABLE_BALANCE", "currency": "$CURRENCY"}]
}
JSON
)"
    if (( i % 100 == 0 )); then echo "    ..master $i"; fi
done

echo ">>> creating $ACCOUNT_COUNT sub CLIENT accounts (CREDIT receivers)"
for i in $(seq 1 "$ACCOUNT_COUNT"); do
    create_account "$(cat <<JSON
{
  "requestId": "seed-s-$i-$EPOCH",
  "accountId": "load-s-$i",
  "accountType": "CLIENT",
  "displayName": "Loadtest Sub $i",
  "ownerId": "CUST-$i",
  "balanceInitializations": [{"balanceType": "AVAILABLE_BALANCE", "currency": "$CURRENCY"}]
}
JSON
)"
    if (( i % 100 == 0 )); then echo "    ..sub $i"; fi
done

OUT_DIR="$(cd "$(dirname "$0")/../.." && pwd)/target"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/loadtest-accounts.json"

echo ">>> emitting account list to $OUT"
{
    printf '['
    printf '{"id":"load-suspense","role":"suspense"}'
    for i in $(seq 1 "$ACCOUNT_COUNT"); do
        printf ',{"id":"load-m-%d","role":"master"}' "$i"
        printf ',{"id":"load-s-%d","role":"sub"}'    "$i"
    done
    printf ']\n'
} > "$OUT"

TOTAL=$(jq 'length' < "$OUT")
echo ">>> seed complete"
echo "  total=$TOTAL  master=$ACCOUNT_COUNT  sub=$ACCOUNT_COUNT  suspense=1"
echo "  $OUT"
