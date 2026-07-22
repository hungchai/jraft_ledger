# F-011 Balance Change Event — Kafka Output

**Document Version**: v0.2
**Feature**: F-011 Balance Change Event
**System**: Next-Gen Internal Ledger Platform
**Status**: Draft for Review
**Dependencies**: ADR-001, F-008 State Machine, F-002 Posting, F-003 Manual Adjustment, F-004 Reversal

> **v0.2 Change Summary**: Event schema added `accountSeq` / `prevAccountSeq` fields (eventVersion raised to 1.1), Section 5.4 accounting sequence fields description supplemented, AC-12 / AC-13 / AC-14 added.

---

## 1. Feature Overview

Each time the Raft State Machine successfully commits any Command that causes a Balance change (Posting, Reversal, Manual Adjustment), a `BalanceChangeEvent` is immediately sent to Kafka for downstream systems (Risk Engine, VAMP, transaction notification, etc.) to consume.

Core principles:
- **Sent after Raft commit**: Only Quorum-confirmed changes emit events; unconfirmed changes are not sent
- **At-least-once delivery**: Duplicates are allowed; downstream deduplicates by `idempotencyKey`
- **Non-blocking main flow**: Kafka publish failure does not affect Posting result; asynchronous retry
- **All Balance Types emit**: AVAILABLE_BALANCE, TRADE_AHEAD_BALANCE, BROKERAGE_BALANCE, etc. all emit

---

## 2. Trigger Scenarios

| Trigger Source | Command Type | Description |
|---|---|---|
| Posting API (F-002) | `POSTING` | Normal booking; each JournalLine's corresponding balance change |
| Reversal (F-004) | `REVERSAL` | Reversal booking |
| Manual Adjustment (F-003) | `MANUAL_ADJUSTMENT` | Manual adjustment after checker approval |

**Non-trigger Scenarios**:
- Balance Query (read-only)
- Account freeze / unfreeze / close (no balance value change)
- Reconciliation Case update (no direct balance change)
- EOD Snapshot generation (not a new booking)

---

## 3. Kafka Topic Design

```
Topic Name:  ledger.balance.change.v1
Partitions:  64 (scalable on demand)
Retention:   7 days
Compression: LZ4
```

### Partition Key Strategy

```
partitionKey = accountId + ":" + balanceType + ":" + currency
```

**Design rationale**:
- Events for the same account, same balance type, same currency are guaranteed **ordering**
- Different accounts run in parallel, avoiding hotspot partition (COMPANY_FX_ACC won't overwhelm a single partition)
- Downstream Risk Engine can precisely consume corresponding partition when subscribing by account

---

## 4. Event Structure (BalanceChangeEvent)

```json
{
  "eventId": "evt-01JWXYZ123456789ABCDEF",
  "eventType": "BALANCE_CHANGE",
  "eventVersion": "1.1",
  "occurredAt": "2026-05-17T23:30:00.123456789Z",

  "idempotencyKey": "req-01JWXYZ000000001:CLIENT_ACC_001:AVAILABLE_BALANCE:USD",

  "commandType": "POSTING",
  "journalId": "JNL-01JWXYZ000000001",
  "journalLineId": "JLL-01JWXYZ000000001-01",
  "requestId": "req-01JWXYZ000000001",
  "businessEventRef": "RFQ-20260517-001",
  "traceId": "trace-abc123",

  "accountId": "CLIENT_ACC_001",
  "balanceType": "AVAILABLE_BALANCE",
  "currency": "USD",

  "entryType": "DEBIT",
  "amount": "800.00",

  "preBalance": "1000.00",
  "postBalance": "200.00",
  "balanceDelta": "-800.00",

  "raftLogIndex": 100245,
  "stateVersion": 100245,
  "accountSeq": 1042,
  "prevAccountSeq": 1041,
  "accountingDate": "2026-05-17",
  "valueDate": "2026-05-17",

  "metadata": {
    "operatorId": "system",
    "sourceSystem": "LEDGER",
    "crossPeriod": false
  }
}
```

---

## 5. Field Descriptions

### 5.1 Event Identification

| Field | Type | Description |
|---|---|---|
| `eventId` | `string` | Event unique ID (UUID v7, time-sortable) |
| `eventType` | `string` | Fixed value `BALANCE_CHANGE` |
| `eventVersion` | `string` | Schema version for downstream compatibility management |
| `occurredAt` | `timestamp` | Raft commit completion time (nanosecond precision, UTC) |
| `idempotencyKey` | `string` | `requestId:accountId:balanceType:currency`, used for downstream idempotent deduplication |

### 5.2 Source Traceability

| Field | Type | Description |
|---|---|---|
| `commandType` | `enum` | `POSTING` / `REVERSAL` / `MANUAL_ADJUSTMENT` |
| `journalId` | `string` | Corresponding Journal ID |
| `journalLineId` | `string` | Corresponding specific JournalLine ID |
| `requestId` | `string` | Original request idempotency key |
| `businessEventRef` | `string` | Business event reference (e.g. RFQ ID, Order ID) |
| `traceId` | `string` | Distributed tracing ID |

### 5.3 Account and Balance

| Field | Type | Description |
|---|---|---|
| `accountId` | `string` | Account ID |
| `balanceType` | `string` | Balance Type (from F-001 Registry) |
| `currency` | `string` | Currency (ISO 4217) |
| `entryType` | `enum` | `DEBIT` / `CREDIT` |
| `amount` | `decimal string` | Amount of this change (positive; direction determined by entryType) |
| `preBalance` | `decimal string` | Balance before change |
| `postBalance` | `decimal string` | Balance after change |
| `balanceDelta` | `decimal string` | Net change (negative = DEBIT, positive = CREDIT) |

> **Amounts use decimal string instead of float** to avoid floating-point precision issues; downstream parses as BigDecimal

### 5.4 Accounting Sequence [v0.2 update]

| Field | Type | Description |
|---|---|---|
| `raftLogIndex` | `long` | Raft log index, global order |
| `stateVersion` | `long` | State Machine version (same as raftLogIndex) |
| `accountSeq` | `long` | **[v0.2 new]** Per-account monotonically increasing sequence number, dimension is `accountId + balanceType + currency`, starts from 1; increments on each balance change for Posting / Reversal / Adjustment |
| `prevAccountSeq` | `long` | **[v0.2 new]** Previous event's `accountSeq` in the same dimension; normally `accountSeq = prevAccountSeq + 1`; first event `prevAccountSeq = 0`; downstream can use this field to detect event stream gap |
| `accountingDate` | `date` | Accounting date (accounting period) |
| `valueDate` | `date` | Value date |

---

## 6. Publishing Architecture

```
Raft Leader
    │
    │  onStateMachineApply()
    ▼
LedgerStateMachine.apply(command)
    │
    ├── Update in-memory balance (including accountSeq increment)
    ├── Write RocksDB WriteBatch (Journal + Balance)
    │
    └── Build BalanceChangeEvent (including accountSeq / prevAccountSeq)
            │
            ▼
    OutboxStore (RocksDB CF_OUTBOX)
            │
    AsyncKafkaPublisher (Virtual Thread)
            │
            ▼
    Kafka Topic: ledger.balance.change.v1
```

### 6.1 Outbox Pattern (ensuring at-least-once)

```
1. During State Machine apply(), BalanceChangeEvent is written to
   RocksDB CF_OUTBOX (same WriteBatch, atomic with balance update)
   BalanceChangeEvent's accountSeq is determined at apply time; resending does not change it

2. AsyncKafkaPublisher background thread:
   a. Scan CF_OUTBOX for unsent events
   b. Send to Kafka
   c. Send success → delete from CF_OUTBOX (or mark SENT)
   d. Send failure → exponential backoff retry, up to 10 times

3. Restart recovery:
   a. After node restart, AsyncKafkaPublisher rescans CF_OUTBOX
   b. Unsent events continue to be delivered, accountSeq same as original
   c. Downstream deduplicates by idempotencyKey (resent events have same accountSeq, not a gap)
```

### 6.2 Delivery Guarantees

| Guarantee | Implementation |
|---|---|
| **No loss** (Outbox atomic write) | BalanceChangeEvent is in the same RocksDB WriteBatch as Balance update |
| **At-least-once** | Outbox retry mechanism; continues retry on failure |
| **Non-blocking main path** | Kafka publish is asynchronous; failure does not affect Posting return |
| **Ordering (same account same type)** | Partition key = accountId:balanceType:currency |
| **Downstream idempotency** | Each event carries unique `idempotencyKey`; downstream deduplicates |
| **Downstream gap detection** | `accountSeq` / `prevAccountSeq` for downstream stream integrity verification |

---

## 7. Multi-Balance-Type Scenario Example

Taking the RFQ scenario as an example, one Posting involves 2 accounts:

```
Posting: CLIENT_ACC_001 AVAILABLE_BALANCE/USD DEBIT 800
         COMPANY_FX_ACC AVAILABLE_BALANCE/USD CREDIT 800
```

Kafka emits **2 independent events** (assuming their previous seq were 41 and 99 respectively):

```json
// Event 1
{
  "accountId": "CLIENT_ACC_001",
  "balanceType": "AVAILABLE_BALANCE",
  "currency": "USD",
  "entryType": "DEBIT",
  "amount": "800.00",
  "preBalance": "1000.00",
  "postBalance": "200.00",
  "balanceDelta": "-800.00",
  "accountSeq": 42,
  "prevAccountSeq": 41,
  "idempotencyKey": "req-001:CLIENT_ACC_001:AVAILABLE_BALANCE:USD"
}

// Event 2
{
  "accountId": "COMPANY_FX_ACC",
  "balanceType": "AVAILABLE_BALANCE",
  "currency": "USD",
  "entryType": "CREDIT",
  "amount": "800.00",
  "preBalance": "5000.00",
  "postBalance": "5800.00",
  "balanceDelta": "800.00",
  "accountSeq": 100,
  "prevAccountSeq": 99,
  "idempotencyKey": "req-001:COMPANY_FX_ACC:AVAILABLE_BALANCE:USD"
}
```

If the same Journal updates both `AVAILABLE_BALANCE` and `TRADE_AHEAD_BALANCE`, each balance type emits one event, each maintaining its own independent `accountSeq`.

---

## 8. Schema Evolution Strategy

- `eventVersion` uses semantic versioning (`1.0`, `1.1`, `2.0`)
- **v0.2 this change**: `1.0` → `1.1` (backward compatible minor change)
  - Added `accountSeq`, `prevAccountSeq` two **optional fields**
  - Downstream legacy consumers can ignore new fields without forced upgrade
- **Breaking change** (deleting or changing field type): version bumps major (`1.x` → `2.0`), old and new topics run in parallel for a period
- Recommend downstream routing by `eventVersion`, do not hard-code fields

---

## 9. API / Configuration

```yaml
# application.yml
ledger:
  kafka:
    bootstrap-servers: kafka1:9092,kafka2:9092,kafka3:9092
    topic: ledger.balance.change.v1
    partitions: 64
    acks: all               # Wait for all ISR confirmations
    retries: 10
    retry-backoff-ms: 500
    compression: lz4
    outbox:
      scan-interval-ms: 100
      max-retry: 10
      retry-backoff-multiplier: 2
```

---

## 10. Acceptance Criteria

| # | Acceptance Condition | Test Method |
|---|---|---|
| AC-01 | After Posting succeeds, Kafka receives corresponding BalanceChangeEvent | Functional Test |
| AC-02 | Event's preBalance, postBalance are consistent with State Machine | Functional Test |
| AC-03 | Same Posting (multiple JournalLines) emits multiple independent events, each with correct balanceDelta | Functional Test |
| AC-04 | Reversal emits event with entryType opposite to original Posting | Functional Test |
| AC-05 | After Manual Adjustment is booked, event is emitted with commandType=MANUAL_ADJUSTMENT | Functional Test |
| AC-06 | Kafka publish failure does not affect Posting result; event retries from Outbox | Failure Injection Test |
| AC-07 | After node restart, unsent events from Outbox continue to be delivered without loss | Failure Injection Test |
| AC-08 | Same requestId Posting retry (idempotent) only emits one event | Functional Test |
| AC-09 | Events for same account same balanceType land in same partition, order correct | Functional Test |
| AC-10 | Kafka publish P95 latency (from Raft commit to Kafka confirm) ≤ 100ms | Performance Test |
| AC-11 | Changes for all balance types (including TRADE_AHEAD_BALANCE) emit events | Functional Test |
| AC-12 | For the same accountId + balanceType + currency dimension, BalanceChangeEvent accountSeq strictly monotonically increases, no skips, no duplicates | Functional Test |
| AC-13 | After system restart, accountSeq recovers from RocksDB / Snapshot; first new event's accountSeq = last event before restart + 1, must not reset to 0 | Failure Recovery Test |
| AC-14 | Downstream can precisely detect event stream gap through prevAccountSeq ≠ previously received accountSeq, and trigger alert | Downstream Integration Test |
