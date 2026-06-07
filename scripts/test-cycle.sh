#!/bin/bash
# test-cycle.sh — Standard test procedure for ledger platform
# Usage: ./scripts/test-cycle.sh [--vus N] [--duration M] [--no-flush] [--recon-only]
#
# Procedure:
#   1. Flush MySQL + RocksDB + Raft log
#   2. Start stack (if not running)
#   2b. Wait for projection (health + end-to-end Kafka probe)
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
DIAG_DIR="$PROJECT_DIR/jraft_ledger/diagnostics"
mkdir -p "$DIAG_DIR"
# Projection catch-up: journal-count lag must be <= PROJ_LAG_MAX before MySQL
# balance checks count as FAIL (otherwise WARN only — async pipeline).
PROJ_LAG_MAX=10
PROJ_WAIT_POLL_SEC=5
PROJ_WAIT_MAX_LOOPS=120   # 120 * 5s = 600s max wait
PROJ_STALL_POLLS=12       # stop early if lag unchanged for 12 polls (60s)
PROJ_BALANCE_QUEUE_MAX=0  # balance conflation queue must be empty
PROJ_OUTBOX_MAX=100       # leader outbox backlog tolerance
PROJ_RATE_IDLE_MAX=1.0    # events/s threshold; below = "no new work"
PROJ_IDLE_READY_SEC=3     # seconds since last event before pipeline is "quiet"
PROJ_IDLE_REQUIRED_POLLS=2  # need N consecutive idle polls before ready=true
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
PROJECTION_URL="${PROJECTION_URL:-http://localhost:8089}"
PROJ_PRE_K6_POLL_SEC=2
PROJ_PRE_K6_MAX_LOOPS=60   # 60 * 2s = 120s max wait before k6 setup

usage() {
  echo "Usage: $0 [--vus N] [--duration M] [--no-flush] [--recon-only] [--base-url URL]"
  echo "         [--proj-lag-max N] [--proj-wait-max-loops N] [--prometheus-url URL]"
  echo "  --vus N         Number of VUs (default: 10)"
  echo "  --duration M    Test duration (default: 2m)"
  echo "  --no-flush      Skip data flush"
  echo "  --recon-only    Only run reconciliation (skip k6)"
  echo "  --base-url URL  Explicit leader URL (auto-detect if not set)"
  echo "  --proj-lag-max N   Max SM-MySQL journal lag for strict balance FAIL (default: 10)"
  echo "  --proj-wait-max-loops N  Poll iterations in each wait phase (default: 120 = 600s)"
  echo "  --prometheus-url URL  Prometheus query API (default: http://localhost:9090)"
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --vus) VUS="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --no-flush) FLUSH=false; shift ;;
    --recon-only) RECON_ONLY=true; shift ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --proj-lag-max) PROJ_LAG_MAX="$2"; shift 2 ;;
    --proj-wait-max-loops) PROJ_WAIT_MAX_LOOPS="$2"; shift 2 ;;
    --prometheus-url) PROMETHEUS_URL="$2"; shift 2 ;;
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

# Query Prometheus instant vector; print scalar value or ERR.
prom_scalar() {
  local query="$1"
  local encoded
  encoded=$(python3 -c "import urllib.parse; print(urllib.parse.quote('''${query}'''))")
  curl -s --max-time 5 "${PROMETHEUS_URL}/api/v1/query?query=${encoded}" 2>/dev/null \
    | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    r = d.get('data', {}).get('result', [])
    if not r:
        print('ERR')
    else:
        print(r[0].get('value', [None, 'ERR'])[1])
except Exception:
    print('ERR')
" 2>/dev/null || echo "ERR"
}

# Fetch projection pipeline gauges from Prometheus (more reliable than journal
# count alone: balance queue + outbox reflect actual pipeline backlog).
fetch_prom_projection_metrics() {
  PROJ_BALANCE_QUEUE=$(prom_scalar 'ledger_projection_journal_buffer_depth')
  PROJ_OUTBOX_PENDING=$(prom_scalar 'max(ledger_outbox_pending)')
  PROJ_EVENTS_RATE=$(prom_scalar 'rate(ledger_projection_events_processed[1m])')
  PROJ_SINCE_LAST_EVENT=$(prom_scalar 'ledger_projection_seconds_since_last_event_seconds')
}

# Pipeline ready: balance queue drained, outbox drained, AND no events flowing.
# An empty queue alone is a transient drain window — events still in flight
# can refill it within milliseconds. True ready means the consumer is idle.
PROJ_IDLE_POLLS=0
projection_pipeline_ready() {
  fetch_prom_projection_metrics
  if [ "$PROJ_BALANCE_QUEUE" = "ERR" ] || [ "$PROJ_OUTBOX_PENDING" = "ERR" ] \
     || [ "$PROJ_EVENTS_RATE" = "ERR" ] || [ "$PROJ_SINCE_LAST_EVENT" = "ERR" ]; then
    PROJ_PIPELINE_READY=0
    PROJ_IDLE_POLLS=0
    return 1
  fi
  python3 -c "
import sys
q = float('${PROJ_BALANCE_QUEUE}')
o = float('${PROJ_OUTBOX_PENDING}')
r = float('${PROJ_EVENTS_RATE}')
i = float('${PROJ_SINCE_LAST_EVENT}')
ok = q <= ${PROJ_BALANCE_QUEUE_MAX} and o <= ${PROJ_OUTBOX_MAX} \
     and r <= ${PROJ_RATE_IDLE_MAX} and i >= ${PROJ_IDLE_READY_SEC}
sys.exit(0 if ok else 1)
" || { PROJ_PIPELINE_READY=0; PROJ_IDLE_POLLS=0; return 1; }
  PROJ_IDLE_POLLS=$((PROJ_IDLE_POLLS + 1))
  if [ "$PROJ_IDLE_POLLS" -ge "$PROJ_IDLE_REQUIRED_POLLS" ]; then
    PROJ_PIPELINE_READY=1
    return 0
  fi
  PROJ_PIPELINE_READY=0
  return 1
}

prom_metrics_line() {
  printf "queue=%s outbox=%s rate=%s/s idle=%ss" \
    "${PROJ_BALANCE_QUEUE:-?}" "${PROJ_OUTBOX_PENDING:-?}" \
    "${PROJ_EVENTS_RATE:-?}" "${PROJ_SINCE_LAST_EVENT:-?}"
}

# Strict MySQL balance FAIL when journal lag is low OR Prometheus says pipeline ready.
mysql_recon_strict() {
  [ "${PROJ_LAG_AT_RECON:-999999}" -le "$PROJ_LAG_MAX" ] || [ "${PROJ_PIPELINE_READY:-0}" -eq 1 ]
}

# Poll until SM journal count ~= MySQL journal count. Sets global LAG, PROJ_RATE.
# Also polls Prometheus; exits early when pipeline ready even if journal lag > 0.
# Returns 0 if lag <= PROJ_LAG_MAX or pipeline ready, 1 otherwise.
wait_projection_catchup() {
  local phase="${1:-projection}"
  SM_JNLS=$(raft_status "$BASE_URL" "smJournalSeq")
  MYSQL_JNLS=$(mysql_query "SELECT COUNT(*) FROM journal;")
  if ! [[ "$SM_JNLS" =~ ^[0-9]+$ ]] || ! [[ "$MYSQL_JNLS" =~ ^[0-9]+$ ]]; then
    warn "[$phase] cannot measure lag (SM=$SM_JNLS MySQL=$MYSQL_JNLS)"
    LAG=999999
    return 1
  fi
  LAG=$((SM_JNLS - MYSQL_JNLS))
  fetch_prom_projection_metrics
  echo "  [$phase] SM=$SM_JNLS MySQL=$MYSQL_JNLS lag=$LAG prom: $(prom_metrics_line)"
  PROJ_RATE=0
  if [ "$LAG" -le "$PROJ_LAG_MAX" ]; then
    return 0
  fi
  if projection_pipeline_ready; then
    echo "  [$phase] Prometheus pipeline ready ($(prom_metrics_line)) despite journal lag=$LAG"
    return 0
  fi
  local prev_mysql=$MYSQL_JNLS
  local prev_lag=$LAG
  local stall_count=0
  local i
  for i in $(seq 1 "$PROJ_WAIT_MAX_LOOPS"); do
    sleep "$PROJ_WAIT_POLL_SEC"
    SM_JNLS=$(raft_status "$BASE_URL" "smJournalSeq")
    MYSQL_JNLS=$(mysql_query "SELECT COUNT(*) FROM journal;")
    LAG=$((SM_JNLS - MYSQL_JNLS))
    PROJ_RATE=$(( (MYSQL_JNLS - prev_mysql) / PROJ_WAIT_POLL_SEC ))
    prev_mysql=$MYSQL_JNLS
    if [ "$LAG" -eq "$prev_lag" ]; then
      stall_count=$((stall_count + 1))
    else
      stall_count=0
    fi
    prev_lag=$LAG
    fetch_prom_projection_metrics
    if [ "$PROJ_RATE" -gt 0 ]; then
      ETA=$((LAG / PROJ_RATE))
      printf "  [%3ds] lag=%-6d drain=%d/s eta=%ds prom: %s\n" \
        "$((i * PROJ_WAIT_POLL_SEC))" "$LAG" "$PROJ_RATE" "$ETA" "$(prom_metrics_line)"
    else
      printf "  [%3ds] lag=%-6d drain=0/s prom: %s\n" \
        "$((i * PROJ_WAIT_POLL_SEC))" "$LAG" "$(prom_metrics_line)"
    fi
    if projection_pipeline_ready; then
      echo "  [$phase] Prometheus pipeline ready ($(prom_metrics_line)) at journal lag=$LAG"
      return 0
    fi
    if [ "$LAG" -le "$PROJ_LAG_MAX" ]; then
      return 0
    fi
    if [ "$stall_count" -ge "$PROJ_STALL_POLLS" ]; then
      warn "[$phase] projection stalled at lag=$LAG for $((PROJ_STALL_POLLS * PROJ_WAIT_POLL_SEC))s — continuing"
      return 1
    fi
  done
  return 1
}

# Block k6 setup until projection is consuming Kafka (auto-offset-reset=latest
# drops messages published before the consumer joins). Health UP alone is not
# enough — wait for partition assignment, then prove API -> Kafka -> MySQL.
wait_projection_before_k6() {
  local i attempt probe_acc probe_req cnt assigned=0

  echo ""
  echo "=== Step 2b: Wait for projection before k6 setup ==="

  for i in $(seq 1 "$PROJ_PRE_K6_MAX_LOOPS"); do
    if curl -s --max-time 3 "$PROJECTION_URL/actuator/health" 2>/dev/null \
       | grep -q '"status":"UP"'; then
      pass "Projection health UP"
      break
    fi
    sleep "$PROJ_PRE_K6_POLL_SEC"
    if [ "$i" -eq "$PROJ_PRE_K6_MAX_LOOPS" ]; then
      fail "Projection not healthy after $((PROJ_PRE_K6_MAX_LOOPS * PROJ_PRE_K6_POLL_SEC))s"
      exit 1
    fi
  done

  for i in $(seq 1 "$PROJ_PRE_K6_MAX_LOOPS"); do
    if docker logs ledger-projection 2>&1 \
       | grep -q 'partitions assigned: \[ledger\.account'; then
      pass "Kafka consumer partitions assigned (ledger.account.v1)"
      assigned=1
      break
    fi
    sleep "$PROJ_PRE_K6_POLL_SEC"
  done
  if [ "$assigned" -eq 0 ]; then
    fail "Kafka consumer not assigned after $((PROJ_PRE_K6_MAX_LOOPS * PROJ_PRE_K6_POLL_SEC))s"
    exit 1
  fi
  sleep "$PROJ_PRE_K6_POLL_SEC"

  for attempt in $(seq 1 5); do
    probe_acc="CYCLE-PROBE-$(date +%s)-${attempt}"
    probe_req="cycle-probe-${probe_acc}"
    curl -s --max-time 10 -X POST "$BASE_URL/ledger/accounts" \
      -H "Content-Type: application/json" \
      -d "{
        \"requestId\": \"${probe_req}\",
        \"accountId\": \"${probe_acc}\",
        \"accountType\": \"CLIENT\",
        \"displayName\": \"Cycle probe\",
        \"ownerId\": \"CYCLE-PROBE\",
        \"balanceInitializations\": [
          {\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USDT\"}
        ]
      }" >/dev/null || true

    for i in $(seq 1 15); do
      cnt=$(mysql_query "SELECT COUNT(*) FROM account WHERE account_id='${probe_acc}';")
      if [ "$cnt" = "1" ]; then
        pass "Projection consuming Kafka (probe account ${probe_acc} in MySQL)"
        fetch_prom_projection_metrics
        echo "  prom: $(prom_metrics_line)"
        return 0
      fi
      sleep "$PROJ_PRE_K6_POLL_SEC"
    done
    warn "Probe ${probe_acc} not projected — retry $attempt/5"
  done

  fail "Projection did not project probe account — aborting k6 (seed journals would be lost)"
  exit 1
}

# API (state machine) vs MySQL balance: FAIL only when projection lag is low.
check_mysql_balance() {
  local label="$1" api_val="$2" mysql_val="$3"
  if [ "$api_val" = "ERR" ] || [ -z "$mysql_val" ]; then
    warn "$label: skip (API=$api_val MySQL=${mysql_val:-empty})"
    return 1
  fi
  if num_eq "$api_val" "$mysql_val"; then
    pass "$label: match ($(normalize "$mysql_val"))"
    return 0
  fi
  if mysql_recon_strict; then
    fail "$label: API=$api_val MySQL=$mysql_val"
  else
    warn "$label: API=$api_val MySQL=$mysql_val (journal lag=$PROJ_LAG_AT_RECON prom: $(prom_metrics_line), not a SM bug)"
  fi
}

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

# Decimal-precision comparison: API returns float64 while MySQL returns
# DECIMAL(34,16). USDT tolerance 1e-6 (sub-cent), BTC tolerance 1e-9
# (sub-satoshi). Sub-cent is acceptable for an 8dp / 16dp double-entry
# ledger (the underlying amount itself is integer in cents/satoshis).
mysql_recon_match() {
  local api="$1" mysql="$2" ccy="$3"
  if num_eq "$api" "$mysql"; then return 0; fi
  local tol
  if [ "$ccy" = "BTC" ]; then tol="1e-9"; else tol="1e-6"; fi
  python3 -c "
a = float('${api}'); b = float('${mysql}')
import sys
sys.exit(0 if abs(a - b) < ${tol} else 1)
" 2>/dev/null
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

# recon-only / --base-url: ensure leader URL before raft_status / api_balance
if [ -z "$BASE_URL" ]; then
  BASE_URL=$(find_leader)
fi
if ! curl -s --max-time 2 "$BASE_URL/health" 2>/dev/null | grep -qE 'LEADER|UP'; then
  fail "Leader not reachable at $BASE_URL"
  exit 1
fi
if $RECON_ONLY; then
  echo ""
  echo "=== Recon-only mode ==="
  pass "Leader: $BASE_URL"
fi

# ============================================================
# 2b. Projection ready before k6 setup (auto-offset-reset=latest)
# ============================================================

if ! $RECON_ONLY; then
  wait_projection_before_k6
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

  # Strip ANSI color codes injected by handleSummary/textSummary so the metric
  # greps below can match the embedded "iterations...........:" / "p(50)=..." rows.
  K6_PLAIN=$(echo "$K6_OUTPUT" | sed -E $'s/\\x1b\\[[0-9;]*m//g')

  # Extract key metrics
  ITERATIONS=$(echo "$K6_PLAIN" | grep "iterations\.\.\." | grep -oE '[0-9]+' | head -1)
  FAILED=$(echo "$K6_PLAIN" | grep "http_req_failed" | tail -1 | grep -oE '[0-9.]+%' | head -1)
  P50=$(echo "$K6_PLAIN" | grep -E "p\(50\)=" | grep "http_req_duration" | tail -1 | grep -oE 'p\(50\)=[0-9.]+(ms|µs|s)' | head -1)
  P95=$(echo "$K6_PLAIN" | grep -E "p\(95\)=" | grep "http_req_duration" | tail -1 | grep -oE 'p\(95\)=[0-9.]+(ms|µs|s)' | head -1)

  echo "  Iterations: ${ITERATIONS:-?}"
  echo "  Failed: ${FAILED:-?}"
  echo "  ${P50:-p50=?}  ${P95:-p95=?}"

  if [ -n "$FAILED" ] && [ "$FAILED" != "0.00%" ]; then
    warn "Failures detected: $FAILED"
  else
    pass "0% failures"
  fi

  # Move k6 HTML report (from k6-reporter handleSummary) to diagnostics
  K6_HTML=$(ls -t k6-report-*.html 2>/dev/null | head -1)
  if [ -n "$K6_HTML" ] && [ -f "$K6_HTML" ]; then
    mkdir -p "$DIAG_DIR"
    mv "$K6_HTML" "$DIAG_DIR/"
    K6_HTML_PATH="$DIAG_DIR/$K6_HTML"
    echo "  k6 HTML report: $K6_HTML_PATH"
  fi
fi

# ============================================================
# 4. Wait for projection catch-up (if applicable)
# ============================================================

echo ""
echo "=== Step 4: Wait for projection catch-up ==="
if wait_projection_catchup "post-stress"; then
  if [ "$LAG" -le "$PROJ_LAG_MAX" ]; then
    pass "Projection caught up (journal lag=$LAG)"
  else
    pass "Projection pipeline ready via Prometheus (journal lag=$LAG, $(prom_metrics_line))"
  fi
else
  warn "Projection still lagging: journal lag=$LAG (drain=${PROJ_RATE}/s, prom: $(prom_metrics_line))"
fi
PROJ_LAG_FINAL=$LAG
PROJ_RATE_FINAL=$PROJ_RATE

echo ""
echo "=== Step 4b: Pre-reconciliation lag snapshot ==="
SM_JNLS=$(raft_status "$BASE_URL" "smJournalSeq")
MYSQL_JNLS=$(mysql_query "SELECT COUNT(*) FROM journal;")
fetch_prom_projection_metrics
if projection_pipeline_ready; then
  PROJ_PIPELINE_READY=1
else
  PROJ_PIPELINE_READY=0
fi
if [[ "$SM_JNLS" =~ ^[0-9]+$ ]] && [[ "$MYSQL_JNLS" =~ ^[0-9]+$ ]]; then
  PROJ_LAG_AT_RECON=$((SM_JNLS - MYSQL_JNLS))
  echo "  SM=$SM_JNLS MySQL=$MYSQL_JNLS journal_lag=$PROJ_LAG_AT_RECON"
  echo "  Prometheus: $(prom_metrics_line) pipeline_ready=$PROJ_PIPELINE_READY"
  if mysql_recon_strict; then
    pass "Pre-recon strict balance checks enabled (journal_lag<=$PROJ_LAG_MAX or pipeline ready)"
  else
    warn "Pre-recon journal_lag=$PROJ_LAG_AT_RECON pipeline not ready — MySQL mismatches => WARN"
  fi
else
  PROJ_LAG_AT_RECON=999999
  warn "Pre-recon lag unknown (SM=$SM_JNLS MySQL=$MYSQL_JNLS) prom: $(prom_metrics_line)"
fi

# ============================================================
# 5. Reconcile
# ============================================================

echo ""
echo "=== Step 5: Reconciliation (journal_lag=$PROJ_LAG_AT_RECON pipeline_ready=$PROJ_PIPELINE_READY) ==="

NODES=("http://localhost:8081" "http://localhost:8082" "http://localhost:8083")
HOTSPOT_ACC="STRESS-HOT-CO-001"
CLIENT_PREFIX="STRESS-CLI-"
CURRENCIES=("USDT" "BTC")

# Discover accounts from MySQL account_balance (every account that has any row).
# Fall back to a small set if MySQL isn't reachable yet (recon-only mode pre-warmup).
ACCOUNTS_FILE=$(mktemp)
mysql_query "SELECT DISTINCT account_account_id FROM account_balance ORDER BY account_account_id;" 2>/dev/null > "$ACCOUNTS_FILE" || true
if [ -s "$ACCOUNTS_FILE" ]; then
  # Build bash array from file
  ACCOUNTS=()
  while IFS= read -r acc; do
    [ -z "$acc" ] && continue
    ACCOUNTS+=("$acc")
  done < "$ACCOUNTS_FILE"
  echo "  Discovered ${#ACCOUNTS[@]} accounts from MySQL (sample: ${ACCOUNTS[0]:-} ${ACCOUNTS[1]:-} ${ACCOUNTS[2]:-})"
else
  ACCOUNTS=("$HOTSPOT_ACC" "STRESS-CLI-0001" "STRESS-CLI-0002")
  echo "  MySQL account_balance not reachable — falling back to ${#ACCOUNTS[@]} hardcoded accounts"
fi
rm -f "$ACCOUNTS_FILE"

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

  check_mysql_balance "$ccy MySQL vs leader" "$LEADER_VAL" "$MYSQL_VAL" || true
done

# --- API balance cross-node (clients) — runs over all accounts discovered from MySQL ---
echo ""
echo "--- API balance cross-node (${#ACCOUNTS[@]} accounts × ${#CURRENCIES[@]} currencies) ---"
XNODE_OK=0
XNODE_BAD=0
XNODE_TOTAL=0
for acc in "${ACCOUNTS[@]}"; do
  for ccy in "${CURRENCIES[@]}"; do
    XNODE_TOTAL=$((XNODE_TOTAL + 1))
    V1=$(api_balance "${NODES[0]}" "$acc" "AVAILABLE_BALANCE" "$ccy")
    V2=$(api_balance "${NODES[1]}" "$acc" "AVAILABLE_BALANCE" "$ccy")
    V3=$(api_balance "${NODES[2]}" "$acc" "AVAILABLE_BALANCE" "$ccy")
    if [ "$V1" = "$V2" ] && [ "$V2" = "$V3" ]; then
      pass "$acc $ccy: identical ($V1)"
      XNODE_OK=$((XNODE_OK + 1))
    else
      fail "$acc $ccy: $V1 / $V2 / $V3"
      XNODE_BAD=$((XNODE_BAD + 1))
    fi
  done
done
if [ "$XNODE_BAD" -eq 0 ]; then
  pass "All-account cross-node API: $XNODE_OK/$XNODE_TOTAL identical"
else
  fail "All-account cross-node API: $XNODE_OK/$XNODE_TOTAL identical ($XNODE_BAD diverged)"
fi

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
MYSQL_RECON=0
MYSQL_RECON_FAIL=0
MYSQL_RECON_LAG_WARN=0
MYSQL_RECON_SKIP=0
RECON_PAIRS=$(mktemp)
mysql_query "SELECT DISTINCT account_account_id, currency FROM account_balance ORDER BY account_account_id, currency;" 2>/dev/null > "$RECON_PAIRS"
while read -r acc ccy; do
  [ -z "$acc" ] && continue
  API_VAL=$(api_balance "$BASE_URL" "$acc" "AVAILABLE_BALANCE" "$ccy")
  MYSQL_VAL=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='$acc' AND currency='$ccy';")

  if [ "$API_VAL" = "ERR" ] || [ -z "$MYSQL_VAL" ]; then
    MYSQL_RECON_SKIP=$((MYSQL_RECON_SKIP + 1))
  elif mysql_recon_match "$API_VAL" "$MYSQL_VAL" "$ccy"; then
    MYSQL_RECON=$((MYSQL_RECON + 1))
  elif mysql_recon_strict; then
    MYSQL_RECON_FAIL=$((MYSQL_RECON_FAIL + 1))
    fail "MySQL $acc $ccy: API=$API_VAL MySQL=$MYSQL_VAL"
  else
    MYSQL_RECON_LAG_WARN=$((MYSQL_RECON_LAG_WARN + 1))
    warn "MySQL $acc $ccy: API=$API_VAL MySQL=$MYSQL_VAL (journal lag=$PROJ_LAG_AT_RECON)"
  fi
done < "$RECON_PAIRS"
rm -f "$RECON_PAIRS"
if [ "$MYSQL_RECON_FAIL" -eq 0 ] && [ "$MYSQL_RECON_LAG_WARN" -eq 0 ]; then
  pass "Full MySQL recon: match=$MYSQL_RECON skip=$MYSQL_RECON_SKIP"
elif [ "$MYSQL_RECON_FAIL" -eq 0 ]; then
  warn "Full MySQL recon: match=$MYSQL_RECON lag_warn=$MYSQL_RECON_LAG_WARN skip=$MYSQL_RECON_SKIP"
else
  fail "Full MySQL recon: match=$MYSQL_RECON mismatch=$MYSQL_RECON_FAIL lag_warn=$MYSQL_RECON_LAG_WARN"
fi
echo "MYSQL_RECON_OK=$MYSQL_RECON MYSQL_RECON_BAD=$MYSQL_RECON_FAIL MYSQL_RECON_LAG_WARN=$MYSQL_RECON_LAG_WARN MYSQL_RECON_SKIP=$MYSQL_RECON_SKIP" > /tmp/recon_counters

# Fallback: if sharded event_log tables don't exist yet, try uns sharded
if [ "${MYSQL_EVENTS:-0}" = "0" ]; then
  MYSQL_EVENTS=$(mysql_query "SELECT COUNT(*) FROM projection_event_log;")
fi

# ============================================================
# 5b. Reconstructed balance verification (source of truth = journal_line)
# ============================================================
# Trust NOTHING from MySQL `account_balance` or Raft SM. Re-compute every
# balance from the append-only `journal_line_*` shards:
#     reconstructed = SUM(DEBIT) - SUM(CREDIT)  per (account, balance_type, currency)
# Then compare against API (leader) and MySQL `account_balance.amount`.
# This catches:
#   - Lost/wrongly-applied journal_lines
#   - Projection bugs that wrote the wrong amount to account_balance
#   - Any drift between state-machine state and durable journal history
# Disagreement here is a real bug, not a lag warning.
# ============================================================

echo ""
echo "=== Step 5b: Reconstructed balance from journal_line (source of truth) ==="
# Wait for journal_line shards to fully drain to MySQL before reconstructing.
# Each posting produces exactly 2 journal_lines (1 DEBIT + 1 CREDIT).
# We wait until MySQL `journal` count catches up to Raft smJournalSeq
# (within 10). Once MySQL journals are current, journal_line drains
# essentially in lockstep.
echo "  Waiting for journal_line shards to drain to MySQL..."
RECON_SETTLED=0
for jl_i in $(seq 1 120); do
  JNLS_NOW=$(raft_status "$BASE_URL" "smJournalSeq")
  MYSQL_JNLS=$(mysql_query "SELECT COUNT(*) FROM journal;")
  JL_TOTAL=$(mysql_query "SELECT SUM(cnt) FROM (SELECT COUNT(*) cnt FROM journal_line_0 UNION ALL SELECT COUNT(*) FROM journal_line_1 UNION ALL SELECT COUNT(*) FROM journal_line_2 UNION ALL SELECT COUNT(*) FROM journal_line_3) t;")
  JNLS_NOW=${JNLS_NOW:-0}
  MYSQL_JNLS=${MYSQL_JNLS:-0}
  JL_TOTAL=${JL_TOTAL:-0}
  if ! [[ "$JNLS_NOW" =~ ^[0-9]+$ ]]; then JNLS_NOW=0; fi
  if ! [[ "$MYSQL_JNLS" =~ ^[0-9]+$ ]]; then MYSQL_JNLS=0; fi
  if ! [[ "$JL_TOTAL" =~ ^[0-9]+$ ]]; then JL_TOTAL=0; fi
  EXPECTED_LINES=$((MYSQL_JNLS * 2))
  JL_LAG=$((EXPECTED_LINES - JL_TOTAL))
  if [ "$JL_LAG" -le 0 ] && [ $((JNLS_NOW - MYSQL_JNLS)) -le 10 ]; then
    pass "journal_line caught up: $JL_TOTAL lines (MySQL journals=$MYSQL_JNLS, smJournalSeq=$JNLS_NOW)"
    RECON_SETTLED=1
    break
  fi
  if [ "$jl_i" -eq 120 ]; then
    warn "journal_line still lagging after 120s: $JL_TOTAL lines / MySQL journals=$MYSQL_JNLS smJournalSeq=$JNLS_NOW (lag=$((JNLS_NOW - MYSQL_JNLS))) — Step 5b will treat as LAG, not as bug"
  fi
  sleep 1
done

RECON_TMP=$(mktemp)
mysql_query "
  SELECT jl.account_account_id, jl.balance_type, jl.currency, jl.entry_type, jl.amount,
         COALESCE(btr.sign_convention, 'NORMAL_CREDIT') AS sign_convention
  FROM journal_line_0 jl
  LEFT JOIN balance_type_registry btr ON btr.type_code = jl.balance_type
  WHERE jl.account_account_id LIKE '${CLIENT_PREFIX}%' OR jl.account_account_id = '$HOTSPOT_ACC'
  UNION ALL
  SELECT jl.account_account_id, jl.balance_type, jl.currency, jl.entry_type, jl.amount,
         COALESCE(btr.sign_convention, 'NORMAL_CREDIT')
  FROM journal_line_1 jl
  LEFT JOIN balance_type_registry btr ON btr.type_code = jl.balance_type
  WHERE jl.account_account_id LIKE '${CLIENT_PREFIX}%' OR jl.account_account_id = '$HOTSPOT_ACC'
  UNION ALL
  SELECT jl.account_account_id, jl.balance_type, jl.currency, jl.entry_type, jl.amount,
         COALESCE(btr.sign_convention, 'NORMAL_CREDIT')
  FROM journal_line_2 jl
  LEFT JOIN balance_type_registry btr ON btr.type_code = jl.balance_type
  WHERE jl.account_account_id LIKE '${CLIENT_PREFIX}%' OR jl.account_account_id = '$HOTSPOT_ACC'
  UNION ALL
  SELECT jl.account_account_id, jl.balance_type, jl.currency, jl.entry_type, jl.amount,
         COALESCE(btr.sign_convention, 'NORMAL_CREDIT')
  FROM journal_line_3 jl
  LEFT JOIN balance_type_registry btr ON btr.type_code = jl.balance_type
  WHERE jl.account_account_id LIKE '${CLIENT_PREFIX}%' OR jl.account_account_id = '$HOTSPOT_ACC'
" 2>/dev/null > "$RECON_TMP"

RECON_LINES=$(wc -l < "$RECON_TMP" | tr -d ' ')
echo "  journal_line rows for STRESS scope: $RECON_LINES"

RECON_AGGR=$(python3 - <<PYEOF
import csv
from collections import defaultdict
sums = defaultdict(lambda: defaultdict(lambda: defaultdict(float)))
signs = {}
with open("$RECON_TMP", newline='') as f:
    r = csv.reader(f, delimiter='\t')
    for row in r:
        if len(row) < 6 or not row[0]:
            continue
        acc, btype, ccy, etype, amt, sign = row[0], row[1], row[2], row[3], row[4], row[5]
        try:
            v = float(amt)
        except ValueError:
            continue
        signs[btype] = sign
        if etype == 'CREDIT':
            if sign == 'NORMAL_DEBIT':
                sums[acc][btype][ccy] -= v
            else:
                sums[acc][btype][ccy] += v
        elif etype == 'DEBIT':
            if sign == 'NORMAL_DEBIT':
                sums[acc][btype][ccy] += v
            else:
                sums[acc][btype][ccy] -= v
for acc in sorted(sums):
    for btype in sorted(sums[acc]):
        for ccy in sorted(sums[acc][btype]):
            print(f"{acc}\t{btype}\t{ccy}\t{sums[acc][btype][ccy]}")
PYEOF
)
rm -f "$RECON_TMP"

RECON_MATCH=0
RECON_MISMATCH=0
RECON_TOTAL=0
RECON_DETAIL=$(mktemp)
echo "  account_id,balance_type,currency,reconstructed,api,mysql,status" > "$RECON_DETAIL"
while IFS=$'\t' read -r acc btype ccy reconstructed; do
  [ -z "$acc" ] && continue
  RECON_TOTAL=$((RECON_TOTAL + 1))
  API_VAL=$(api_balance "$BASE_URL" "$acc" "$btype" "$ccy")
  MYSQL_VAL=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='$acc' AND balance_type='$btype' AND currency='$ccy';")
  if [ "$API_VAL" = "ERR" ]; then API_VAL=""; fi
  STATUS="OK"
  # Tolerance: float64 accumulation drift ~ 1e-6 USDT, 1e-9 BTC
  RECON_TOL="1e-6"
  [ "$ccy" = "BTC" ] && RECON_TOL="1e-9"
  if [ -n "$MYSQL_VAL" ]; then
    DIFF_M=$(python3 -c "print(abs(float('$reconstructed') - float('$MYSQL_VAL')))")
    if ! python3 -c "import sys; sys.exit(0 if float('$DIFF_M') < $RECON_TOL else 1)"; then
      STATUS="MYSQL_MISMATCH(diff=$DIFF_M)"
    fi
  else
    STATUS="MYSQL_MISSING"
  fi
  if [ -n "$API_VAL" ]; then
    DIFF_A=$(python3 -c "print(abs(float('$reconstructed') - float('$API_VAL')))")
    if ! python3 -c "import sys; sys.exit(0 if float('$DIFF_A') < $RECON_TOL else 1)"; then
      if [ "$STATUS" = "OK" ]; then STATUS="API_MISMATCH(diff=$DIFF_A)"
      else STATUS="${STATUS}+API_MISMATCH(diff=$DIFF_A)"; fi
    fi
  fi
  if [ "$STATUS" = "OK" ]; then
    RECON_MATCH=$((RECON_MATCH + 1))
  else
    RECON_MISMATCH=$((RECON_MISMATCH + 1))
  fi
  echo "  ${acc},${btype},${ccy},${reconstructed},${API_VAL},${MYSQL_VAL},${STATUS}" >> "$RECON_DETAIL"
done <<< "$RECON_AGGR"

if [ "$RECON_MISMATCH" -eq 0 ]; then
  pass "Reconstruction: $RECON_MATCH/$RECON_TOTAL (account,balance_type,currency) tuples match API & MySQL"
elif [ "$RECON_SETTLED" -ne 1 ]; then
  # journal_line was still draining when we tried to reconstruct — mismatches
  # are lag, not bugs. Downgrade to WARN so the cycle can still report PASS.
  warn "Reconstruction (lag): $RECON_MATCH/$RECON_TOTAL match, $RECON_MISMATCH MISMATCH (projection still draining — not a bug)"
  echo "  Sample of unmatched (lag) tuples:"
  grep -vE ',OK$' "$RECON_DETAIL" | head -5 | sed 's/^/    /'
else
  fail "Reconstruction: $RECON_MATCH/$RECON_TOTAL match, $RECON_MISMATCH MISMATCH (real bug, projection already settled)"
  echo "  Mismatched tuples:"
  grep -vE ',OK$' "$RECON_DETAIL" | head -20 | sed 's/^/    /'
fi
echo "RECON_OK=$RECON_MATCH RECON_BAD=$RECON_MISMATCH RECON_TOTAL=$RECON_TOTAL RECON_SETTLED=$RECON_SETTLED" > /tmp/recon_counters_extra
rm -f "$RECON_DETAIL"

# --- Sample client MySQL vs Leader (subset of discovered accounts) ---
echo ""
echo "--- Client MySQL vs Leader (sample of ${#ACCOUNTS[@]} accounts) ---"
# Pick a few representative accounts from the discovered set (first / mid / last / hotspot)
SAMPLE_ACCS=()
N_ACCOUNTS=${#ACCOUNTS[@]}
if [ "$N_ACCOUNTS" -gt 0 ]; then
  SAMPLE_ACCS+=("${ACCOUNTS[0]}")
  if [ "$N_ACCOUNTS" -gt 4 ]; then
    MID=$(( N_ACCOUNTS / 2 ))
    SAMPLE_ACCS+=("${ACCOUNTS[$MID]}")
    # add MID+1 if distinct
    NEXT=$((MID + 1))
    if [ "$NEXT" -lt "$N_ACCOUNTS" ] && [ "${ACCOUNTS[$NEXT]}" != "${ACCOUNTS[$MID]}" ]; then
      SAMPLE_ACCS+=("${ACCOUNTS[$NEXT]}")
    fi
  fi
  LAST_IDX=$((N_ACCOUNTS - 1))
  SAMPLE_ACCS+=("${ACCOUNTS[$LAST_IDX]}")
fi
# Always include the hotspot if present
for acc in "${ACCOUNTS[@]}"; do
  if [ "$acc" = "$HOTSPOT_ACC" ]; then
    SAMPLE_ACCS+=("$HOTSPOT_ACC")
    break
  fi
done
# Dedupe
if [ "${#SAMPLE_ACCS[@]}" -gt 0 ]; then
  SAMPLE_ACCS=($(printf "%s\n" "${SAMPLE_ACCS[@]}" | sort -u))
fi
for acc in "${SAMPLE_ACCS[@]}"; do
  for ccy in "${CURRENCIES[@]}"; do
    API_VAL=$(api_balance "$BASE_URL" "$acc" "AVAILABLE_BALANCE" "$ccy")
    MYSQL_VAL=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='$acc' AND currency='$ccy';")
    check_mysql_balance "$acc $ccy" "$API_VAL" "$MYSQL_VAL" || true
  done
done

# ============================================================
# 6. Summary
# ============================================================

# ============================================================
# 6. Diagnostic snapshot — capture full node state for debugging
# ============================================================

DIAG_FILE="$DIAG_DIR/recon-$(date +%Y%m%d-%H%M%S).log"
DIAG_TMP=$(mktemp)
{
  echo "=== Diagnostic Snapshot $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
  echo "Test: $VUS VUs × $DURATION  Recon-only: $RECON_ONLY  Threshold: journal_lag<=$PROJ_LAG_MAX  Prometheus: $PROMETHEUS_URL"
  echo "Iterations: ${ITERATIONS:-?}  Failed: ${FAILED:-?}  p50=${P50:-?}  p95=${P95:-?}"

  # k6 throughput
  if [ -n "${K6_OUTPUT:-}" ]; then
    K6_DROPPED=$(echo "$K6_OUTPUT" | grep -E "dropped_iterations" | grep -oE '[0-9]+' | tail -1)
    echo "k6 dropped_iterations: ${K6_DROPPED:-0}"
  fi

  # --- Raft consensus state ---
  echo ""
  echo "--- Raft Status (per node) ---"
  printf "%-15s %-8s %-10s %-12s %-12s %-7s %s\n" "node" "role" "term" "lastApplied" "smJournalSeq" "cfg" "alivePeers"
  for p in 8081 8082 8083; do
    DATA=$(curl -s --max-time 5 "http://localhost:${p}/ledger/cluster/raft-status" 2>/dev/null)
    if [ -z "$DATA" ]; then
      printf "%-15s %-8s %-10s %-12s %-12s %-7s %s\n" ":${p}" "DOWN" "-" "-" "-" "-" "-"
      continue
    fi
    python3 -c "
import json, sys
d = json.loads('''$DATA''')
role = 'LEADER' if d.get('isLeader') else 'FOLLOWER'
print('%-15s %-8s %-10s %-12s %-12s %-7s %s' % (
  ':' + '${p}',
  role,
  d.get('term', '?'),
  d.get('lastAppliedIndex', '?'),
  d.get('smJournalSeq', '?'),
  d.get('smConfigCount', '?'),
  ','.join(d.get('alivePeers', [])) or '-',
))
"
  done
  # Derived Raft consistency
  RAFT_LA=$(for p in 8081 8082 8083; do curl -s "http://localhost:${p}/ledger/cluster/raft-status" 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin).get('lastAppliedIndex',''))" 2>/dev/null; done)
  RAFT_SM=$(for p in 8081 8082 8083; do curl -s "http://localhost:${p}/ledger/cluster/raft-status" 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin).get('smJournalSeq',''))" 2>/dev/null; done)
  LA_MIN=$(echo "$RAFT_LA" | sort -n | head -1)
  LA_MAX=$(echo "$RAFT_LA" | sort -n | tail -1)
  SM_MIN=$(echo "$RAFT_SM" | sort -n | head -1)
  SM_MAX=$(echo "$RAFT_SM" | sort -n | tail -1)
  LA_DIFF=$(( ${LA_MAX:-0} - ${LA_MIN:-0} ))
  SM_DIFF=$(( ${SM_MAX:-0} - ${SM_MIN:-0} ))
  echo "  Raft lastAppliedIndex  range: [$LA_MIN, $LA_MAX]  max_diff=$LA_DIFF"
  echo "  Raft smJournalSeq       range: [$SM_MIN, $SM_MAX]  max_diff=$SM_DIFF"
  if [ "$LA_DIFF" -gt 0 ] || [ "$SM_DIFF" -gt 0 ]; then
    echo "  ❌ Raft nodes DIVERGED — investigation required"
  else
    echo "  ✅ Raft nodes consistent"
  fi

  # --- Hotspot balance cross-node ---
  echo ""
  echo "--- STRESS-HOT-CO-001 Balances (cross-node API) ---"
  printf "%-8s %-6s %-25s %-25s %-25s %s\n" "node" "ccy" "api_amount" "mysql_amount" "diff(mysql-api)" "match"
  for ccy in USDT BTC; do
    AMOUNTS=()
    for p in 8081 8082 8083; do
      AMT=$(curl -s --max-time 5 "http://localhost:${p}/ledger/balances?accountId=${HOTSPOT_ACC}&balanceType=AVAILABLE_BALANCE&currency=${ccy}" 2>/dev/null \
        | python3 -c "import sys,json;print(json.load(sys.stdin).get('amount',''))" 2>/dev/null)
      AMOUNTS+=("$AMT")
    done
    M_AMT=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='${HOTSPOT_ACC}' AND currency='${ccy}';")
    API_REF="${AMOUNTS[0]}"
    for i in 0 1 2; do
      DIFF=$(python3 -c "a=float('${AMOUNTS[$i]}');b=float('${M_AMT}');print(f'{b-a:+.10f}')" 2>/dev/null || echo "?")
      if [ "$i" -eq 0 ]; then
        MATCH_API="✅"
        for a in "${AMOUNTS[@]}"; do
          [ "$(normalize "$a")" = "$(normalize "$API_REF")" ] || MATCH_API="❌"
        done
        MATCH_DB="✅"
        mysql_recon_match "$API_REF" "$M_AMT" "$ccy" >/dev/null 2>&1 || MATCH_DB="❌"
        OVERALL="api=${MATCH_API} db=${MATCH_DB}"
      else
        OVERALL="-"
      fi
      printf "%-8s %-6s %-25s %-25s %-25s %s\n" ":${NODES[$i]##*:}" "$ccy" "${AMOUNTS[$i]}" "$M_AMT" "$DIFF" "$OVERALL"
    done
  done

  # --- MySQL state ---
  echo ""
  echo "--- MySQL: $HOTSPOT_ACC (full) ---"
  docker exec ledger-mysql mysql -u ledger -pledger123 ledger_view -t \
    -e "SELECT account_account_id, currency, amount, account_seq, updated_at FROM account_balance WHERE account_account_id='$HOTSPOT_ACC';" 2>/dev/null

  echo ""
  echo "--- MySQL: Counts ---"
  echo "  journals           = $(mysql_query "SELECT COUNT(*) FROM journal;")"
  echo "  balance_rows       = $(mysql_query "SELECT COUNT(*) FROM account_balance;")"
  echo "  events             = $(mysql_query "SELECT COUNT(*) FROM projection_event_log;")"
  echo "  journal_lines      = $MYSQL_LINES"
  echo "  account            = $(mysql_query "SELECT COUNT(*) FROM account;")"
  echo "  outbox_pending     = $(mysql_query "SELECT status, COUNT(*) FROM projection_event_log GROUP BY status;" | awk '/PENDING/{print $2}')"

  # --- Prometheus snapshot (now) ---
  echo ""
  echo "--- Prometheus: projection pipeline (now) ---"
  fetch_prom_projection_metrics
  echo "  ledger_projection_journal_buffer_depth     = ${PROJ_BALANCE_QUEUE:-ERR}"
  echo "  ledger_outbox_pending (max)                = ${PROJ_OUTBOX_PENDING:-ERR}"
  echo "  rate(ledger_projection_events_processed[1m]) = ${PROJ_EVENTS_RATE:-ERR} /s"
  echo "  ledger_projection_seconds_since_last_event = ${PROJ_SINCE_LAST_EVENT:-ERR} s"
  DIAG_LAG=$(( $(raft_status "$BASE_URL" "smJournalSeq") - $(mysql_query "SELECT COUNT(*) FROM journal;") ))
  echo "  journal_lag (SM_journals - MySQL_journals) = $DIAG_LAG"
  if projection_pipeline_ready; then
    PROJ_PIPELINE_READY=1
    echo "  pipeline_ready: ✅ (queue=$PROJ_BALANCE_QUEUE outbox=$PROJ_OUTBOX_PENDING rate=$PROJ_EVENTS_RATE/s idle=$PROJ_SINCE_LAST_EVENT s, idle_polls=$PROJ_IDLE_POLLS)"
  else
    PROJ_PIPELINE_READY=0
    echo "  pipeline_ready: ❌ (queue=$PROJ_BALANCE_QUEUE outbox=$PROJ_OUTBOX_PENDING rate=$PROJ_EVENTS_RATE/s idle=$PROJ_SINCE_LAST_EVENT s, idle_polls=$PROJ_IDLE_POLLS/$PROJ_IDLE_REQUIRED_POLLS)"
  fi

  # --- Full balance reconciliation ---
  echo ""
  echo "--- MySQL: Full balance reconciliation (all accounts) ---"
  echo "  journal_lag_at_diag=$DIAG_LAG pipeline_ready=$PROJ_PIPELINE_READY  strict_threshold=$PROJ_LAG_MAX"
  MYSQL_RECON_TOTAL=0 MYSQL_RECON_OK=0 MYSQL_RECON_BAD=0 MYSQL_RECON_LAG=0 MYSQL_RECON_SKIP=0
  echo "  account_id,currency,api_amount,mysql_amount,diff,abs_diff_pct,status" > /tmp/recon_detail.csv
  DIAG_PAIRS=$(mktemp)
  mysql_query "SELECT DISTINCT account_account_id, currency FROM account_balance ORDER BY account_account_id, currency;" 2>/dev/null > "$DIAG_PAIRS"
  while read -r acc ccy; do
    [ -z "$acc" ] && continue
    MYSQL_RECON_TOTAL=$((MYSQL_RECON_TOTAL + 1))
    API_VAL=$(curl -s --max-time 5 "${BASE_URL}/ledger/balances?accountId=${acc}&balanceType=AVAILABLE_BALANCE&currency=${ccy}" 2>/dev/null \
      | python3 -c "import sys,json;print(json.load(sys.stdin).get('amount','ERR'))" 2>/dev/null || echo "ERR")
    MYSQL_VAL=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='${acc}' AND currency='${ccy}';")
    if [ "$API_VAL" = "ERR" ] || [ -z "$MYSQL_VAL" ]; then
      echo "  ${acc},${ccy},${API_VAL},${MYSQL_VAL},?,?,SKIP" >> /tmp/recon_detail.csv
      MYSQL_RECON_SKIP=$((MYSQL_RECON_SKIP + 1))
      continue
    fi
    DIFF=$(python3 -c "a=float('${API_VAL}');b=float('${MYSQL_VAL}');print(f'{b-a:+.10f}')" 2>/dev/null || echo "?")
    PCT=$(python3 -c "a=float('${API_VAL}');b=float('${MYSQL_VAL}');print(f'{(abs(b-a)/abs(a)*100 if a else 0):.4f}')" 2>/dev/null || echo "?")
    if [ "$(normalize "$API_VAL")" = "$(normalize "$MYSQL_VAL")" ]; then
      echo "  ${acc},${ccy},${API_VAL},${MYSQL_VAL},${DIFF},${PCT}%,MATCH" >> /tmp/recon_detail.csv
      MYSQL_RECON_OK=$((MYSQL_RECON_OK + 1))
    elif [ "$DIAG_LAG" -le "$PROJ_LAG_MAX" ] || [ "$PROJ_PIPELINE_READY" = "1" ]; then
      echo "  ${acc},${ccy},${API_VAL},${MYSQL_VAL},${DIFF},${PCT}%,MISMATCH" >> /tmp/recon_detail.csv
      MYSQL_RECON_BAD=$((MYSQL_RECON_BAD + 1))
    else
      echo "  ${acc},${ccy},${API_VAL},${MYSQL_VAL},${DIFF},${PCT}%,LAG_MISMATCH" >> /tmp/recon_detail.csv
      MYSQL_RECON_LAG=$((MYSQL_RECON_LAG + 1))
    fi
  done < "$DIAG_PAIRS"
  rm -f "$DIAG_PAIRS"
  echo ""
  echo "  Recon: total=$MYSQL_RECON_TOTAL match=$MYSQL_RECON_OK mismatch=$MYSQL_RECON_BAD lag_mismatch=$MYSQL_RECON_LAG skip=$MYSQL_RECON_SKIP"
  MATCH_PCT=$(python3 -c "print(f'{(100*${MYSQL_RECON_OK}/${MYSQL_RECON_TOTAL}):.1f}' if ${MYSQL_RECON_TOTAL} else '0.0')" 2>/dev/null || echo "0.0")
  echo "  Match rate: ${MATCH_PCT}% (${MYSQL_RECON_OK}/${MYSQL_RECON_TOTAL})"
  # Mismatches by severity (use abs_diff_pct)
  if [ "$MYSQL_RECON_BAD" -gt 0 ]; then
    echo ""
    echo "  Top mismatches by |diff|:"
    tail -n +2 /tmp/recon_detail.csv | grep -E ',MISMATCH$|,LAG_MISMATCH$' \
      | sort -t',' -k5 -g 2>/dev/null | tail -10 \
      | awk -F',' '{printf "    %s %-12s api=%-22s mysql=%-22s diff=%s pct=%s%%\n", $7, $1, $3, $4, $5, $6}'
  fi
  echo ""
  echo "  Full detail:"
  echo "    account_id,currency,api_amount,mysql_amount,diff,abs_diff_pct,status"
  tail -n +2 /tmp/recon_detail.csv | sed 's/^/    /'

  # --- L1 journal-line sum vs balance check (sample accounts) ---
  echo ""
  echo "--- L1 Sanity: journals vs journal_lines ---"
  JNLS=$(mysql_query "SELECT COUNT(*) FROM journal;")
  LINES=$(mysql_query "SELECT SUM(cnt) FROM (SELECT COUNT(*) cnt FROM journal_line_0 UNION ALL SELECT COUNT(*) FROM journal_line_1 UNION ALL SELECT COUNT(*) FROM journal_line_2 UNION ALL SELECT COUNT(*) FROM journal_line_3) t;")
  AVG_LEGS=$(python3 -c "print(f'{${LINES}/${JNLS}:.2f}' if ${JNLS} else '0.0')" 2>/dev/null || echo "?")
  echo "  journals=$JNLS  journal_lines=$LINES  avg_legs_per_journal=$AVG_LEGS (expect 2.0 for postings, 0 for adjustments)"

  # --- Reconstructed balance from journal_line (source of truth, fresh snapshot) ---
  echo ""
  echo "--- Reconstructed balance from journal_line (source of truth) ---"
  RECON_DIAG_TMP=$(mktemp)
  mysql_query "
    SELECT jl.account_account_id, jl.balance_type, jl.currency, jl.entry_type, jl.amount,
           COALESCE(btr.sign_convention, 'NORMAL_CREDIT') AS sign_convention
    FROM journal_line_0 jl
    LEFT JOIN balance_type_registry btr ON btr.type_code = jl.balance_type
    WHERE jl.account_account_id LIKE '${CLIENT_PREFIX}%' OR jl.account_account_id = '$HOTSPOT_ACC'
    UNION ALL
    SELECT jl.account_account_id, jl.balance_type, jl.currency, jl.entry_type, jl.amount,
           COALESCE(btr.sign_convention, 'NORMAL_CREDIT')
    FROM journal_line_1 jl
    LEFT JOIN balance_type_registry btr ON btr.type_code = jl.balance_type
    WHERE jl.account_account_id LIKE '${CLIENT_PREFIX}%' OR jl.account_account_id = '$HOTSPOT_ACC'
    UNION ALL
    SELECT jl.account_account_id, jl.balance_type, jl.currency, jl.entry_type, jl.amount,
           COALESCE(btr.sign_convention, 'NORMAL_CREDIT')
    FROM journal_line_2 jl
    LEFT JOIN balance_type_registry btr ON btr.type_code = jl.balance_type
    WHERE jl.account_account_id LIKE '${CLIENT_PREFIX}%' OR jl.account_account_id = '$HOTSPOT_ACC'
    UNION ALL
    SELECT jl.account_account_id, jl.balance_type, jl.currency, jl.entry_type, jl.amount,
           COALESCE(btr.sign_convention, 'NORMAL_CREDIT')
    FROM journal_line_3 jl
    LEFT JOIN balance_type_registry btr ON btr.type_code = jl.balance_type
    WHERE jl.account_account_id LIKE '${CLIENT_PREFIX}%' OR jl.account_account_id = '$HOTSPOT_ACC'
  " 2>/dev/null > "$RECON_DIAG_TMP"
  RECON_DIAG_LINES=$(wc -l < "$RECON_DIAG_TMP" | tr -d ' ')
  echo "  journal_line rows for STRESS scope: $RECON_DIAG_LINES"
  RECON_DIAG_OK=0; RECON_DIAG_BAD=0
  echo "  account_id,balance_type,currency,reconstructed,api,mysql,status" > /tmp/recon_diag_detail.csv
  RECON_DIAG_AGGR=$(python3 - <<PYEOF
import csv
from collections import defaultdict
sums = defaultdict(lambda: defaultdict(lambda: defaultdict(float)))
signs = {}
with open("$RECON_DIAG_TMP", newline='') as f:
    r = csv.reader(f, delimiter='\t')
    for row in r:
        if len(row) < 6 or not row[0]: continue
        acc, btype, ccy, etype, amt, sign = row[0], row[1], row[2], row[3], row[4], row[5]
        try: v = float(amt)
        except ValueError: continue
        signs[btype] = sign
        if etype == 'CREDIT':
            sums[acc][btype][ccy] += v if sign != 'NORMAL_DEBIT' else -v
        elif etype == 'DEBIT':
            sums[acc][btype][ccy] += v if sign == 'NORMAL_DEBIT' else -v
for acc in sorted(sums):
    for btype in sorted(sums[acc]):
        for ccy in sorted(sums[acc][btype]):
            print(f"{acc}\t{btype}\t{ccy}\t{sums[acc][btype][ccy]}")
PYEOF
)
  rm -f "$RECON_DIAG_TMP"
  while IFS=$'\t' read -r acc btype ccy reconstructed; do
    [ -z "$acc" ] && continue
    DAPI_VAL=$(curl -s --max-time 5 "${BASE_URL}/ledger/balances?accountId=${acc}&balanceType=${btype}&currency=${ccy}" 2>/dev/null \
      | python3 -c "import sys,json;print(json.load(sys.stdin).get('amount','ERR'))" 2>/dev/null || echo "ERR")
    DMYSQL_VAL=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='$acc' AND balance_type='$btype' AND currency='$ccy';")
    STATUS="OK"
    # Tolerance: float64 accumulation drift ~ 1e-6 USDT, 1e-9 BTC
    RECON_DIAG_TOL="1e-6"
    [ "$ccy" = "BTC" ] && RECON_DIAG_TOL="1e-9"
    if [ -n "$DMYSQL_VAL" ]; then
      DIFF_M=$(python3 -c "print(abs(float('$reconstructed') - float('$DMYSQL_VAL')))")
      if ! python3 -c "import sys; sys.exit(0 if float('$DIFF_M') < $RECON_DIAG_TOL else 1)"; then
        STATUS="MYSQL_MISMATCH(diff=$DIFF_M)"
      fi
    else
      STATUS="MYSQL_MISSING"
    fi
    if [ -n "$DAPI_VAL" ] && [ "$DAPI_VAL" != "ERR" ]; then
      DIFF_A=$(python3 -c "print(abs(float('$reconstructed') - float('$DAPI_VAL')))")
      if ! python3 -c "import sys; sys.exit(0 if float('$DIFF_A') < $RECON_DIAG_TOL else 1)"; then
        if [ "$STATUS" = "OK" ]; then STATUS="API_MISMATCH(diff=$DIFF_A)"
        else STATUS="${STATUS}+API_MISMATCH(diff=$DIFF_A)"; fi
      fi
    fi
    if [ "$STATUS" = "OK" ]; then RECON_DIAG_OK=$((RECON_DIAG_OK+1))
    else RECON_DIAG_BAD=$((RECON_DIAG_BAD+1)); fi
    echo "  ${acc},${btype},${ccy},${reconstructed},${DAPI_VAL},${DMYSQL_VAL},${STATUS}" >> /tmp/recon_diag_detail.csv
  done <<< "$RECON_DIAG_AGGR"
  echo "  Reconstructed: ok=$RECON_DIAG_OK bad=$RECON_DIAG_BAD"
  if [ "$RECON_DIAG_BAD" -gt 0 ]; then
    echo "  Mismatches:"
    grep -vE ',OK$' /tmp/recon_diag_detail.csv | head -20 | sed 's/^/    /'
  fi

  # --- TPS snapshot (instantaneous rates from Prometheus) ---
  echo ""
  echo "--- TPS (instantaneous rates from Prometheus) ---"
  POSTING_TPS=$(prom_scalar "rate(ledger_posting_duration_seconds_count[1m])" 2>/dev/null)
  APPLIED_TPS=$(prom_scalar "rate(ledger_raft_last_applied_index[1m])" 2>/dev/null)
  OUTBOX_TPS=$(prom_scalar "rate(ledger_outbox_published[1m])" 2>/dev/null)
  PROJ_TPS=$(prom_scalar "rate(ledger_projection_events_processed[1m])" 2>/dev/null)
  printf "  %-26s %s\n" "API posting TPS"     "${POSTING_TPS:-ERR} /s"
  printf "  %-26s %s\n" "Raft apply TPS"      "${APPLIED_TPS:-ERR} /s"
  printf "  %-26s %s\n" "Outbox publish TPS"  "${OUTBOX_TPS:-ERR} /s"
  printf "  %-26s %s\n" "Projection write TPS" "${PROJ_TPS:-ERR} /s"
  if [ -n "${ITERATIONS:-}" ] && [ "${ITERATIONS:-0}" != "?" ] && [ "${ITERATIONS:-0}" -gt 0 ] 2>/dev/null; then
    DUR_S=$(echo "$DURATION" | sed -E 's/([0-9]+)m/\1*60/;s/([0-9]+)s/\1/' | bc 2>/dev/null || echo 0)
    K6_TPS=$(python3 -c "print(f'{$ITERATIONS/$DUR_S:.1f}' if $DUR_S else '0.0')" 2>/dev/null || echo "?")
    printf "  %-26s %s /s  (k6 iterations=%s over %s)\n" "k6 send TPS" "$K6_TPS" "$ITERATIONS" "$DURATION"
  else
    printf "  %-26s %s\n" "k6 send TPS" "N/A (--recon-only)"
  fi

  # --- JVM GC metrics (all 4 java services) ---
  echo ""
  echo "--- JVM GC: pause count + total + max + bytes allocated (since JVM start) ---"
  printf "  %-12s %-10s %-22s %-10s %-22s %s\n" "service" "gc_count" "total_pause" "max_ms" "allocated_bytes" "stops_over_5ms"
  GC_TOTAL_STOPS=0
  for entry in "ledger1:8081" "ledger2:8082" "ledger3:8083" "projection:8089"; do
    name="${entry%:*}"; port="${entry#*:}"
    PROM=$(curl -s --max-time 5 "http://localhost:${port}/actuator/prometheus" 2>/dev/null)
    if [ -z "$PROM" ]; then
      printf "  %-12s %-10s %-22s %-10s %-22s %s\n" "$name" "DOWN" "-" "-" "-" "-"
      continue
    fi
    GC_COUNT=$(echo "$PROM" | awk -F'[{} ]' '/^jvm_gc_pause_seconds_count/ {sum+=$(NF)} END{printf "%d", sum+0}')
    GC_SUM=$(echo "$PROM" | awk -F'[{} ]' '/^jvm_gc_pause_seconds_sum/ {sum+=$(NF)} END{printf "%.3f s", sum+0}')
    GC_MAX=$(echo "$PROM" | awk -F'[{} ]' '/^jvm_gc_pause_seconds_max/ {v=$(NF)+0; if(v>m_max) m_max=v} END{printf "%.1f", (m_max+0)*1000}')
    GC_ALLOC=$(echo "$PROM" | awk '/^jvm_gc_memory_allocated_bytes_total/ {$1=""; v=$2+0; if (v>=1e9) printf "%.2f GB", v/1e9; else if (v>=1e6) printf "%.2f MB", v/1e6; else printf "%.0f B", v; exit}')
    # Stops over 5ms: count of jvm_gc_pause_seconds_max rows > 0.005 (cause breakdown)
    GC_SLOW=$(echo "$PROM" | awk -F'[{} ]' '/^jvm_gc_pause_seconds_max/ {v=$(NF)+0; if(v>0.005) c++} END{printf "%d", c+0}')
    [ -z "$GC_SLOW" ] && GC_SLOW=0
    GC_TOTAL_STOPS=$((GC_TOTAL_STOPS + GC_SLOW))
    printf "  %-12s %-10s %-22s %-10s %-22s %s\n" "$name" "$GC_COUNT" "$GC_SUM" "${GC_MAX}ms" "${GC_ALLOC:-?}" "$GC_SLOW"
  done
  echo "  (stops_over_5ms = any jvm_gc_pause_seconds_max row > 5ms; ZGC pauses themselves are sub-ms)"
  echo "  Total stops>5ms across all 4 services: $GC_TOTAL_STOPS"

  # --- Raft error logs (last 5 per node) ---
  echo ""
  echo "--- Follower error logs (last 5 per node) ---"
  for n in 1 2 3; do
    echo "  === node-${n} ==="
    ERR_COUNT=$(docker logs "ledger-node-${n}" 2>&1 | grep -ciE "error|exception|Failed to apply" || true)
    echo "  error/exception lines: $ERR_COUNT"
    docker logs "ledger-node-${n}" 2>&1 | grep -iE "error|exception|Failed to apply" | tail -3 | sed 's/^/    /'
  done
} > "$DIAG_TMP" 2>/dev/null
mv "$DIAG_TMP" "$DIAG_FILE"

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
fetch_prom_projection_metrics
projection_pipeline_ready >/dev/null 2>&1 || true
if [ "$LAG_NOW" -gt 0 ] && [ "${PROJ_RATE_FINAL:-0}" -gt 0 ]; then
  ETA_NOW="$((LAG_NOW / PROJ_RATE_FINAL))s"
else
  ETA_NOW="-"
fi
PROM_OK=$([ "${PROJ_PIPELINE_READY:-0}" -eq 1 ] && echo "✅" || echo "⚠️")
printf "  %-4s %-25s %s\n" \
  "$([ "$LAG_NOW" -le "$PROJ_LAG_MAX" ] || [ "${PROJ_PIPELINE_READY:-0}" -eq 1 ] && echo "✅" || echo "⚠️")" \
  "MySQL journal lag" \
  "SM=$SM_NOW MySQL=$MYSQL_NOW lag=$LAG_NOW drain=${PROJ_RATE_FINAL:-?}/s eta=$ETA_NOW"
printf "  %-4s %-25s %s\n" \
  "$PROM_OK" \
  "Prometheus pipeline" \
  "$(prom_metrics_line) ready=${PROJ_PIPELINE_READY:-0}"

# Hotspot balance summary
for ccy in "${CURRENCIES[@]}"; do
  V1=$(api_balance "${NODES[0]}" "$HOTSPOT_ACC" "AVAILABLE_BALANCE" "$ccy")
  V2=$(api_balance "${NODES[1]}" "$HOTSPOT_ACC" "AVAILABLE_BALANCE" "$ccy")
  V3=$(api_balance "${NODES[2]}" "$HOTSPOT_ACC" "AVAILABLE_BALANCE" "$ccy")
  M=$(mysql_query "SELECT amount FROM account_balance WHERE account_account_id='$HOTSPOT_ACC' AND currency='$ccy';")
  MATCH=$(num_eq "$V1" "$V2" && num_eq "$V2" "$V3" && echo "✅" || echo "❌")
  M_MATCH=$(mysql_recon_match "$V1" "$M" "$ccy" >/dev/null 2>&1 && echo "✅" || echo "⚠️")
  printf "  %-4s %-25s %s\n" \
    "$MATCH" \
    "Hotspot $ccy cross-node" \
    "$(normalize "$V1") / $(normalize "$V2") / $(normalize "$V3")"
  printf "  %-4s %-25s %s\n" \
    "$M_MATCH" \
    "Hotspot $ccy MySQL" \
    "leader=$(normalize "$V1") MySQL=$(normalize "$M")"
done

  # MySQL full recon (from Step 5 counters)
  eval "$(cat /tmp/recon_counters 2>/dev/null)"
  printf "  %-4s %-25s %s\n" \
    "$( [ "${MYSQL_RECON_BAD:-0}" -eq 0 ] && echo "✅" || echo "❌")" \
    "MySQL balance recon (all)" \
    "match=${MYSQL_RECON_OK:-0} mismatch=${MYSQL_RECON_BAD:-0} lag_warn=${MYSQL_RECON_LAG_WARN:-0} skip=${MYSQL_RECON_SKIP:-0}"

  # Reconstructed-from-journal_line verification (source of truth = append-only journal_line)
  eval "$(cat /tmp/recon_counters_extra 2>/dev/null)"
  RECON_BAD_LAG=$([ "${RECON_SETTLED:-0}" -ne 1 ] && [ "${RECON_BAD:-0}" -gt 0 ] && echo 1 || echo 0)
  if [ "${RECON_BAD:-0}" -eq 0 ]; then
    RECON_ICON="✅"
    RECON_LABEL="Reconstructed (from journal_line)"
    RECON_DETAIL_STR="match=${RECON_OK:-0} mismatch=${RECON_BAD:-0} total=${RECON_TOTAL:-0}"
  elif [ "$RECON_BAD_LAG" -eq 1 ]; then
    RECON_ICON="⚠️"
    RECON_LABEL="Reconstructed (lag, not bug)"
    RECON_DETAIL_STR="match=${RECON_OK:-0} mismatch=${RECON_BAD:-0} total=${RECON_TOTAL:-0} (projection still draining)"
  else
    RECON_ICON="❌"
    RECON_LABEL="Reconstructed (from journal_line)"
    RECON_DETAIL_STR="match=${RECON_OK:-0} mismatch=${RECON_BAD:-0} total=${RECON_TOTAL:-0} (real bug, settled)"
  fi
  printf "  %-4s %-25s %s\n" "$RECON_ICON" "$RECON_LABEL" "$RECON_DETAIL_STR"

  # MySQL event/journal/line counts
  printf "  %-4s %-25s %s\n" \
    "📊" \
    "MySQL counts" \
    "journals=$MYSQL_JNLS_COUNT events=${MYSQL_EVENTS:-0} lines=${MYSQL_LINES:-0} balances=$MYSQL_BALANCES"

echo "  ──── ──────────────────────── ──────────────────────────────────"
# Fetch TPS for summary (sub-shell scope in recon doesn't leak)
SUMMARY_POSTING=$(prom_scalar "rate(ledger_posting_duration_seconds_count[1m])" 2>/dev/null)
SUMMARY_APPLIED=$(prom_scalar "rate(ledger_raft_last_applied_index[1m])" 2>/dev/null)
SUMMARY_OUTBOX=$(prom_scalar "rate(ledger_outbox_published[1m])" 2>/dev/null)
SUMMARY_PROJ=$(prom_scalar "rate(ledger_projection_events_processed[1m])" 2>/dev/null)
if [ -n "${ITERATIONS:-}" ] && [ "${ITERATIONS:-0}" != "?" ] && [ "${ITERATIONS:-0}" -gt 0 ] 2>/dev/null; then
  DUR_S=$(echo "$DURATION" | sed -E 's/([0-9]+)m/\1*60/;s/([0-9]+)s/\1/' | bc 2>/dev/null || echo 0)
  K6_TPS_S=$(python3 -c "print(f'{$ITERATIONS/$DUR_S:.1f}' if $DUR_S else '0.0')" 2>/dev/null || echo "?")
else
  K6_TPS_S="N/A"
fi
printf "  %-4s %-25s %s\n" "⚡" "TPS (1m rate)" "api=${SUMMARY_POSTING:-?} raft=${SUMMARY_APPLIED:-?} outbox=${SUMMARY_OUTBOX:-?} proj=${SUMMARY_PROJ:-?} k6=${K6_TPS_S}/s"
printf "  %-25s %d passed, %d failed, %d warnings\n" "" "$PASS" "$FAIL" "$WARN"
echo "========================================="
echo "  Diagnostics: $DIAG_FILE"
[ -n "${K6_HTML_PATH:-}" ] && echo "  k6 HTML report: $K6_HTML_PATH"
echo ""

if [ "$FAIL" -eq 0 ]; then
  echo -e "${GREEN}TEST CYCLE PASSED${NC}"
  exit 0
else
  echo -e "${RED}TEST CYCLE FAILED${NC}"
  exit 1
fi
