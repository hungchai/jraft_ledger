# Ledger Platform — Testing Guide

## Quick Start

```bash
# 1. Build and start all services
docker compose up -d --build

# 2. Wait for services (MySQL + Kafka + 3 Raft nodes)
docker compose ps   # all should be "healthy" or "running"

# 3. Run smoke tests
./scripts/smoke-test.sh http://localhost:8081

# 4. Run regression tests
./scripts/regression-test.sh http://localhost:8081

# 5. Run stress tests (50 concurrent, 200 total)
./scripts/stress-test.sh http://localhost:8081 50 200

# 6. Stop
docker compose down
```

## Test Layers

| Layer | Tool | Location | Count |
|---|---|---|---|
| Unit tests | JUnit 5 + AssertJ | `*/src/test/java/` | 119 |
| REST integration | Spring Boot Test + MockMvc | `ledger-restful/src/test/` | 10 |
| Raft cluster | SOFAJRaft in-process | `ledger-core/src/test/` | 2 |
| RocksDB | Embedded RocksDB | `ledger-core/src/test/` | 6 |
| Kafka | Testcontainers Kafka | `ledger-core/src/test/` | 2 |
| Smoke tests | curl + bash | `scripts/smoke-test.sh` | 10 steps |
| Regression | curl + bash | `scripts/regression-test.sh` | 9 sections |
| Stress tests | curl + bash concurrent | `scripts/stress-test.sh` | Configurable |

## Running JUnit Tests

```bash
# All 119 tests
mvn test

# Specific test
mvn test -pl ledger-core -Dtest=LedgerStateMachineTest

# Specific method
mvn test -pl ledger-core -Dtest="LedgerStateMachineTest#applyPosting_singleDebit_balanceDecreased"

# Exclude slow tests (Kafka, Raft cluster)
mvn test -pl ledger-core -Dtest='!KafkaIntegrationTest,!RaftClusterIntegrationTest'
mvn test -pl ledger-service
mvn test -pl ledger-restful
```

## Docker Compose Architecture

```
                  ┌─────────────┐
                  │   caller    │
                  └──────┬──────┘
                         │ HTTP :8081/2/3
              ┌──────────┼──────────┐
              ▼          ▼          ▼
        ┌─────────┐┌─────────┐┌─────────┐
        │ledger-1 ││ledger-2 ││ledger-3 │   Raft Cluster
        │ :8081   ││ :8082   ││ :8083   │   (3 nodes)
        └────┬────┘└────┬────┘└────┬────┘
             │          │          │
             └──────────┼──────────┘
                        │ Raft RPC (Bolt)
                        │
              ┌─────────┴─────────┐
              ▼                   ▼
        ┌──────────┐       ┌──────────┐
        │  MySQL   │       │  Kafka   │
        │  :3306   │       │  :9092   │
        └──────────┘       └──────────┘
         View Layer         Event Bus
```

## Data Persistence

All data is mounted to `./jraft_ledger/` on the host:

```
jraft_ledger/
├── mysql/          # MySQL data
├── kafka/          # Kafka data
├── node1/          # Ledger node 1 RocksDB + Raft log
├── node2/          # Ledger node 2 RocksDB + Raft log
└── node3/          # Ledger node 3 RocksDB + Raft log
```

To reset: `rm -rf ./jraft_ledger && docker compose down -v`

## Smoke Test Flow

The smoke test (`scripts/smoke-test.sh`) covers the main business flow:

1. Health check
2. Create CLIENT_ACC_001 account
3. Create COMPANY_FX_ACC account
4. Deposit 1000 USD (balanced posting)
5. Verify balance = 1000.00
6. Withdraw 300 USD
7. Verify balance = 700.00
8. Find journal by requestId
9. Idempotency — resend same request (balance unchanged)
10. Verify balance still 700.00

## Regression Test Sections

| Section | Endpoints Tested |
|---|---|
| Health | `GET /health` |
| Account Management | `POST /ledger/accounts`, freeze, unfreeze, add balance type, duplicate rejection |
| Posting | Valid posting, unbalanced rejection, insufficient balance rejection |
| Balance Query | `GET /ledger/balances`, multi-currency |
| Journal Query | `GET /ledger/journals/by-request-id`, `GET /ledger/journals/{id}`, paged |
| Reversal | `POST /ledger/journals/{id}/reversal` |
| Adjustment | Create draft → approve (Maker-Checker) |
| EOD | `POST /ledger/periods/eod` |
| Reconciliation | `POST /ledger/reconciliation/l2` |

## Curl Quick Reference

```bash
BASE=http://localhost:8081

# Health
curl $BASE/health

# Create account
curl -X POST $BASE/ledger/accounts -H "Content-Type: application/json" -d '{
  "requestId": "req-001", "accountId": "MY_ACC", "accountType": "CLIENT",
  "displayName": "My Account", "ownerId": "CUST-001",
  "balanceInitializations": [{"balanceType": "AVAILABLE_BALANCE", "currency": "USD"}]
}'

# Post (move balance)
curl -X POST $BASE/ledger/postings -H "Content-Type: application/json" -d '{
  "requestId": "req-002", "businessEventType": "DEPOSIT", "businessEventRef": "DEP-001",
  "valueDate": "2026-05-18",
  "legs": [{"legId": "leg-1", "postingType": "DEPOSIT", "lines": [
    {"accountId": "CO_ACC", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "DEBIT", "amount": "500.00"},
    {"accountId": "MY_ACC", "balanceType": "AVAILABLE_BALANCE", "currency": "USD", "entryType": "CREDIT", "amount": "500.00"}
  ]}]
}'

# Check balance
curl "$BASE/ledger/balances?accountId=MY_ACC&balanceType=AVAILABLE_BALANCE&currency=USD"

# Find journal
curl "$BASE/ledger/journals/by-request-id?requestId=req-002"

# Reverse
curl -X POST $BASE/ledger/journals/JNL-0001/reversal -H "Content-Type: application/json" -d '{
  "requestId": "rev-001", "reversalReason": "Test", "reversalReasonCode": "TEST",
  "valueDate": "2026-05-18"
}'

# EOD
curl -X POST $BASE/ledger/periods/eod -H "Content-Type: application/json" -d '{"date": "2026-05-17"}'
```

## Adding New Tests

### Unit test (ledger-core or ledger-service)

```java
@Test
@DisplayName("TC-XXX-01 description")
void methodName_scenario_expectedResult() {
    // Given
    // When
    // Then
    assertThat(result).isEqualTo(expected);
}
```

### Shell test (scripts/)

```bash
R=$(curl -s "$BASE/endpoint")
check "description" "expected_value" "$R"
```
