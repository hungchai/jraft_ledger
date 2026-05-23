# F-012 Projection Service — Kafka → MySQL View Layer

**Version**: v0.1
**Feature**: F-012 Projection Service
**System**: Next-Gen Internal Ledger Platform
**Dependencies**: F-011 Balance Change Event, F-011b Posting Completion Event, MySQL View Layer

---

## 1. Feature Overview

Projection Service is the **read-path synchronization service** in the CQRS architecture. It is deployed independently, consumes ledger events from Kafka, and projects them into the MySQL View Layer for use by Journal Query (F-006), Reconciliation (F-007), and other queries.

**Core Principles**:
- **Independent deployment**: not embedded in Ledger nodes; crashes do not affect posting
- **At-least-once consumption**: guarantees no duplicate writes through idempotent inserts
- **Projected data**: journal + journal_line + account + account_balance; read-path APIs query MySQL to offload Raft nodes

---

## 2. Architecture Position

```
Ledger Node (Leader)                Ledger Node (Follower)
     │                                     │
     ├─ StateMachine.apply()               ├─ StateMachine.apply()
     ├─ RocksDB persist                    ├─ RocksDB persist
     └─ Kafka publish ──────────────┐      └─ Kafka publish ───┐
                                    │                           │
                              ┌─────▼─────┐              ┌──────▼─────┐
                              │   Kafka   │              │   Kafka    │
                              │  Topic    │              │  Topic     │
                              └─────┬─────┘              └──────┬─────┘
                                    │                           │
                                    └──────────┬────────────────┘
                                               │
                                    ┌──────────▼──────────┐
                                    │ Projection Service  │
                                    │ (independent :8089) │
                                    │                     │
                                    │ Kafka Consumer Group│
                                    │ → MySQL INSERT      │
                                    └──────────┬──────────┘
                                               │
                                        ┌──────▼──────┐
                                        │    MySQL    │
                                        │ (View Layer)│
                                        └─────────────┘
```

---

## 3. Kafka Consumer Design

### 3.1 Consumer Configuration

```yaml
kafka:
  bootstrap-servers: ledger-kafka:9092
  consumer:
    group-id: ledger-projection
    auto-offset-reset: earliest
  topics:
    account-created: ledger.account.v1
    balance-change: ledger.balance.change.v1
```

### 3.2 Consumer Group Strategy

- **Group ID**: `ledger-projection` (shared across instances)
- **Partition assignment**: 64 partitions, supporting up to 64 Projection instances for horizontal scaling
- **Ordering guarantee**: events for the same `accountId:balanceType:currency` must be on the same partition (routed by partition key), ensuring ordered projection

---

## 4. Projection Logic

### 4.1 AccountCreatedEvent → MySQL

```
Receive AccountCreatedEvent {
  accountId, accountType, displayName, ownerId,
  status, balanceTypes, createdAt, ...
}
       │
       ▼
1. UPSERT INTO account (idempotent: ON DUPLICATE KEY UPDATE)
   - account_id, account_type, display_name, owner_id, status, created_at

2. If no account_balance row exists, auto-initialize one record per balanceType:
   - account_id, balance_type, currency=default, amount=0,
     frozen_amount=0, locked_amount=0, account_seq=0
```

### 4.2 BalanceChangeEvent → MySQL

```
Receive BalanceChangeEvent {
  journalId, journalLineId, commandType,
  accountId, balanceType, currency,
  entryType, amount, preBalance, postBalance,
  accountSeq, prevAccountSeq, valueDate, ...
}
       │
       ▼
1. INSERT INTO journal (idempotent: ON DUPLICATE KEY IGNORE)
   - journal_id, journal_type, request_id, business_event_type, business_event_ref,
     value_date, status, cross_period, created_at

2. INSERT INTO journal_line (idempotent: ON DUPLICATE KEY IGNORE)
   - journal_line_id, journal_id, leg_id, account_id, balance_type, currency,
     entry_type, amount, balance_before, balance_after, config_version, created_at

3. UPSERT INTO account_balance (idempotent: ON DUPLICATE KEY UPDATE)
   - account_id, balance_type, currency, amount, account_seq, last_journal_id
   - frozen_amount and locked_amount are preserved (not overwritten)

4. Log projection status (DEBUG level)
```

### 4.3 Balance Type Registry Initialization

On startup, the Projection Service automatically checks and inserts default balance_type_registry records (e.g. `AVAILABLE`, `PENDING_SETTLEMENT`) for queries and auditing.

### 4.4 Idempotency Guarantee

```sql
-- journal table uses journal_id as UNIQUE KEY, id as auto-increment PK
-- when consuming the same event again, INSERT ... ON DUPLICATE KEY skips without exception
INSERT INTO journal (...) VALUES (...);

-- journal_line table uses journal_line_id as UNIQUE KEY, id as auto-increment PK
-- also idempotent
INSERT INTO journal_line (...) VALUES (...);

-- account table uses account_id as UNIQUE KEY
-- on duplicate creation, updates account_type, status, display_name
INSERT INTO account (...) VALUES (...)
ON DUPLICATE KEY UPDATE account_type = VALUES(account_type), status = VALUES(status), display_name = VALUES(display_name);

-- account_balance table uses (account_id, balance_type, currency) as UNIQUE KEY
INSERT INTO account_balance (...) VALUES (...)
ON DUPLICATE KEY UPDATE amount = VALUES(amount), account_seq = VALUES(account_seq), last_journal_id = VALUES(last_journal_id);
```

---

## 5. Deployment

### 5.1 Docker Compose

```yaml
projection:
  build:
    context: .
    dockerfile: Dockerfile.projection
  container_name: ledger-projection
  ports:
    - "8089:8089"
  environment:
    SPRING_DATASOURCE_URL: jdbc:mysql://ledger-mysql:3306/ledger_view
    SPRING_DATASOURCE_USERNAME: ledger
    SPRING_DATASOURCE_PASSWORD: ledger123
    KAFKA_BOOTSTRAP_SERVERS: ledger-kafka:9092
  depends_on:
    mysql:
      condition: service_healthy
    kafka:
      condition: service_started
```

### 5.2 Resource Configuration

| Parameter | Value | Description |
|---|---|---|
| JVM Heap | 1GB | Pure Kafka → MySQL mapping, does not require large memory |
| CPU | 1 core | Lightweight consumer |
| Instances | 1–64 | Scale as needed (depends on partition count and throughput requirements) |

---

## 6. Monitoring

| Metric | Description |
|---|---|
| `kafka_consumer_records_consumed_total` | Total records consumed |
| `kafka_consumer_records_lag` | Consumer lag |
| `mysql_insert_latency_seconds` | MySQL write latency |
| `projection_errors_total` | Projection failure count |

---

## 7. Acceptance Criteria

| # | Condition | Test Method |
|---|---|---|
| AC-01 | After a Kafka event arrives, it appears in the MySQL journal table within 1 second | Functional Test |
| AC-02 | Consuming the same event again (same journalId) does not create duplicate records in MySQL | Idempotency Test |
| AC-03 | After Projection crashes and restarts, it resumes consumption from the last committed offset with no data loss | Failure Recovery Test |
| AC-04 | Multiple Projection instances share the same consumer group; partitions are evenly distributed | Scaling Test |
| AC-05 | When MySQL is unavailable, Projection does not crash; it pauses consumption and waits for MySQL to recover | Fault Tolerance Test |
| AC-06 | After an AccountCreatedEvent arrives, the account table and account_balance initialization records are written correctly | Functional Test |
| AC-07 | Consuming the same AccountCreatedEvent again (same accountId) updates status and display_name without creating duplicates | Idempotency Test |
| AC-08 | frozen_amount and locked_amount in account_balance remain unchanged after BalanceChangeEvent projection | Consistency Test |
