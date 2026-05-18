#!/bin/bash
# Smoke tests — main business flow: create account → post → check balance → reverse
set -e

BASE="${1:-http://localhost:8081}"
PASS=0; FAIL=0

check() {
  local desc="$1" expected="$2" actual="$3"
  if echo "$actual" | grep -q "$expected"; then
    echo "  PASS: $desc"; PASS=$((PASS+1))
  else
    echo "  FAIL: $desc (expected '$expected')"; FAIL=$((FAIL+1))
  fi
}

echo "=== Smoke Tests against $BASE ==="
echo ""

# 1. Health
echo "[1] Health check"
R=$(curl -s "$BASE/health")
check "health UP" '"UP"' "$R"

# 2. Create account
echo "[2] Create CLIENT_ACC_001"
R=$(curl -s -X POST "$BASE/ledger/accounts" \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "smoke-001",
    "accountId": "CLIENT_ACC_001",
    "accountType": "CLIENT",
    "displayName": "Smoke Test Client",
    "ownerId": "CUST-001",
    "balanceInitializations": [
      {"balanceType": "AVAILABLE_BALANCE", "currency": "USD"}
    ]
  }')
check "create account" "COMPLETED" "$R"

# 3. Create COMPANY_FX_ACC
echo "[3] Create COMPANY_FX_ACC"
R=$(curl -s -X POST "$BASE/ledger/accounts" \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "smoke-002",
    "accountId": "COMPANY_FX_ACC",
    "accountType": "COMPANY",
    "displayName": "Company FX",
    "balanceInitializations": [
      {"balanceType": "AVAILABLE_BALANCE", "currency": "USD"}
    ]
  }')
check "create company account" "COMPLETED" "$R"

# 4. Deposit to client (credit)
echo "[4] Deposit 1000 USD to CLIENT_ACC_001"
R=$(curl -s -X POST "$BASE/ledger/postings" \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "smoke-003",
    "businessEventType": "DEPOSIT",
    "businessEventRef": "DEP-001",
    "valueDate": "2026-05-18",
    "legs": [
      {
        "legId": "leg-1",
        "postingType": "DEPOSIT",
        "lines": [
          {"accountId": "COMPANY_FX_ACC", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "DEBIT", "amount": "1000.00", "description": "Company funds"},
          {"accountId": "CLIENT_ACC_001", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "CREDIT", "amount": "1000.00", "description": "Client deposit"}
        ]
      }
    ]
  }')
check "deposit posting" "COMPLETED" "$R"

# 5. Check client balance
echo "[5] Check CLIENT_ACC_001 balance"
R=$(curl -s "$BASE/ledger/balances?accountId=CLIENT_ACC_001&balanceType=AVAILABLE_BALANCE&currency=USD")
check "balance > 0" "1000.00" "$R"

# 6. Withdraw from client
echo "[6] Withdraw 300 USD from CLIENT_ACC_001"
R=$(curl -s -X POST "$BASE/ledger/postings" \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "smoke-004",
    "businessEventType": "WITHDRAWAL",
    "businessEventRef": "WTH-001",
    "valueDate": "2026-05-18",
    "legs": [
      {
        "legId": "leg-1",
        "postingType": "WITHDRAWAL",
        "lines": [
          {"accountId": "CLIENT_ACC_001", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "DEBIT", "amount": "300.00", "description": "Client withdrawal"},
          {"accountId": "COMPANY_FX_ACC", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "CREDIT", "amount": "300.00", "description": "Company receive"}
        ]
      }
    ]
  }')
check "withdrawal posting" "COMPLETED" "$R"

# 7. Verify balance = 700
echo "[7] Verify balance = 700.00"
R=$(curl -s "$BASE/ledger/balances?accountId=CLIENT_ACC_001&balanceType=AVAILABLE_BALANCE&currency=USD")
check "balance 700.00" "700.00" "$R"

# 8. Find journal by requestId
echo "[8] Find journal by requestId"
R=$(curl -s "$BASE/ledger/journals/by-request-id?requestId=smoke-004")
check "journal found" "JNL" "$R"

# 9. Idempotency — repeat same request
echo "[9] Idempotency — repeat smoke-004"
R=$(curl -s -X POST "$BASE/ledger/postings" \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "smoke-004",
    "businessEventType": "WITHDRAWAL",
    "businessEventRef": "WTH-001",
    "valueDate": "2026-05-18",
    "legs": [
      {
        "legId": "leg-1",
        "postingType": "WITHDRAWAL",
        "lines": [
          {"accountId": "CLIENT_ACC_001", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "DEBIT", "amount": "300.00", "description": "Client withdrawal"},
          {"accountId": "COMPANY_FX_ACC", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "CREDIT", "amount": "300.00", "description": "Company receive"}
        ]
      }
    ]
  }')
check "idempotent posting" "COMPLETED" "$R"

# 10. Balance unchanged after idempotent retry
echo "[10] Balance unchanged after idempotent retry"
R=$(curl -s "$BASE/ledger/balances?accountId=CLIENT_ACC_001&balanceType=AVAILABLE_BALANCE&currency=USD")
check "balance still 700.00" "700.00" "$R"

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ] && echo "SMOKE TESTS PASSED" || echo "SMOKE TESTS FAILED"
exit $FAIL
