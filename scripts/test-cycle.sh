#!/bin/bash
# test-cycle.sh — Standard test procedure for ledger platform
# Usage: ./scripts/test-cycle.sh [--vus N] [--duration M] [--no-flush] [--recon-only]
#
# Procedure:
#   1. Flush MySQL + RocksDB + Raft log
#   2. Start stack (if not running)
#   3. Run k6 stress test
#   4. Reconcile: balances + journals across 3 nodes API + MySQL
#   5. Report findings

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

VUS=10
DURATION=2m
FLUSH=true
RECON_ONLY=false
K6_SCRIPT="$SCRIPT_DIR/k6-posting-stress.js"
BASE_URL=""

usage() {
  echo "Usage: $0 [--vus N] [--duration M] [--no-flush] [--recon-only] [--base-url URL]"
  echo "  --vus N         Number of VUs (default: 10)"
  echo "  --duration M    Test duration (default: 2m)"
  echo "  --no-flush      Skip data flush"
  echo "  --recon-only    Only run reconciliation (skip k6)"
  echo "  --base-url URL  Explicit leader URL (auto-detect if not set)"
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --vus) VUS="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --no-flush) FLUSH=false; shift ;;
    --recon-only) RECON_ONLY=true; shift ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    *) usage ;;
  esac
done

# ============================================================
# Helpers
# ============================================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS=0; FAIL=0; WARN=0

pass()  { echo -e "  ${GREEN}✅ $1${NC}"; PASS=$((PASS+1)); }
fail()  { echo -e "  ${RED}❌ $1${NC}"; FAIL=$((FAIL+1)); }
warn()  { echo -e "  ${YELLOW}⚠️  $1${NC}"; WARN=$((WARN+1)); }

find_leader() {
  for port in 8081 8082 8083; do
    local url="http://localhost:$port"
    local role
    role=$(curl -s --max-time 2 "$url/health" 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin).get('role',''))" 2>/dev/null || echo "")
    if [ "$role" = "LEADER" ]; then
      echo "$url"
      return
    fi
  done
  echo "http://localhost:8081"
}

mysql_query() {
  docker exec ledger-mysql mysql -u ledger -pledger123 ledger_view -sN -e "$1" 2>/dev/null
}

api_balance() {
  local node=$1 account=$2 btype=$3 ccy=$4
  curl -s --max-time 5 "${node}/ledger/balances?accountId=${account}&balanceType=${btype}&currency=${ccy}" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('amount','ERR'))" 2>/dev/null || echo "ERR"
}

raft_status() {
  local node=$1 field=$2
  curl -s --max-time 5 "${node}/ledger/cluster/raft-status" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('${field}','ERR'))" 2>/dev/null || echo "ERR"
}

# ============================================================
# 1. Flush data
# ============================================================

if $FLUSH && ! $RECON_ONLY; then
  echo "=== Step 1: Flush data ==="
  docker compose -f "$PROJECT_DIR/docker-compose.yml" down 2>/dev/null || true
  rm -rf "$PROJECT_DIR/jraft_ledger/mysql" \
         "$PROJECT_DIR/jraft_ledger/node1/rocksdb" "$PROJECT_DIR/jraft_ledger/node1/raft" \
         "$PROJECT_DIR/jraft_ledger/node2/rocksdb" "$PROJECT_DIR/jraft_ledger/node2/raft" \
         "$PROJECT_DIR/jraft_ledger/node3/rocksdb" "$PROJECT_DIR/jraft_ledger/node3/raft" \
         "$PROJECT_DIR/jraft_ledger/kafka"
  pass "MySQL + RocksDB + Raft log + Kafka flushed"
fi

# ============================================================
# 2. Start stack
# ============================================================

if ! $RECON_ONLY; then
  echo ""
  echo "=== Step 2: Start stack ==="
  docker compose -f "$PROJECT_DIR/docker-compose.yml" up -d 2>&1 | tail -1

  # Wait for leader
  echo -n "Waiting for leader"
  LEADER=""
  for i in $(seq 1 30); do
    LEADER=$(find_leader)
    if curl -s --max-time 2 "$LEADER/health" 2>/dev/null | grep -q "LEADER"; then
      echo ""
      pass "Leader elected: $LEADER"
      break
    fi
    sleep 2
    echo -n "."
  done

  if [ -z "$LEADER" ]; then
    fail "No leader elected after 60s"
    exit 1
  fi

  [ -z "$BASE_URL" ] && BASE_URL="$LEADER"

  # Verify all nodes
  for port in 8081 8082 8083; do
    NODE="http://localhost:$port"
    if curl -s --max-time 2 "$NODE/health" 2>/dev/null | grep -q "UP"; then
      pass "Node $NODE healthy"
    else
      warn "Node $NODE not responding"
    fi
  done
fi

# ============================================================
# 3. Run k6
# ============================================================

if ! $RECON_ONLY; then
  echo ""
  echo "=== Step 3: k6 stress test ($VUS VUs, $DURATION) ==="
  echo "Leader: $BASE_URL"

  K6_OUTPUT=$(k6 run --vus "$VUS" --duration "$DURATION" \
    -e BASE_URL="$BASE_URL" \
    "$K6_SCRIPT" 2>&1) || true

  # Extract key metrics
  ITERATIONS=$(echo "$K6_OUTPUT" | grep "iterations\.\.\." | grep -oE '[0-9]+' | head -1)
  FAILED=$(echo "$K6_OUTPUT" | grep "http_req_failed" | tail -1 | grep -oE '[0-9.]+%' | head -1)
  P50=$(echo "$K6_OUTPUT" | grep "p(50)=" | tail -1 | grep -oE 'p\(50\)=[0-9.]+ms' | head -1)
  P95=$(echo "$K6_OUTPUT" | grep "p(95)=" | tail -1 | grep -oE 'p\(95\)=[0-9.]+ms' | head -1)

  echo "  Iterations: ${ITERATIONS:-?}"
  echo "  Failed: ${FAILED:-?}"
  echo "  ${P50:-p50=?}  ${P95:-p95=?}"

  if [ -n "$FAILED" ] && [ "$FAILED" != "0.00%" ]; then
    warn "Failures detected: $FAILED"
  else
    pass "0% failures"
  fi
fi

# ============================================================
# 4. Wait for projection catch-up (if applicable)
# ============================================================

echo ""
echo "=== Step 4: Wait for projection catch-up ==="

SM_JNLS=$(raft_status "$BASE_URL" "smJournalSeq")
MYSQL_JNLS=$(mysql_query "SELECT COUNT(*) FROM journal;")
LAG=$((SM_JNLS - MYSQL_JNLS))

if [ "$LAG" -gt 100 ]; then
  echo -n "SM=$SM_JNLS MySQL=$MYSQL_JNLS lag=$LAG — waiting"
  for i in $(seq 1 60); do
    sleep 5
    MYSQL_JNLS=$(mysql_query "SELECT COUNT(*) FROM journal;")
    LAG=$((SM_JNLS - MYSQL_JNLS))
    echo -n "."
    if [ "$LAG" -le 10 ]; then break; fi
  done
  echo ""
fi

if [ "$LAG" -le 10 ]; then
  pass "Projection caught up (lag=$LAG)"
else
  warn "Projection still lagging: $LAG journals behind"
fi

# ============================================================
# 5. Reconcile
# ============================================================

echo ""
echo "=== Step 5: Reconciliation ==="

NODES=("http://localhost:8081" "http://localhost:8082" "http://localhost:8083")
ACCOUNTS=("STRESS-HOT-CO-001" "STRESS-CLI-0001" "STRESS-CLI-0002")
CURRENCIES=("USDT" "BTC")

# --- Raft consistency ---
echo ""
echo "--- Raft consistency ---"
LA1=$(raft_status "${NODES[0]}" "lastAppliedIndex")
LA2=$(raft_status "${NODES[1]}" "lastAppliedIndex")
LA3=$(raft_status "${NODES[2]}" "lastAppliedIndex")

if [ "$LA1" = "$LA2" ] && [ "$LA2" = "$LA3" ]; then
  pass "lastAppliedIndex identical: $LA1"
else
  fail "lastAppliedIndex diverged: $LA1 / $LA2 / $LA3"
fi

SM1=$(raft_status "${NODES[0]}" "smJournalSeq")
SM2=$(raft_status "${NODES[1]}" "smJournalSeq")
SM3=$(raft_status "${NODES[2]}" "smJournalSeq")
DIFF_SM=$((SM1 - SM2)); DIFF_SM="${DIFF_SM#-}"
if [ "$DIFF_SM" -le 10 ]; then
  pass "smJournalSeq within 10: $SM1 / $SM2 / $SM3"
else
  fail "smJournalSeq diverged: $SM1 / $SM2 / $SM3 (diff=$DIFF_SM)"
fi

# --- API balance cross-node ---
echo ""
echo "--- API balance cross-node ---"
for acc in "${ACCOUNTS[@]}"; do
  for ccy in "${CURRENCIES[@]}"; do
    V1=$(api_balance "${NODES[0]}" "$acc" "AVAILABLE_BALANCE" "$ccy")
    V2=$(api_balance "${NODES[1]}" "$acc" "AVAILABLE_BALANCE" "$ccy")
    V3=$(api_balance "${NODES[2]}" "$acc" "AVAILABLE_BALANCE" "$ccy")
    if [ "$V1" = "$V2" ] && [ "$V2" = "$V3" ]; then
      pass "$acc $ccy: identical ($V1)"
    else
      fail "$acc $ccy: $V1 / $V2 / $V3"
    fi
  done
done

# --- MySQL vs Leader API (Hotspot) ---
echo ""
echo "--- MySQL vs Leader API ---"
for ccy in "${CURRENCIES[@]}"; do
  API_VAL=$(api_balance "$BASE_URL" "STRESS-HOT-CO-001" "AVAILABLE_BALANCE" "$ccy")
  MYSQL_VAL=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='STRESS-HOT-CO-001' AND currency='$ccy';")
  if [ "$API_VAL" = "$MYSQL_VAL" ]; then
    pass "Hotspot $ccy: API=$API_VAL MySQL=$MYSQL_VAL"
  else
    fail "Hotspot $ccy: API=$API_VAL MySQL=$MYSQL_VAL"
  fi
done

# --- MySQL journal count vs SM ---
echo ""
echo "--- Journal count ---"
SM_JNLS=$(raft_status "$BASE_URL" "smJournalSeq")
MYSQL_JNLS=$(mysql_query "SELECT COUNT(*) FROM journal;")
if [ "$SM_JNLS" = "$MYSQL_JNLS" ]; then
  pass "Journal count: SM=$SM_JNLS MySQL=$MYSQL_JNLS"
else
  warn "Journal count: SM=$SM_JNLS MySQL=$MYSQL_JNLS (lag=$((SM_JNLS - MYSQL_JNLS)))"
fi

# --- MySQL event count ---
MYSQL_EVENTS=$(mysql_query "SELECT COUNT(*) FROM projection_event_log;")
echo "  MySQL events: $MYSQL_EVENTS"

# --- Sample client MySQL vs Leader ---
echo ""
echo "--- Client MySQL vs Leader ---"
for acc in STRESS-CLI-0001 STRESS-CLI-0002 STRESS-CLI-0050 STRESS-CLI-0100; do
  for ccy in "${CURRENCIES[@]}"; do
    API_VAL=$(api_balance "$BASE_URL" "$acc" "AVAILABLE_BALANCE" "$ccy")
    MYSQL_VAL=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='$acc' AND currency='$ccy';")
    if [ "$API_VAL" = "$MYSQL_VAL" ]; then
      pass "$acc $ccy: match ($API_VAL)"
    else
      fail "$acc $ccy: API=$API_VAL MySQL=$MYSQL_VAL"
    fi
  done
done

# ============================================================
# 6. Summary
# ============================================================

echo ""
echo "========================================"
echo "  Results: $PASS passed, $FAIL failed, $WARN warnings"
echo "========================================"

if [ "$FAIL" -eq 0 ]; then
  echo -e "${GREEN}TEST CYCLE PASSED${NC}"
  exit 0
else
  echo -e "${RED}TEST CYCLE FAILED${NC}"
  exit 1
fi
