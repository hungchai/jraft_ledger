#!/bin/bash
# Regression test — full API surface coverage
set -e

BASE="${1:-http://localhost:8081}"
PASS=0; FAIL=0
TOTAL_START=$(date +%s)

check() {
  local desc="$1" expected="$2" actual="$3"
  if echo "$actual" | grep -q "$expected"; then
    echo "  PASS: $desc"; PASS=$((PASS+1))
  else
    echo "  FAIL: $desc (expected '$expected', got '$(echo $actual | head -c 200)')"; FAIL=$((FAIL+1))
  fi
}

check_status() {
  local desc="$1" expected_code="$2" url="$3" method="${4:-GET}" data="${5:-}"
  local code
  if [ "$method" = "POST" ]; then
    code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$url" -H "Content-Type: application/json" -d "$data")
  else
    code=$(curl -s -o /dev/null -w "%{http_code}" "$url")
  fi
  if [ "$code" = "$expected_code" ]; then
    echo "  PASS: $desc (HTTP $code)"; PASS=$((PASS+1))
  else
    echo "  FAIL: $desc (expected HTTP $expected_code, got $code)"; FAIL=$((FAIL+1))
  fi
}

echo "========================================="
echo "  Ledger Platform — Regression Test Suite"
echo "========================================="
echo ""

# ── Section 1: Health ──────────────────
echo "[Section 1] Health"
check_status "health endpoint" 200 "$BASE/health"
echo ""

# ── Section 2: Account Management ──────
echo "[Section 2] Account Management"
REQ_ID="reg-acc-$(date +%s)"

# Create account
R=$(curl -s -X POST "$BASE/ledger/accounts" -H "Content-Type: application/json" -d "{
  \"requestId\": \"$REQ_ID\",
  \"accountId\": \"REG_ACC_001\",
  \"accountType\": \"CLIENT\",
  \"displayName\": \"Regression Client\",
  \"ownerId\": \"CUST-REG-001\",
  \"balanceInitializations\": [
    {\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\"},
    {\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"HKD\"}
  ]
}")
check "create account" "COMPLETED" "$R"

# Duplicate account
R2=$(curl -s -X POST "$BASE/ledger/accounts" -H "Content-Type: application/json" -d "{
  \"requestId\": \"reg-acc-dup\",
  \"accountId\": \"REG_ACC_001\",
  \"accountType\": \"CLIENT\",
  \"displayName\": \"Duplicate\",
  \"ownerId\": \"CUST-REG-001\",
  \"balanceInitializations\": [{\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\"}]
}")
check_status "duplicate account rejected" 500 "$BASE/ledger/accounts" POST "{
  \"requestId\": \"reg-acc-dup\",
  \"accountId\": \"REG_ACC_001\",
  \"accountType\": \"CLIENT\",
  \"displayName\": \"Dup\",
  \"ownerId\": \"CUST-REG-001\",
  \"balanceInitializations\": [{\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\"}]
}"

# Freeze
R=$(curl -s -X POST "$BASE/ledger/accounts/REG_ACC_001/freeze" -H "Content-Type: application/json" \
  -d "{\"requestId\": \"reg-freeze\"}")
check "freeze account" "COMPLETED" "$R"

# Unfreeze
R=$(curl -s -X POST "$BASE/ledger/accounts/REG_ACC_001/unfreeze" -H "Content-Type: application/json" \
  -d "{\"requestId\": \"reg-unfreeze\"}")
check "unfreeze account" "COMPLETED" "$R"

# Add balance type
R=$(curl -s -X POST "$BASE/ledger/accounts/REG_ACC_001/balance-types" -H "Content-Type: application/json" \
  -d "{\"balanceType\": \"BROKERAGE_BALANCE\", \"currency\": \"USD\", \"requestId\": \"reg-add-bt\"}")
check "add balance type" "COMPLETED" "$R"
echo ""

# ── Section 3: Posting ─────────────────
echo "[Section 3] Posting"

# Setup company account
curl -s -X POST "$BASE/ledger/accounts" -H "Content-Type: application/json" -d "{
  \"requestId\": \"reg-setup-co\",
  \"accountId\": \"REG_CO\",
  \"accountType\": \"COMPANY\",
  \"displayName\": \"Reg Company\",
  \"balanceInitializations\": [{\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\"}]
}" > /dev/null

# Valid posting
R=$(curl -s -X POST "$BASE/ledger/postings" -H "Content-Type: application/json" -d '{
  "requestId": "reg-post-001",
  "businessEventType": "TEST",
  "businessEventRef": "REG-TEST-001",
  "valueDate": "2026-05-18",
  "legs": [{
    "legId": "leg-1",
    "postingType": "TEST",
    "lines": [
      {"accountId": "REG_CO", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "DEBIT", "amount": "500.00", "description": "Fund"},
      {"accountId": "REG_ACC_001", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "CREDIT", "amount": "500.00", "description": "Receive"}
    ]
  }]
}')
check "valid posting" "COMPLETED" "$R"

# Unbalanced posting
R=$(curl -s -X POST "$BASE/ledger/postings" -H "Content-Type: application/json" -d '{
  "requestId": "reg-post-unbal",
  "businessEventType": "TEST",
  "businessEventRef": "REG-UNBAL",
  "valueDate": "2026-05-18",
  "legs": [{
    "legId": "leg-1",
    "postingType": "TEST",
    "lines": [
      {"accountId": "REG_ACC_001", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "DEBIT", "amount": "100.00", "description": "D"},
      {"accountId": "REG_CO", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "CREDIT", "amount": "99.00", "description": "C"}
    ]
  }]
}')
check "unbalanced rejected" "JOURNAL_UNBALANCED" "$R"

# Insufficient balance
R=$(curl -s -X POST "$BASE/ledger/postings" -H "Content-Type: application/json" -d '{
  "requestId": "reg-post-insf",
  "businessEventType": "TEST",
  "businessEventRef": "REG-INSF",
  "valueDate": "2026-05-18",
  "legs": [{
    "legId": "leg-1",
    "postingType": "TEST",
    "lines": [
      {"accountId": "REG_ACC_001", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "DEBIT", "amount": "999999.00", "description": "Too much"},
      {"accountId": "REG_CO", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "CREDIT", "amount": "999999.00", "description": "Too much"}
    ]
  }]
}')
check "insufficient balance rejected" "INSUFFICIENT_BALANCE" "$R"
echo ""

# ── Section 4: Balance Query ───────────
echo "[Section 4] Balance Query"

R=$(curl -s "$BASE/ledger/balances?accountId=REG_ACC_001&balanceType=AVAILABLE_BALANCE&currency=USD")
check "balance query" "STATE_MACHINE" "$R"

R=$(curl -s "$BASE/ledger/balances?accountId=REG_ACC_001&balanceType=AVAILABLE_BALANCE&currency=HKD")
check "HKD balance" "0" "$R"
echo ""

# ── Section 5: Journal Query ───────────
echo "[Section 5] Journal Query"

R=$(curl -s "$BASE/ledger/journals/by-request-id?requestId=reg-post-001")
JOURNAL_ID=$(echo "$R" | grep -o '"journalId":"[^"]*"' | head -1 | cut -d'"' -f4)
check "journal by requestId" "JNL" "$R"

if [ -n "$JOURNAL_ID" ]; then
  R=$(curl -s "$BASE/ledger/journals/$JOURNAL_ID")
  check "journal by ID" "$JOURNAL_ID" "$R"
fi

R=$(curl -s "$BASE/ledger/journals?accountId=REG_ACC_001&page=0&size=10")
check "journals by account" "totalCount" "$R"
echo ""

# ── Section 6: Reversal ────────────────
echo "[Section 6] Reversal"

if [ -n "$JOURNAL_ID" ]; then
  R=$(curl -s -X POST "$BASE/ledger/journals/$JOURNAL_ID/reversal" -H "Content-Type: application/json" -d "{
    \"requestId\": \"reg-rev-001\",
    \"reversalReason\": \"Regression test reversal\",
    \"reversalReasonCode\": \"TEST\",
    \"valueDate\": \"2026-05-18\"
  }")
  check "reversal" "COMPLETED" "$R"
fi
echo ""

# ── Section 7: Adjustment ──────────────
echo "[Section 7] Manual Adjustment"

R=$(curl -s -X POST "$BASE/ledger/adjustments/drafts" -H "Content-Type: application/json" -d "{
  \"requestId\": \"reg-adj-001\",
  \"makerId\": \"maker-reg\",
  \"legs\": [{
    \"legId\": \"leg-1\",
    \"postingType\": \"ADJUSTMENT\",
    \"lines\": [
      {\"accountId\": \"REG_ACC_001\", \"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\", \"entryType\": \"CREDIT\", \"amount\": \"50.00\", \"description\": \"Adj credit\"},
      {\"accountId\": \"REG_CO\", \"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\", \"entryType\": \"DEBIT\", \"amount\": \"50.00\", \"description\": \"Adj debit\"}
    ]
  }]
}")
check "create adjustment draft" "PENDING_APPROVAL" "$R"

DRAFT_ID=$(echo "$R" | grep -o '"draftId":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -n "$DRAFT_ID" ]; then
  R=$(curl -s -X POST "$BASE/ledger/adjustments/drafts/$DRAFT_ID/approve" -H "Content-Type: application/json" \
    -d "{\"checkerId\": \"checker-reg\", \"approveRequestId\": \"reg-appr-001\"}")
  check "approve adjustment" "COMPLETED" "$R"
fi
echo ""

# ── Section 8: EOD ─────────────────────
echo "[Section 8] EOD"

R=$(curl -s -X POST "$BASE/ledger/periods/eod" -H "Content-Type: application/json" \
  -d '{"date": "2026-05-17"}')
check "EOD trigger" "CLOSED" "$R"
echo ""

# ── Section 9: Reconciliation ──────────
echo "[Section 9] Reconciliation"

R=$(curl -s -X POST "$BASE/ledger/reconciliation/l2" -H "Content-Type: application/json" -d "{
  \"date\": \"2026-05-18\",
  \"accountBalances\": {\"REG_ACC_001\": \"500.00\"},
  \"controlAccountId\": \"REG_CO\",
  \"controlBalance\": \"500.00\",
  \"tolerance\": \"0.01\"
}")
check "L2 reconciliation" "rulesPassed" "$R"
echo ""

# ── Results ────────────────────────────
TOTAL_END=$(date +%s)
ELAPSED=$((TOTAL_END - TOTAL_START))
echo "========================================="
echo "  Results: $PASS passed, $FAIL failed"
echo "  Time: ${ELAPSED}s"
echo "========================================="
[ "$FAIL" -eq 0 ] && echo "REGRESSION TESTS PASSED" || echo "REGRESSION TESTS FAILED"
exit $FAIL
