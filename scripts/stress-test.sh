#!/bin/bash
# Stress test — concurrent postings against a single account
set -e

BASE="${1:-http://localhost:8081}"
CONCURRENT="${2:-50}"
COUNT="${3:-200}"
ACCOUNT="STRESS_ACC_001"
PASS=0; FAIL=0; START=$(date +%s)

echo "=== Stress Test: $CONCURRENT concurrent, $COUNT total postings ==="

# Setup: create stress account with balance
echo "[Setup] Creating stress account with balance"
curl -s -X POST "$BASE/ledger/accounts" -H "Content-Type: application/json" -d "{
  \"requestId\": \"stress-setup\",
  \"accountId\": \"$ACCOUNT\",
  \"accountType\": \"CLIENT\",
  \"displayName\": \"Stress Test\",
  \"ownerId\": \"CUST-STRESS\",
  \"balanceInitializations\": [
    {\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\"}
  ]
}" > /dev/null

# Create company account for pairing
curl -s -X POST "$BASE/ledger/accounts" -H "Content-Type: application/json" -d "{
  \"requestId\": \"stress-setup-co\",
  \"accountId\": \"STRESS_CO\",
  \"accountType\": \"COMPANY\",
  \"displayName\": \"Stress Company\",
  \"balanceInitializations\": [
    {\"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\"}
  ]
}" > /dev/null

# Seed initial balance
curl -s -X POST "$BASE/ledger/postings" -H "Content-Type: application/json" -d "{
  \"requestId\": \"stress-seed\",
  \"businessEventType\": \"DEPOSIT\",
  \"businessEventRef\": \"STRESS-SEED\",
  \"valueDate\": \"2026-05-18\",
  \"legs\": [
    {
      \"legId\": \"leg-1\",
      \"postingType\": \"DEPOSIT\",
      \"lines\": [
        {\"accountId\": \"STRESS_CO\", \"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\", \"entryType\": \"DEBIT\", \"amount\": \"100000.00\", \"description\": \"Seed\"},
        {\"accountId\": \"$ACCOUNT\", \"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\", \"entryType\": \"CREDIT\", \"amount\": \"100000.00\", \"description\": \"Seed\"}
      ]
    }
  ]
}" > /dev/null

echo "[Running] $COUNT postings with $CONCURRENT workers..."

# Send requests using background jobs
send_batch() {
  local start=$1 end=$2
  for i in $(seq $start $end); do
    curl -s -X POST "$BASE/ledger/postings" -H "Content-Type: application/json" \
      -d "{
        \"requestId\": \"stress-$i\",
        \"businessEventType\": \"STRESS\",
        \"businessEventRef\": \"STRESS-BATCH\",
        \"valueDate\": \"2026-05-18\",
        \"legs\": [
          {
            \"legId\": \"leg-$i\",
            \"postingType\": \"STRESS\",
            \"lines\": [
              {\"accountId\": \"$ACCOUNT\", \"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\", \"entryType\": \"DEBIT\", \"amount\": \"1.00\", \"description\": \"Stress $i\"},
              {\"accountId\": \"STRESS_CO\", \"balanceType\": \"AVAILABLE_BALANCE\", \"currency\": \"USD\", \"entryType\": \"CREDIT\", \"amount\": \"1.00\", \"description\": \"Stress $i\"}
            ]
          }
        ]
      }" > /dev/null &
  done
  wait
}

BATCH=$((COUNT / CONCURRENT))
for b in $(seq 0 $((CONCURRENT - 1))); do
  S=$((b * BATCH + 1))
  E=$(((b + 1) * BATCH))
  send_batch $S $E &
done
wait

END=$(date +%s)
ELAPSED=$((END - START))

# Verify
echo "[Verify] Checking final balance..."
BALANCE=$(curl -s "$BASE/ledger/balances?accountId=$ACCOUNT&balanceType=AVAILABLE_BALANCE&currency=USD" | grep -o '"amount":[0-9.]*' | cut -d: -f2)
EXPECTED=$((100000 - COUNT))
echo "  Expected: $EXPECTED.00, Got: $BALANCE"
echo "  Elapsed: ${ELAPSED}s, Throughput: $((COUNT / (ELAPSED > 0 ? ELAPSED : 1))) req/s"

# Cleanup not needed — state persists in ledger
echo "=== Stress Test Complete ==="
