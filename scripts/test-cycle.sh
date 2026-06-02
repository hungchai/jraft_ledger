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

# Normalize numeric strings: strip trailing zeros, then trailing dot.
# "100000300.0000000000000000" → "100000300"
# "100000300.0" → "100000300"
# "3999.99589552" → "3999.99589552"
normalize() {
  echo "$1" | sed -E 's/0+$//;s/\.$//'
}

# Compare two numeric strings after normalization.
num_eq() {
  [ "$(normalize "$1")" = "$(normalize "$2")" ]
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
echo "  Initial: SM=$SM_JNLS MySQL=$MYSQL_JNLS lag=$LAG"

# Drain-rate / ETA tracking: lag is expected (async Kafka->projection->MySQL
# pipeline). We poll the MySQL journal count, derive projection throughput
# (rows/sec) and an ETA so the operator can see lag is shrinking, not stuck.
PROJ_RATE=0
if [ "$LAG" -gt 10 ]; then
  prev_mysql=$MYSQL_JNLS
  for i in $(seq 1 60); do
    sleep 5
    SM_JNLS=$(raft_status "$BASE_URL" "smJournalSeq")
    MYSQL_JNLS=$(mysql_query "SELECT COUNT(*) FROM journal;")
    LAG=$((SM_JNLS - MYSQL_JNLS))
    PROJ_RATE=$(( (MYSQL_JNLS - prev_mysql) / 5 ))
    prev_mysql=$MYSQL_JNLS
    if [ "$PROJ_RATE" -gt 0 ]; then
      ETA=$((LAG / PROJ_RATE))
      printf "  [%3ds] lag=%-6d drain=%d/s eta=%ds\n" "$((i*5))" "$LAG" "$PROJ_RATE" "$ETA"
    else
      printf "  [%3ds] lag=%-6d drain=0/s eta=stalled\n" "$((i*5))" "$LAG"
    fi
    if [ "$LAG" -le 10 ]; then break; fi
  done
fi

if [ "$LAG" -le 10 ]; then
  pass "Projection caught up (lag=$LAG)"
else
  warn "Projection still lagging: $LAG journals behind (drain=${PROJ_RATE}/s)"
fi
PROJ_LAG_FINAL=$LAG
PROJ_RATE_FINAL=$PROJ_RATE

# ============================================================
# 5. Reconcile
# ============================================================

echo ""
echo "=== Step 5: Reconciliation ==="

NODES=("http://localhost:8081" "http://localhost:8082" "http://localhost:8083")
HOTSPOT_ACC="STRESS-HOT-CO-001"
ACCOUNTS=("$HOTSPOT_ACC" "STRESS-CLI-0001" "STRESS-CLI-0002")
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

# --- 🔴 HOTSPOT ($HOTSPOT_ACC) — mandatory reconciliation rule ---
echo ""
echo "--- 🔴 HOTSPOT: $HOTSPOT_ACC ---"
for ccy in "${CURRENCIES[@]}"; do
  V1=$(api_balance "${NODES[0]}" "$HOTSPOT_ACC" "AVAILABLE_BALANCE" "$ccy")
  V2=$(api_balance "${NODES[1]}" "$HOTSPOT_ACC" "AVAILABLE_BALANCE" "$ccy")
  V3=$(api_balance "${NODES[2]}" "$HOTSPOT_ACC" "AVAILABLE_BALANCE" "$ccy")
  MYSQL_VAL=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='$HOTSPOT_ACC' AND currency='$ccy';")
  LEADER_VAL=$(api_balance "$BASE_URL" "$HOTSPOT_ACC" "AVAILABLE_BALANCE" "$ccy")

  # Cross-node check
  if num_eq "$V1" "$V2" && num_eq "$V2" "$V3"; then
    pass "$ccy cross-node: identical ($(normalize "$V1"))"
  else
    # Compute numeric diffs
    D21=$(python3 -c "print(float('$V2') - float('$V1'))" 2>/dev/null || echo "?")
    D31=$(python3 -c "print(float('$V3') - float('$V1'))" 2>/dev/null || echo "?")
    fail "$ccy cross-node: leader=$V1 node2=$V2(d$D21) node3=$V3(d$D31)"
  fi

  # MySQL vs leader check
  if num_eq "$LEADER_VAL" "$MYSQL_VAL"; then
    pass "$ccy MySQL vs leader: match ($(normalize "$LEADER_VAL"))"
  else
    DM=$(python3 -c "print(float('$MYSQL_VAL') - float('$LEADER_VAL'))" 2>/dev/null || echo "?")
    fail "$ccy MySQL vs leader: leader=$LEADER_VAL MySQL=$MYSQL_VAL(d$DM)"
  fi
done

# --- API balance cross-node (clients) ---
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

# --- MySQL events (sharded) ---
echo ""
echo "--- MySQL counts ---"
MYSQL_EVENTS=$(mysql_query \
  "SELECT SUM(cnt) FROM (
     SELECT COUNT(*) AS cnt FROM projection_event_log_0
     UNION ALL SELECT COUNT(*) FROM projection_event_log_1
     UNION ALL SELECT COUNT(*) FROM projection_event_log_2
     UNION ALL SELECT COUNT(*) FROM projection_event_log_3
   ) t;")
echo "  MySQL events (sharded): ${MYSQL_EVENTS:-0}"

MYSQL_JNLS_COUNT=$(mysql_query "SELECT COUNT(*) FROM journal;")
echo "  MySQL journals: $MYSQL_JNLS_COUNT"

MYSQL_LINES=$(mysql_query \
  "SELECT SUM(cnt) FROM (
     SELECT COUNT(*) AS cnt FROM journal_line_0
     UNION ALL SELECT COUNT(*) FROM journal_line_1
     UNION ALL SELECT COUNT(*) FROM journal_line_2
     UNION ALL SELECT COUNT(*) FROM journal_line_3
   ) t;")
echo "  MySQL journal_lines (sharded): ${MYSQL_LINES:-0}"

MYSQL_BALANCES=$(mysql_query "SELECT COUNT(*) FROM account_balance;")
echo "  MySQL balances: $MYSQL_BALANCES"

# --- Full MySQL balance reconciliation (all accounts) ---
echo ""
echo "--- Full MySQL balance reconciliation ---"
RECON_FILE=$(mktemp)
MYSQL_RECON=0
MYSQL_RECON_FAIL=0
MYSQL_RECON_SKIP=0

# Get all distinct account/ccy pairs from MySQL that have a balance
mysql_query "SELECT DISTINCT account_account_id, currency FROM account_balance ORDER BY account_account_id, currency;" 2>/dev/null | while read -r acc ccy; do
  [ -z "$acc" ] && continue
  API_VAL=$(api_balance "$BASE_URL" "$acc" "AVAILABLE_BALANCE" "$ccy")
  MYSQL_VAL=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='$acc' AND currency='$ccy';")

  if [ "$API_VAL" = "ERR" ] || [ -z "$MYSQL_VAL" ]; then
    MYSQL_RECON_SKIP=$((MYSQL_RECON_SKIP + 1))
  elif num_eq "$API_VAL" "$MYSQL_VAL"; then
    MYSQL_RECON=$((MYSQL_RECON + 1))
  else
    MYSQL_RECON_FAIL=$((MYSQL_RECON_FAIL + 1))
    fail "MySQL $acc $ccy: API=$API_VAL MySQL=$MYSQL_VAL"
  fi
done
# Recon counters written to /tmp for parent shell access
echo "MATCH=$MYSQL_RECON MISMATCH=$MYSQL_RECON_FAIL SKIP=$MYSQL_RECON_SKIP" > "$RECON_FILE"

# Fallback: if sharded event_log tables don't exist yet, try uns sharded
if [ "${MYSQL_EVENTS:-0}" = "0" ]; then
  MYSQL_EVENTS=$(mysql_query "SELECT COUNT(*) FROM projection_event_log;")
fi

# --- Sample client MySQL vs Leader ---
echo ""
echo "--- Client MySQL vs Leader ---"
for acc in STRESS-CLI-0001 STRESS-CLI-0002 STRESS-CLI-0050 STRESS-CLI-0100; do
  for ccy in "${CURRENCIES[@]}"; do
    API_VAL=$(api_balance "$BASE_URL" "$acc" "AVAILABLE_BALANCE" "$ccy")
    MYSQL_VAL=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='$acc' AND currency='$ccy';")
    if num_eq "$API_VAL" "$MYSQL_VAL"; then
      pass "$acc $ccy: match ($(normalize "$MYSQL_VAL"))"
    else
      fail "$acc $ccy: API=$API_VAL MySQL=$MYSQL_VAL"
    fi
  done
done

# ============================================================
# 6. Summary
# ============================================================

# ============================================================
# 6. Diagnostic snapshot — capture full node state for debugging
# ============================================================

DIAG_DIR="$PROJECT_DIR/jraft_ledger/diagnostics"
mkdir -p "$DIAG_DIR"
DIAG_FILE="$DIAG_DIR/recon-$(date +%Y%m%d-%H%M%S).log"
{
  echo "=== Diagnostic Snapshot $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
  echo "Test: $VUS VUs × $DURATION"
  echo "Iterations: ${ITERATIONS:-?}  Failed: ${FAILED:-?}  p50=${P50:-?}  p95=${P95:-?}"
  echo ""
  echo "--- Raft Status ---"
  for p in 8081 8082 8083; do
    curl -s "http://localhost:${p}/ledger/cluster/raft-status" 2>/dev/null
    echo ""
  done
  echo ""
  echo "--- STRESS-HOT-CO-001 Balances ---"
  for p in 8081 8082 8083; do
    echo -n ":${p} "
    curl -s "http://localhost:${p}/ledger/balances?accountId=$HOTSPOT_ACC&balanceType=AVAILABLE_BALANCE&currency=USDT" 2>/dev/null
    echo ""
    echo -n ":${p} "
    curl -s "http://localhost:${p}/ledger/balances?accountId=$HOTSPOT_ACC&balanceType=AVAILABLE_BALANCE&currency=BTC" 2>/dev/null
    echo ""
  done
  echo ""
  echo "--- MySQL: $HOTSPOT_ACC ---"
  docker exec ledger-mysql mysql -u ledger -pledger123 ledger_view -t \
    -e "SELECT account_account_id, currency, amount, account_seq FROM account_balance WHERE account_account_id='$HOTSPOT_ACC';" 2>/dev/null
  echo ""
  echo "--- MySQL: Counts ---"
  echo "  journals=$(mysql_query "SELECT COUNT(*) FROM journal;")"
  echo "  balance_rows=$(mysql_query "SELECT COUNT(*) FROM account_balance;")"
  echo "  events=$(mysql_query "SELECT COUNT(*) FROM projection_event_log;")"
  echo "  journal_lines=$MYSQL_LINES"
  echo ""
  echo "--- MySQL: Full balance reconciliation (all accounts) ---"
  MYSQL_RECON_TOTAL=0 MYSQL_RECON_OK=0 MYSQL_RECON_BAD=0 MYSQL_RECON_SKIP=0
  echo "  account_id,currency,api_amount,mysql_amount,status" > /tmp/recon_detail.csv
  mysql_query "SELECT DISTINCT account_account_id, currency FROM account_balance ORDER BY account_account_id, currency;" 2>/dev/null | while read -r acc ccy; do
    [ -z "$acc" ] && continue
    MYSQL_RECON_TOTAL=$((MYSQL_RECON_TOTAL + 1))
    API_VAL=$(curl -s --max-time 5 "${BASE_URL}/ledger/balances?accountId=${acc}&balanceType=AVAILABLE_BALANCE&currency=${ccy}" 2>/dev/null \
      | python3 -c "import sys,json;print(json.load(sys.stdin).get('amount','ERR'))" 2>/dev/null || echo "ERR")
    MYSQL_VAL=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='${acc}' AND currency='${ccy}';")
    if [ "$API_VAL" = "ERR" ] || [ -z "$MYSQL_VAL" ]; then
      echo "  ${acc},${ccy},${API_VAL},${MYSQL_VAL},SKIP" >> /tmp/recon_detail.csv
      MYSQL_RECON_SKIP=$((MYSQL_RECON_SKIP + 1))
    elif [ "$(normalize "$API_VAL")" = "$(normalize "$MYSQL_VAL")" ]; then
      echo "  ${acc},${ccy},${API_VAL},${MYSQL_VAL},MATCH" >> /tmp/recon_detail.csv
      MYSQL_RECON_OK=$((MYSQL_RECON_OK + 1))
    else
      echo "  ${acc},${ccy},${API_VAL},${MYSQL_VAL},MISMATCH" >> /tmp/recon_detail.csv
      MYSQL_RECON_BAD=$((MYSQL_RECON_BAD + 1))
    fi
    echo "MYSQL_RECON_TOTAL=$MYSQL_RECON_TOTAL MYSQL_RECON_OK=$MYSQL_RECON_OK MYSQL_RECON_BAD=$MYSQL_RECON_BAD MYSQL_RECON_SKIP=$MYSQL_RECON_SKIP" > /tmp/recon_counters
  done
  wait
  # Read counters from subshell
  eval "$(cat /tmp/recon_counters 2>/dev/null)"
  echo "  Recon: total=${MYSQL_RECON_TOTAL:-0} match=${MYSQL_RECON_OK:-0} mismatch=${MYSQL_RECON_BAD:-0} skip=${MYSQL_RECON_SKIP:-0}"
  if [ -s /tmp/recon_detail.csv ]; then
    echo "  Recon detail:"
    cat /tmp/recon_detail.csv
  fi
  echo ""
  echo "--- Follower logs (last 20 errors) ---"
  for n in 2 3; do
    echo "=== node-${n} ==="
    docker logs "ledger-node-${n}" 2>&1 | grep -i "error\|exception\|Failed to apply" | tail -5
  done
} > "$DIAG_FILE" 2>/dev/null

# ============================================================
# 7. Summary
# ============================================================

echo ""
echo "========================================="
echo " TEST CYCLE SUMMARY"
echo "========================================="
printf "  %-8s %s\n" "VUs:"      "$VUS"
printf "  %-8s %s\n" "Duration:" "$DURATION"
printf "  %-8s %s\n" "Iter:"     "${ITERATIONS:-?}"
printf "  %-8s %s\n" "Failed:"   "${FAILED:-?}"
printf "  %-8s %s\n" "p50:"      "${P50:-?}"
printf "  %-8s %s\n" "p95:"      "${P95:-?}"
echo ""
printf "  %-4s %-25s %s\n" "?" "Check" "Detail"
echo "  ──── ──────────────────────── ──────────────────────────────────"
printf "  %-4s %-25s %s\n" \
  "$([ "$LA1" = "$LA2" ] && [ "$LA2" = "$LA3" ] && echo "✅" || echo "❌")" \
  "Raft lastAppliedIndex" \
  "$LA1 / $LA2 / $LA3"
printf "  %-4s %-25s %s\n" \
  "$([ "$DIFF_SM" -le 10 ] && echo "✅" || echo "❌")" \
  "Raft smJournalSeq" \
  "$SM1 / $SM2 / $SM3 (max diff=$DIFF_SM)"
# Re-measure lag fresh at summary time (projection keeps draining after Step 4).
SM_NOW=$(raft_status "$BASE_URL" "smJournalSeq")
MYSQL_NOW=$(mysql_query "SELECT COUNT(*) FROM journal;")
LAG_NOW=$((SM_NOW - MYSQL_NOW))
if [ "$LAG_NOW" -gt 0 ] && [ "${PROJ_RATE_FINAL:-0}" -gt 0 ]; then
  ETA_NOW="$((LAG_NOW / PROJ_RATE_FINAL))s"
else
  ETA_NOW="-"
fi
printf "  %-4s %-25s %s\n" \
  "$([ "$LAG_NOW" -le 10 ] && echo "✅" || echo "⚠️")" \
  "MySQL journal lag" \
  "SM=$SM_NOW MySQL=$MYSQL_NOW lag=$LAG_NOW drain=${PROJ_RATE_FINAL:-?}/s eta=$ETA_NOW"

# Hotspot balance summary
for ccy in "${CURRENCIES[@]}"; do
  V1=$(api_balance "${NODES[0]}" "$HOTSPOT_ACC" "AVAILABLE_BALANCE" "$ccy")
  V2=$(api_balance "${NODES[1]}" "$HOTSPOT_ACC" "AVAILABLE_BALANCE" "$ccy")
  V3=$(api_balance "${NODES[2]}" "$HOTSPOT_ACC" "AVAILABLE_BALANCE" "$ccy")
  M=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='$HOTSPOT_ACC' AND currency='$ccy';")
  MATCH=$(num_eq "$V1" "$V2" && num_eq "$V2" "$V3" && echo "✅" || echo "❌")
  M_MATCH=$(num_eq "$V1" "$M" && echo "✅" || echo "⚠️")
  printf "  %-4s %-25s %s\n" \
    "$MATCH" \
    "Hotspot $ccy cross-node" \
    "$(normalize "$V1") / $(normalize "$V2") / $(normalize "$V3")"
  printf "  %-4s %-25s %s\n" \
    "$M_MATCH" \
    "Hotspot $ccy MySQL" \
    "leader=$(normalize "$V1") MySQL=$(normalize "$M")"
done

  # MySQL full recon
  eval "$(cat /tmp/recon_counters 2>/dev/null)"
  printf "  %-4s %-25s %s\n" \
    "$( [ "${MYSQL_RECON_BAD:-0}" -eq 0 ] && [ "${MYSQL_RECON_OK:-0}" -gt 0 ] && echo "✅" || echo "⚠️")" \
    "MySQL balance recon (all)" \
    "match=${MYSQL_RECON_OK:-0} mismatch=${MYSQL_RECON_BAD:-0} skip=${MYSQL_RECON_SKIP:-0}"

  # MySQL event/journal/line counts
  printf "  %-4s %-25s %s\n" \
    "📊" \
    "MySQL counts" \
    "journals=$MYSQL_JNLS_COUNT events=${MYSQL_EVENTS:-0} lines=${MYSQL_LINES:-0} balances=$MYSQL_BALANCES"

echo "  ──── ──────────────────────── ──────────────────────────────────"
printf "  %-25s %d passed, %d failed, %d warnings\n" "" "$PASS" "$FAIL" "$WARN"
echo "========================================="
echo "  Diagnostics: $DIAG_FILE"
echo ""

if [ "$FAIL" -eq 0 ]; then
  echo -e "${GREEN}TEST CYCLE PASSED${NC}"
  exit 0
else
  echo -e "${RED}TEST CYCLE FAILED${NC}"
  exit 1
fi
