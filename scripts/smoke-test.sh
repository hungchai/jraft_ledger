#!/bin/bash
# Smoke tests — main business flow: create account → post → check balance → reverse
# Uses unique account IDs per run to avoid conflicts with existing data
# Auto-finds Raft leader if not explicitly passed as $1
set -e

PASS=0; FAIL=0

# Probe cluster nodes (8081, 8082, 8083) and return the leader URL.
# If $1 is explicitly provided, use it directly (skip auto-detect).
find_leader() {
  if [ -n "${1:-}" ] && [ "$1" != "auto" ]; then
    echo "$1"
    return
  fi
  for port in 8081 8082 8083; do
    local url="http://localhost:$port"
    local role
    role=$(curl -s --max-time 2 "$url/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('role',''))" 2>/dev/null || echo "")
    if [ "$role" = "LEADER" ]; then
      echo "$url"
      return
    fi
  done
  # Fallback: if no leader found (standalone or all down), use 8081
  echo "http://localhost:8081"
}

BASE=$(find_leader "${1:-auto}")
PROJECTION="http://localhost:8089"

# Generate unique suffix for this run
SUFFIX="$(date +%s%N | tail -c 6)"
CLIENT_ACC="CLIENT_ACC_${SUFFIX}"
COMPANY_ACC="COMPANY_FX_ACC_${SUFFIX}"

check() {
  local desc="$1" expected="$2" actual="$3"
  if echo "$actual" | grep -q "$expected"; then
    echo "  PASS: $desc"; PASS=$((PASS+1))
  else
    echo "  FAIL: $desc (expected '$expected')"; FAIL=$((FAIL+1))
  fi
}

echo "=== Smoke Tests against $BASE ==="
echo "Using unique accounts: $CLIENT_ACC, $COMPANY_ACC"
echo ""

# 1. Health — Ledger cluster
echo "[1] Health check (ledger cluster)"
R=$(curl -s "$BASE/health")
check "health UP" '"UP"' "$R"

# 1b. Projection health
echo "[1b] Health check (projection)"
R=$(curl -s "$PROJECTION/actuator/health")
check "projection health UP" '"UP"' "$R"

# 2. Create account
echo "[2] Create $CLIENT_ACC"
R=$(curl -s -X POST "$BASE/ledger/accounts" \
  -H "Content-Type: application/json" \
  -d "{
    \"requestId\": \"smoke-${SUFFIX}-001\",
    \"accountId\": \"$CLIENT_ACC\",
    \"accountType\": \"CLIENT\",
    \"displayName\": \"Smoke Test Client\",
    \"ownerId\": \"CUST-001\",
    \"balanceInitializations\": [
      {\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\"}
    ]
  }")
check "create account" "COMPLETED" "$R"

# 3. Create COMPANY_FX_ACC
echo "[3] Create $COMPANY_ACC"
R=$(curl -s -X POST "$BASE/ledger/accounts" \
  -H "Content-Type: application/json" \
  -d "{
    \"requestId\": \"smoke-${SUFFIX}-002\",
    \"accountId\": \"$COMPANY_ACC\",
    \"accountType\": \"COMPANY\",
    \"displayName\": \"Company FX\",
    \"balanceInitializations\": [
      {\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\"}
    ]
  }")
check "create company account" "COMPLETED" "$R"

# 4. Deposit to client (credit)
echo "[4] Deposit 1000 USD to $CLIENT_ACC"
R=$(curl -s -X POST "$BASE/ledger/postings" \
  -H "Content-Type: application/json" \
  -d "{
    \"requestId\": \"smoke-${SUFFIX}-003\",
    \"businessEventType\": \"DEPOSIT\",
    \"businessEventRef\": \"DEP-${SUFFIX}\",
    \"valueDate\": \"2026-05-18\",
    \"legs\": [
      {
        \"legId\": \"leg-1\",
        \"postingType\": \"DEPOSIT\",
        \"amount\": \"1000.00\",
        \"currency\": \"USD\",
        \"lines\": [
          {\"accountId\": \"$COMPANY_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"description\": \"Company funds\"},
          {\"accountId\": \"$CLIENT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"description\": \"Client deposit\"}
        ]
      }
    ]
  }")
check "deposit posting" "COMPLETED" "$R"

# 5. Check client balance
echo "[5] Check $CLIENT_ACC balance"
R=$(curl -s "$BASE/ledger/balances?accountId=$CLIENT_ACC&balanceType=AVAILABLE_BALANCE&currency=USD")
check "balance > 0" "1000.00" "$R"

# 6. Withdraw from client
echo "[6] Withdraw 300 USD from $CLIENT_ACC"
R=$(curl -s -X POST "$BASE/ledger/postings" \
  -H "Content-Type: application/json" \
  -d "{
    \"requestId\": \"smoke-${SUFFIX}-004\",
    \"businessEventType\": \"WITHDRAWAL\",
    \"businessEventRef\": \"WTH-${SUFFIX}\",
    \"valueDate\": \"2026-05-18\",
    \"legs\": [
      {
        \"legId\": \"leg-1\",
        \"postingType\": \"WITHDRAWAL\",
        \"amount\": \"300.00\",
        \"currency\": \"USD\",
        \"lines\": [
          {\"accountId\": \"$CLIENT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"description\": \"Client withdrawal\"},
          {\"accountId\": \"$COMPANY_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"description\": \"Company receive\"}
        ]
      }
    ]
  }")
check "withdrawal posting" "COMPLETED" "$R"

# 7. Verify balance = 700
echo "[7] Verify balance = 700.00"
R=$(curl -s "$BASE/ledger/balances?accountId=$CLIENT_ACC&balanceType=AVAILABLE_BALANCE&currency=USD")
check "balance 700.00" "700.00" "$R"

# 8. Find journal by requestId
echo "[8] Find journal by requestId"
R=$(curl -s "$BASE/ledger/journals/by-request-id?requestId=smoke-${SUFFIX}-004")
check "journal found" "JNL" "$R"

# 9. Idempotency — repeat same request
echo "[9] Idempotency — repeat smoke-${SUFFIX}-004"
R=$(curl -s -X POST "$BASE/ledger/postings" \
  -H "Content-Type: application/json" \
  -d "{
    \"requestId\": \"smoke-${SUFFIX}-004\",
    \"businessEventType\": \"WITHDRAWAL\",
    \"businessEventRef\": \"WTH-${SUFFIX}\",
    \"valueDate\": \"2026-05-18\",
    \"legs\": [
      {
        \"legId\": \"leg-1\",
        \"postingType\": \"WITHDRAWAL\",
        \"amount\": \"300.00\",
        \"currency\": \"USD\",
        \"lines\": [
          {\"accountId\": \"$CLIENT_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"description\": \"Client withdrawal\"},
          {\"accountId\": \"$COMPANY_ACC\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"CREDIT\", \"description\": \"Company receive\"}
        ]
      }
    ]
  }")
check "idempotent posting" "COMPLETED" "$R"

# 10. Balance unchanged after idempotent retry
echo "[10] Balance unchanged after idempotent retry"
R=$(curl -s "$BASE/ledger/balances?accountId=$CLIENT_ACC&balanceType=AVAILABLE_BALANCE&currency=USD")
check "balance still 700.00" "700.00" "$R"

# 11. Error details — unknown account
echo "[11] Post to unknown account returns errorDetails with accountId"
R=$(curl -s -X POST "$BASE/ledger/postings" \
  -H "Content-Type: application/json" \
  -d "{
    \"requestId\": \"smoke-${SUFFIX}-011\",
    \"businessEventType\": \"TEST\",
    \"businessEventRef\": \"GHOST-${SUFFIX}\",
    \"valueDate\": \"2026-05-18\",
    \"legs\": [
      {
        \"legId\": \"leg-1\",
        \"postingType\": \"TEST\",
        \"amount\": \"100.00\",
        \"currency\": \"USD\",
        \"lines\": [
          {\"accountId\": \"GHOST_ACC_${SUFFIX}\", \"balanceType\": \"AVAILABLE_BALANCE\", \"position\": \"CURRENT\", \"entryType\": \"DEBIT\", \"description\": \"Ghost\"}
        ]
      }
    ]
  }")
check "errorCode ACCOUNT_NOT_FOUND" "ACCOUNT_NOT_FOUND" "$R"
check "errorDetails contains accountId" '"accountId":"GHOST_ACC_'"$SUFFIX"'"' "$R"

echo ""
echo "=== Raft Cluster Consistency Checks ==="

# Helper: extract JSON field value
jsonval() {
  echo "$1" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$2',''))" 2>/dev/null
}

# Determine peer node URLs from BASE
NODE1="${BASE}"
NODE2="${BASE/8081/8082}"
NODE3="${BASE/8081/8083}"

# A. Multi-node balance consistency
echo "[A] Cross-node balance consistency for $CLIENT_ACC"
B1=$(curl -s "$NODE1/ledger/balances?accountId=$CLIENT_ACC&balanceType=AVAILABLE_BALANCE&currency=USD")
B2=$(curl -s "$NODE2/ledger/balances?accountId=$CLIENT_ACC&balanceType=AVAILABLE_BALANCE&currency=USD")
B3=$(curl -s "$NODE3/ledger/balances?accountId=$CLIENT_ACC&balanceType=AVAILABLE_BALANCE&currency=USD")
AMT1=$(jsonval "$B1" amount)
AMT2=$(jsonval "$B2" amount)
AMT3=$(jsonval "$B3" amount)
check "balance identical node1 vs node2" "$AMT1" "$AMT2"
check "balance identical node1 vs node3" "$AMT1" "$AMT3"
check "balance identical node2 vs node3" "$AMT2" "$AMT3"

# B. Raft index consistency
echo "[B] Raft index consistency"
R1=$(curl -s "$NODE1/ledger/cluster/raft-status")
R2=$(curl -s "$NODE2/ledger/cluster/raft-status")
R3=$(curl -s "$NODE3/ledger/cluster/raft-status")
LAI1=$(jsonval "$R1" lastAppliedIndex)
LAI2=$(jsonval "$R2" lastAppliedIndex)
LAI3=$(jsonval "$R3" lastAppliedIndex)
SMR1=$(jsonval "$R1" smRaftLogIndex)
SMR2=$(jsonval "$R2" smRaftLogIndex)
SMR3=$(jsonval "$R3" smRaftLogIndex)
JRS1=$(jsonval "$R1" smJournalSeq)
JRS2=$(jsonval "$R2" smJournalSeq)
JRS3=$(jsonval "$R3" smJournalSeq)
# lastAppliedIndex may differ by 1 (leader applies before followers).
# smRaftLogIndex and smJournalSeq must be exact — they track journal-creating ops only.
LAI_DIFF=$((LAI1 - LAI2))
LAI_DIFF="${LAI_DIFF#-}"  # absolute value
check "lastAppliedIndex within 1 across nodes" "1" "$( [ "$LAI_DIFF" -le 1 ] && echo 1 || echo 0 )"
check "smRaftLogIndex identical across nodes" "$SMR1" "$SMR2"
check "smJournalSeq identical across nodes" "$JRS1" "$JRS2"

# C. MySQL projection consistency (poll — projection is async)
echo "[C] MySQL projection consistency"
MYSQL_MATCH=0
for attempt in $(seq 1 15); do
  MYSQL_AMT=$(docker exec ledger-mysql mysql -u ledger -pledger123 ledger_view -sN \
    -e "SELECT amount FROM account_balance WHERE account_account_id = '$CLIENT_ACC' AND balance_type = 'AVAILABLE_BALANCE' AND currency = 'USD'" 2>/dev/null || echo "")
  if [ -n "$MYSQL_AMT" ]; then
    MYSQL_AMT_TRIMMED=$(echo "$MYSQL_AMT" | sed 's/\.0*$//')
    # Normalize SM amount: strip trailing .0* so 700.0 == 700
    AMT1_TRIM=$(echo "$AMT1" | sed 's/\.0*$//')
    if [ "$MYSQL_AMT_TRIMMED" = "$AMT1_TRIM" ]; then
      MYSQL_MATCH=1
      break
    fi
  fi
  sleep 1
done
if [ "$MYSQL_MATCH" -eq 1 ]; then
  check "MySQL balance matches SM" "$AMT1_TRIM" "$MYSQL_AMT_TRIMMED"
else
  echo "  FAIL: MySQL projection consistency (timed out or mismatch)"
  FAIL=$((FAIL+1))
fi

# D. Projection consumer lag / event processing
echo "[D] Projection event processing"
PROJ_METRICS=$(curl -s "$PROJECTION/actuator/prometheus")
PROJ_EVENTS=$(echo "$PROJ_METRICS" | grep 'ledger_projection_events_processed{' | sed 's/.* //')
if [ -n "$PROJ_EVENTS" ] && [ "$PROJ_EVENTS" != "0" ]; then
  echo "  PASS: projection has processed events ($PROJ_EVENTS)"
  PASS=$((PASS+1))
else
  echo "  FAIL: projection events_processed is zero or missing"
  FAIL=$((FAIL+1))
fi

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ] && echo "SMOKE TESTS PASSED" || echo "SMOKE TESTS FAILED"
exit $FAIL
