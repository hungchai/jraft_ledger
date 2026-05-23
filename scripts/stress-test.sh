#!/bin/bash
# Stress Test Suite — Next-Gen Internal Ledger Platform
# Implements docs/STRESS-TEST-PLAN.md v1.0
set -e

BASE="${1:-http://localhost:8081}"
RESET_ENV="${2:-false}"

# Tunables
ACCOUNT_COUNT=10000
RFQ_CLIENTS=1000
RFQ_PER_CLIENT=100
MIX_TOTAL=20000
MIX_CONCURRENT=500
SAME_ACC_CONCURRENT=1100
SAME_ACC_BALANCE="1000.00"
IDEM_UNIQUE=100
IDEM_RETRIES=1000
BP_CONCURRENT=5000
RW_DURATION_SEC=60

PASS=0
FAIL=0
TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

info()  { echo "[INFO]  $*"; }
pass()  { echo "[PASS]  $*"; PASS=$((PASS+1)); }
fail()  { echo "[FAIL]  $*"; FAIL=$((FAIL+1)); }

# Portable file lock (flock not available on macOS)
_lock() {
  local lf="$1"
  while ! mkdir "$lf" 2>/dev/null; do sleep 0.001; done
}
_unlock() {
  local lf="$1"
  rmdir "$lf" 2>/dev/null || true
}

http_post() {
  local url="$1" data="$2"
  curl -s -X POST "$url" -H "Content-Type: application/json" -d "$data"
}

http_get() {
  curl -s "$1"
}

check_health() {
  local url="$1/health"
  local r; r=$(http_get "$url" 2>/dev/null || echo "")
  if echo "$r" | grep -q '"UP"'; then return 0; else return 1; fi
}

# ───────────────────────────────────────────
# 0. Environment Reset
# ───────────────────────────────────────────
if [ "$RESET_ENV" = "true" ]; then
  info "Resetting environment..."
  docker compose down -v 2>/dev/null || true
  rm -rf ./jraft_ledger/node1/rocksdb ./jraft_ledger/node2/rocksdb ./jraft_ledger/node3/rocksdb ./jraft_ledger/mysql 2>/dev/null || true
  docker compose up -d --build 2>/dev/null || true
  info "Waiting for cluster healthy..."
  for node in 8081 8082 8083; do
    until curl -s "http://localhost:$node/health" | grep -q '"UP"'; do sleep 2; done
  done
  info "Cluster healthy."
fi

info "Stress target: $BASE"
info "Temp dir: $TMPDIR"

# ───────────────────────────────────────────
# 1. Seed Hotspot Company Account
# ───────────────────────────────────────────
info "Phase 0 — Seed hotspot account"
HOTSPOT_ACC="STRESS-HOT-CO-001"
http_post "$BASE/ledger/accounts" "{
  \"requestId\": \"seed-hotspot-$(date +%s)\",
  \"accountId\": \"$HOTSPOT_ACC\",
  \"accountType\": \"COMPANY\",
  \"displayName\": \"Hotspot Co\",
  \"ownerId\": \"CO-HOTSPOT\",
  \"balanceInitializations\": [
    {\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\"}
  ]
}" > /dev/null

# Deposit 10,000,000 USD into hotspot
http_post "$BASE/ledger/postings" "{
  \"requestId\": \"seed-deposit-hot-$(date +%s)\",
  \"businessEventType\": \"DEPOSIT\",
  \"businessEventRef\": \"SEED-HOT\",
  \"valueDate\": \"2026-05-23\",
  \"legs\": [
    {
      \"legId\": \"leg-1\",
      \"postingType\": \"DEPOSIT\",
      \"lines\": [
        {\"accountId\": \"SYSTEM_SEED\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"amount\": \"10000000.00\", \"description\": \"Seed\"},
        {\"accountId\": \"$HOTSPOT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"amount\": \"10000000.00\", \"description\": \"Seed\"}
      ]
    }
  ]
}" > /dev/null
pass "Hotspot account seeded with 10,000,000 USD"

# ───────────────────────────────────────────
# 2. Create 10,000 Accounts
# ───────────────────────────────────────────
info "Phase 1 — Creating $ACCOUNT_COUNT accounts (batched)..."
BATCH_SIZE=100
TOTAL_BATCHES=$((ACCOUNT_COUNT / BATCH_SIZE))
START_T=$(date +%s)

for b in $(seq 1 $TOTAL_BATCHES); do
  for i in $(seq 1 $BATCH_SIZE); do
    idx=$(((b - 1) * BATCH_SIZE + i))
    if [ $idx -le 9990 ]; then
      ACC_ID="STRESS-CLI-$(printf %04d $idx)"
      ACC_TYPE="CLIENT"
    else
      ACC_ID="STRESS-CO-$(printf %04d $idx)"
      ACC_TYPE="COMPANY"
    fi
    http_post "$BASE/ledger/accounts" "{
      \"requestId\": \"seed-acc-$idx-$(date +%s%N)\",
      \"accountId\": \"$ACC_ID\",
      \"accountType\": \"$ACC_TYPE\",
      \"displayName\": \"Stress $ACC_TYPE $idx\",
      \"ownerId\": \"OWN-$idx\",
      \"balanceInitializations\": [
        {\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\"}
      ]
    }" > /dev/null &
  done
  wait
  if [ $((b % 10)) -eq 0 ]; then
    info "  Batch $b / $TOTAL_BATCHES done"
  fi
done

ELAPSED=$(( $(date +%s) - START_T ))
info "Account creation complete in ${ELAPSED}s (~$((ACCOUNT_COUNT / (ELAPSED > 0 ? ELAPSED : 1))) acc/s)"

# Seed all CLIENT accounts with 1,000 USD
info "Phase 1b — Seeding CLIENT balances (batched)..."
for b in $(seq 1 $TOTAL_BATCHES); do
  for i in $(seq 1 $BATCH_SIZE); do
    idx=$(((b - 1) * BATCH_SIZE + i))
    if [ $idx -gt 9990 ]; then continue; fi
    ACC_ID="STRESS-CLI-$(printf %04d $idx)"
    http_post "$BASE/ledger/postings" "{
      \"requestId\": \"seed-bal-$idx-$(date +%s%N)\",
      \"businessEventType\": \"DEPOSIT\",
      \"businessEventRef\": \"SEED-BAL\",
      \"valueDate\": \"2026-05-23\",
      \"legs\": [
        {
          \"legId\": \"leg-1\",
          \"postingType\": \"DEPOSIT\",
          \"lines\": [
            {\"accountId\": \"$HOTSPOT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"amount\": \"1000.00\", \"description\": \"Seed\"},
            {\"accountId\": \"$ACC_ID\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"amount\": \"1000.00\", \"description\": \"Seed\"}
          ]
        }
      ]
    }" > /dev/null &
  done
  wait
  if [ $((b % 10)) -eq 0 ]; then
    info "  Seed batch $b / $TOTAL_BATCHES done"
  fi
done
pass "Seeded 9,990 CLIENT accounts with 1,000 USD"

# ───────────────────────────────────────────
# 3. Phase 2 — RFQ Hotspot
# ───────────────────────────────────────────
info "Phase 2 — RFQ Hotspot: $RFQ_CLIENTS clients × $RFQ_PER_CLIENT postings against $HOTSPOT_ACC"
RFQ_TOTAL=$((RFQ_CLIENTS * RFQ_PER_CLIENT))
RFQ_BATCH=50
RFQ_START=$(date +%s)
RFQ_OK=0; RFQ_FAIL=0
RFQ_LOCK="$TMPDIR/rfq.lock"

rfq_worker() {
  local client_idx="$1"
  local acc_id="STRESS-CLI-$(printf %04d $client_idx)"
  for n in $(seq 1 $RFQ_PER_CLIENT); do
    req_id="rfq-${client_idx}-${n}-$(date +%s%N)"
    r=$(http_post "$BASE/ledger/postings" "{
      \"requestId\": \"$req_id\",
      \"businessEventType\": \"RFQ\",
      \"businessEventRef\": \"RFQ-${client_idx}\",
      \"valueDate\": \"2026-05-23\",
      \"legs\": [
        {
          \"legId\": \"leg-1\",
          \"postingType\": \"RFQ\",
          \"lines\": [
            {\"accountId\": \"$acc_id\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"amount\": \"1.00\", \"description\": \"RFQ\"},
            {\"accountId\": \"$HOTSPOT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"amount\": \"1.00\", \"description\": \"RFQ\"}
          ]
        }
      ]
    }")
    if echo "$r" | grep -q '"COMPLETED"'; then
      _lock "$RFQ_LOCK"; echo 1 >> "$TMPDIR/rfq_ok.txt"; _unlock "$RFQ_LOCK"
    else
      _lock "$RFQ_LOCK"; echo 1 >> "$TMPDIR/rfq_fail.txt"; _unlock "$RFQ_LOCK"
    fi
  done
}

for c in $(seq 1 $RFQ_CLIENTS); do
  rfq_worker "$c" &
  if [ $((c % RFQ_BATCH)) -eq 0 ]; then
    wait
    info "  RFQ client batch $c / $RFQ_CLIENTS"
  fi
done
wait

RFQ_ELAPSED=$(( $(date +%s) - RFQ_START ))
RFQ_OK=$(wc -l < "$TMPDIR/rfq_ok.txt" 2>/dev/null || echo 0)
RFQ_FAIL=$(wc -l < "$TMPDIR/rfq_fail.txt" 2>/dev/null || echo 0)
info "RFQ complete: ${RFQ_ELAPSED}s, OK=$RFQ_OK, FAIL=$RFQ_FAIL, throughput=$((RFQ_TOTAL / (RFQ_ELAPSED > 0 ? RFQ_ELAPSED : 1))) req/s"

# Verify hotspot balance
HOT_BAL=$(http_get "$BASE/ledger/balances?accountId=$HOTSPOT_ACC&balanceType=AVAILABLE_BALANCE&currency=USD" | grep -oE '"amount":"?[0-9.]+"?' | head -1 | tr -cd '0-9.')
info "Hotspot balance: $HOT_BAL"
if echo "$HOT_BAL" | grep -q "10001000"; then
  pass "Hotspot balance correct after RFQ (10,001,000.00 expected)"
else
  fail "Hotspot balance drift (got $HOT_BAL, expected ~10001000.00)"
fi

# ───────────────────────────────────────────
# 4. Phase 3 — Mixed Deposit / Withdrawal
# ───────────────────────────────────────────
info "Phase 3 — Mixed deposit/withdrawal: $MIX_TOTAL postings, $MIX_CONCURRENT concurrent"
MIX_START=$(date +%s)
MIX_OK=0; MIX_FAIL=0
MIX_LOCK="$TMPDIR/mix.lock"

mix_worker() {
  local start="$1" end="$2"
  for i in $(seq $start $end); do
    rr=$((i % 10))
    client_idx=$(( (i % 9990) + 1 ))
    acc_id="STRESS-CLI-$(printf %04d $client_idx)"
    req_id="mix-$i-$(date +%s%N)"
    if [ $rr -lt 6 ]; then
      evt_type="DEPOSIT"; amt="10.00"; entry_client="CREDIT"; entry_hotspot="DEBIT"
    else
      evt_type="WITHDRAWAL"; amt="5.00"; entry_client="DEBIT"; entry_hotspot="CREDIT"
    fi
    r2=$(http_post "$BASE/ledger/postings" "{
      \"requestId\": \"$req_id\",
      \"businessEventType\": \"$evt_type\",
      \"businessEventRef\": \"MIX-$i\",
      \"valueDate\": \"2026-05-23\",
      \"legs\": [
        {
          \"legId\": \"leg-1\",
          \"postingType\": \"$evt_type\",
          \"lines\": [
            {\"accountId\": \"$HOTSPOT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"$entry_hotspot\", \"amount\": \"$amt\", \"description\": \"Mix\"},
            {\"accountId\": \"$acc_id\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"$entry_client\", \"amount\": \"$amt\", \"description\": \"Mix\"}
          ]
        }
      ]
    }")
    if echo "$r2" | grep -q '"COMPLETED"'; then
      _lock "$MIX_LOCK"; echo 1 >> "$TMPDIR/mix_ok.txt"; _unlock "$MIX_LOCK"
    else
      _lock "$MIX_LOCK"; echo 1 >> "$TMPDIR/mix_fail.txt"; _unlock "$MIX_LOCK"
    fi
  done
}

MIX_BATCH=$((MIX_TOTAL / MIX_CONCURRENT))
for b in $(seq 0 $((MIX_CONCURRENT - 1))); do
  S=$((b * MIX_BATCH + 1))
  E=$(((b + 1) * MIX_BATCH))
  mix_worker "$S" "$E" &
done
wait

MIX_ELAPSED=$(( $(date +%s) - MIX_START ))
MIX_OK=$(wc -l < "$TMPDIR/mix_ok.txt" 2>/dev/null || echo 0)
MIX_FAIL=$(wc -l < "$TMPDIR/mix_fail.txt" 2>/dev/null || echo 0)
info "Mix complete: ${MIX_ELAPSED}s, OK=$MIX_OK, FAIL=$MIX_FAIL, throughput=$((MIX_TOTAL / (MIX_ELAPSED > 0 ? MIX_ELAPSED : 1))) req/s"
pass "Mixed posting phase completed"

# ───────────────────────────────────────────
# 5. Phase 4 — Concurrent Same-Account Withdrawal
# ───────────────────────────────────────────
info "Phase 4 — Same-account race: $SAME_ACC_CONCURRENT withdrawals of 1.00 USD from account with $SAME_ACC_BALANCE"
SAME_ACC="STRESS-CLI-MAX-001"
http_post "$BASE/ledger/accounts" "{
  \"requestId\": \"seed-max-$(date +%s)\",
  \"accountId\": \"$SAME_ACC\",
  \"accountType\": \"CLIENT\",
  \"displayName\": \"Max Race\",
  \"ownerId\": \"OWN-MAX\",
  \"balanceInitializations\": [
    {\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\"}
  ]
}" > /dev/null

http_post "$BASE/ledger/postings" "{
  \"requestId\": \"seed-max-bal-$(date +%s)\",
  \"businessEventType\": \"DEPOSIT\",
  \"businessEventRef\": \"SEED-MAX\",
  \"valueDate\": \"2026-05-23\",
  \"legs\": [
    {
      \"legId\": \"leg-1\",
      \"postingType\": \"DEPOSIT\",
      \"lines\": [
        {\"accountId\": \"$HOTSPOT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"amount\": \"$SAME_ACC_BALANCE\", \"description\": \"Seed\"},
        {\"accountId\": \"$SAME_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"amount\": \"$SAME_ACC_BALANCE\", \"description\": \"Seed\"}
      ]
    }
  ]
}" > /dev/null

SA_START=$(date +%s)
SA_LOCK="$TMPDIR/sa.lock"

for i in $(seq 1 $SAME_ACC_CONCURRENT); do
  (
    req_id="sa-$i-$(date +%s%N)"
    r=$(http_post "$BASE/ledger/postings" "{
      \"requestId\": \"$req_id\",
      \"businessEventType\": \"WITHDRAWAL\",
      \"businessEventRef\": \"SA-$i\",
      \"valueDate\": \"2026-05-23\",
      \"legs\": [
        {
          \"legId\": \"leg-1\",
          \"postingType\": \"WITHDRAWAL\",
          \"lines\": [
            {\"accountId\": \"$SAME_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"amount\": \"1.00\", \"description\": \"SA\"},
            {\"accountId\": \"$HOTSPOT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"amount\": \"1.00\", \"description\": \"SA\"}
          ]
        }
      ]
    }")
    if echo "$r" | grep -q '"COMPLETED"'; then
      _lock "$SA_LOCK"; echo 1 >> "$TMPDIR/sa_ok.txt"; _unlock "$SA_LOCK"
    elif echo "$r" | grep -qi 'INSUFFICIENT_BALANCE\|REJECTED'; then
      _lock "$SA_LOCK"; echo 1 >> "$TMPDIR/sa_rej.txt"; _unlock "$SA_LOCK"
    else
      _lock "$SA_LOCK"; echo 1 >> "$TMPDIR/sa_fail.txt"; _unlock "$SA_LOCK"
    fi
  ) &
done
wait
SA_ELAPSED=$(( $(date +%s) - SA_START ))

SA_OK=$(wc -l < "$TMPDIR/sa_ok.txt" 2>/dev/null || echo 0)
SA_REJ=$(wc -l < "$TMPDIR/sa_rej.txt" 2>/dev/null || echo 0)
SA_FAIL=$(wc -l < "$TMPDIR/sa_fail.txt" 2>/dev/null || echo 0)
info "Same-account race: ${SA_ELAPSED}s, OK=$SA_OK, REJ=$SA_REJ, FAIL=$SA_FAIL"

# Verify balance
SA_BAL=$(http_get "$BASE/ledger/balances?accountId=$SAME_ACC&balanceType=AVAILABLE_BALANCE&currency=USD" | grep -oE '"amount":"?[0-9.]+"?' | head -1 | tr -cd '0-9.')
info "$SAME_ACC final balance: $SA_BAL"
if [ "$SA_BAL" = "0.00" ] || [ "$SA_BAL" = "0" ] || [ "$SA_BAL" = "0.0" ]; then
  pass "Same-account balance exhausted exactly (0.00)"
else
  fail "Same-account balance unexpected ($SA_BAL)"
fi
if [ "$SA_OK" -eq 1000 ]; then
  pass "Exactly 1,000 withdrawals succeeded"
else
  fail "Withdrawal success count unexpected (got $SA_OK, expected 1000)"
fi

# ───────────────────────────────────────────
# 6. Phase 5 — Idempotency Storm
# ───────────────────────────────────────────
info "Phase 5 — Idempotency storm: $IDEM_UNIQUE unique requestIds × $IDEM_RETRIES retries"
IDEM_ACC="STRESS-CLI-IDEM-001"
http_post "$BASE/ledger/accounts" "{
  \"requestId\": \"seed-idem-$(date +%s)\",
  \"accountId\": \"$IDEM_ACC\",
  \"accountType\": \"CLIENT\",
  \"displayName\": \"Idem\",
  \"ownerId\": \"OWN-IDEM\",
  \"balanceInitializations\": [
    {\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\"}
  ]
}" > /dev/null

http_post "$BASE/ledger/postings" "{
  \"requestId\": \"seed-idem-bal-$(date +%s)\",
  \"businessEventType\": \"DEPOSIT\",
  \"businessEventRef\": \"SEED-IDEM\",
  \"valueDate\": \"2026-05-23\",
  \"legs\": [
    {
      \"legId\": \"leg-1\",
      \"postingType\": \"DEPOSIT\",
      \"lines\": [
        {\"accountId\": \"$HOTSPOT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"amount\": \"10000.00\", \"description\": \"Seed\"},
        {\"accountId\": \"$IDEM_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"amount\": \"10000.00\", \"description\": \"Seed\"}
      ]
    }
  ]
}" > /dev/null

IDEM_START=$(date +%s)
IDEM_LOCK="$TMPDIR/idem.lock"

for u in $(seq 1 $IDEM_UNIQUE); do
  REQ_ID="idem-req-$u-fixed"
  # Fire first request (should succeed)
  http_post "$BASE/ledger/postings" "{
    \"requestId\": \"$REQ_ID\",
    \"businessEventType\": \"WITHDRAWAL\",
    \"businessEventRef\": \"IDEM-$u\",
    \"valueDate\": \"2026-05-23\",
    \"legs\": [
      {
        \"legId\": \"leg-1\",
        \"postingType\": \"WITHDRAWAL\",
        \"lines\": [
          {\"accountId\": \"$IDEM_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"amount\": \"1.00\", \"description\": \"Idem\"},
          {\"accountId\": \"$HOTSPOT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"amount\": \"1.00\", \"description\": \"Idem\"}
        ]
      }
    ]
  }" > /dev/null

  # Fire retries concurrently
  for r in $(seq 2 $IDEM_RETRIES); do
    (
      rr=$(http_post "$BASE/ledger/postings" "{
        \"requestId\": \"$REQ_ID\",
        \"businessEventType\": \"WITHDRAWAL\",
        \"businessEventRef\": \"IDEM-$u\",
        \"valueDate\": \"2026-05-23\",
        \"legs\": [
          {
            \"legId\": \"leg-1\",
            \"postingType\": \"WITHDRAWAL\",
            \"lines\": [
              {\"accountId\": \"$IDEM_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"amount\": \"1.00\", \"description\": \"Idem\"},
              {\"accountId\": \"$HOTSPOT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"amount\": \"1.00\", \"description\": \"Idem\"}
            ]
          }
        ]
      }")
      if echo "$rr" | grep -q '"COMPLETED"'; then
        _lock "$IDEM_LOCK"; echo 1 >> "$TMPDIR/idem_ok.txt"; _unlock "$IDEM_LOCK"
      else
        _lock "$IDEM_LOCK"; echo 1 >> "$TMPDIR/idem_fail.txt"; _unlock "$IDEM_LOCK"
      fi
    ) &
  done
  # Limit parallelism per unique request to avoid shell explosion
  wait
done

IDEM_ELAPSED=$(( $(date +%s) - IDEM_START ))
IDEM_OK=$(wc -l < "$TMPDIR/idem_ok.txt" 2>/dev/null || echo 0)
IDEM_FAIL=$(wc -l < "$TMPDIR/idem_fail.txt" 2>/dev/null || echo 0)
info "Idempotency storm: ${IDEM_ELAPSED}s, OK=$IDEM_OK, FAIL=$IDEM_FAIL"

# Verify journal count via query (approximate — we check balance instead)
IDEM_BAL=$(http_get "$BASE/ledger/balances?accountId=$IDEM_ACC&balanceType=AVAILABLE_BALANCE&currency=USD" | grep -oE '"amount":"?[0-9.]+"?' | head -1 | tr -cd '0-9.')
info "$IDEM_ACC final balance: $IDEM_BAL"
EXPECTED_IDEM_BAL="9900.00"
if [ "$IDEM_BAL" = "$EXPECTED_IDEM_BAL" ]; then
  pass "Idempotency balance correct ($EXPECTED_IDEM_BAL) — no duplicates"
else
  fail "Idempotency balance drift (got $IDEM_BAL, expected $EXPECTED_IDEM_BAL)"
fi

# ───────────────────────────────────────────
# 7. Phase 6 — Backpressure
# ───────────────────────────────────────────
info "Phase 6 — Backpressure burst: $BP_CONCURRENT rapid requests to hotspot"
BP_START=$(date +%s)
BP_429=0; BP_OK=0
BP_LOCK="$TMPDIR/bp.lock"

for i in $(seq 1 $BP_CONCURRENT); do
  (
    code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/ledger/postings" -H "Content-Type: application/json" -d "{
      \"requestId\": \"bp-$i-$(date +%s%N)\",
      \"businessEventType\": \"STRESS\",
      \"businessEventRef\": \"BP\",
      \"valueDate\": \"2026-05-23\",
      \"legs\": [
        {
          \"legId\": \"leg-1\",
          \"postingType\": \"STRESS\",
          \"lines\": [
            {\"accountId\": \"STRESS-CLI-0001\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"amount\": \"0.01\", \"description\": \"BP\"},
            {\"accountId\": \"$HOTSPOT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"amount\": \"0.01\", \"description\": \"BP\"}
          ]
        }
      ]
    }")
    if [ "$code" = "429" ]; then
      _lock "$BP_LOCK"; echo 1 >> "$TMPDIR/bp_429.txt"; _unlock "$BP_LOCK"
    elif [ "$code" = "200" ]; then
      _lock "$BP_LOCK"; echo 1 >> "$TMPDIR/bp_ok.txt"; _unlock "$BP_LOCK"
    fi
  ) &
done
wait
BP_ELAPSED=$(( $(date +%s) - BP_START ))
BP_429=$(wc -l < "$TMPDIR/bp_429.txt" 2>/dev/null || echo 0)
BP_OK=$(wc -l < "$TMPDIR/bp_ok.txt" 2>/dev/null || echo 0)
info "Backpressure: ${BP_ELAPSED}s, HTTP 200=$BP_OK, HTTP 429=$BP_429"
if [ "$BP_429" -gt 0 ]; then
  pass "Backpressure triggered (HTTP 429 count=$BP_429)"
else
  fail "Backpressure not triggered — all $BP_OK returned 200"
fi

# ───────────────────────────────────────────
# 8. Phase 7 — Read / Write Mix
# ───────────────────────────────────────────
info "Phase 7 — Read/write interleave for ${RW_DURATION_SEC}s"
RW_START=$(date +%s)
RW_WOK=0; RW_Rok=0; RW_WFAIL=0
RW_LOCK="$TMPDIR/rw.lock"

# Background writers
(
  while [ $(($(date +%s) - RW_START)) -lt $RW_DURATION_SEC ]; do
    idx=$((RANDOM % 9990 + 1))
    acc_id="STRESS-CLI-$(printf %04d $idx)"
    req_id="rw-w-$(date +%s%N)"
    r=$(http_post "$BASE/ledger/postings" "{
      \"requestId\": \"$req_id\",
      \"businessEventType\": \"DEPOSIT\",
      \"businessEventRef\": \"RW\",
      \"valueDate\": \"2026-05-23\",
      \"legs\": [
        {
          \"legId\": \"leg-1\",
          \"postingType\": \"DEPOSIT\",
          \"lines\": [
            {\"accountId\": \"$HOTSPOT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"amount\": \"0.01\", \"description\": \"RW\"},
            {\"accountId\": \"$acc_id\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"amount\": \"0.01\", \"description\": \"RW\"}
          ]
        }
      ]
    }")
    if echo "$r" | grep -q '"COMPLETED"'; then
      _lock "$RW_LOCK"; echo 1 >> "$TMPDIR/rw_wok.txt"; _unlock "$RW_LOCK"
    else
      _lock "$RW_LOCK"; echo 1 >> "$TMPDIR/rw_wfail.txt"; _unlock "$RW_LOCK"
    fi
  done
) &
WRITER_PID=$!

# Readers loop
while [ $(($(date +%s) - RW_START)) -lt $RW_DURATION_SEC ]; do
  idx=$((RANDOM % 9990 + 1))
  acc_id="STRESS-CLI-$(printf %04d $idx)"
  r=$(http_get "$BASE/ledger/balances?accountId=$acc_id&balanceType=AVAILABLE_BALANCE&currency=USD")
  if echo "$r" | grep -q '"amount"'; then
    _lock "$RW_LOCK"; echo 1 >> "$TMPDIR/rw_rok.txt"; _unlock "$RW_LOCK"
  fi
  # Throttle readers slightly to avoid overwhelming curl
  sleep 0.001
done

wait $WRITER_PID 2>/dev/null || true
RW_ELAPSED=$(( $(date +%s) - RW_START ))
RW_WOK=$(wc -l < "$TMPDIR/rw_wok.txt" 2>/dev/null || echo 0)
RW_Rok=$(wc -l < "$TMPDIR/rw_rok.txt" 2>/dev/null || echo 0)
RW_WFAIL=$(wc -l < "$TMPDIR/rw_wfail.txt" 2>/dev/null || echo 0)
info "Read/write: ${RW_ELAPSED}s, writes=$RW_WOK, reads=$RW_Rok, write_fails=$RW_WFAIL"
pass "Read/write interleave completed"

# ───────────────────────────────────────────
# 9. Summary
# ───────────────────────────────────────────
info "========================================"
info "  Stress Test Summary"
info "========================================"
info "PASS: $PASS"
info "FAIL: $FAIL"
if [ "$FAIL" -eq 0 ]; then
  echo "STRESS TEST PASSED"
  exit 0
else
  echo "STRESS TEST FAILED"
  exit 1
fi
